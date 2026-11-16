import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue'
import { getAssessFillPage, saveAssessDraft, submitAssessFill } from '@/api/assessTask'
import type { AssessFillElement, AssessFillPage, AssessFillRating } from '@/types/assessTask'

/**
 * 评价填写页的全部业务逻辑（2026-11-14 从 AssessFill.vue 抽出）。
 *
 * 抽出来的原因：同一份评价要有 PC 与 H5 两套界面。如果把逻辑复制两份，
 * 草稿版本冲突、提交前完整性校验、未评不许跳题这些规则迟早会在两边跑偏 ——
 * 而它们一旦跑偏，症状是「用户在手机上填完了却提交不了」，很难查。
 *
 * 界面差异（提示怎么弹、确认框长什么样）通过 {@link AssessFillUi} 注入，
 * 所以这里不 import 任何 UI 库，也不假设是 PC 还是手机。
 */

/** 界面层必须提供的两个能力：轻提示 + 提交二次确认 */
export interface AssessFillUi {
  /** 轻提示。type 缺省按 info 处理 */
  notify: (message: string, type?: 'success' | 'warning' | 'info' | 'error') => void
  /** 提交前的二次确认；返回 true 才真正提交 */
  confirmSubmit: () => Promise<boolean>
}

export interface AssessFillGroup {
  categoryId: number
  categoryName: string | null
  items: { element: AssessFillElement; index: number }[]
}

export function useAssessFill(token: Ref<string> | ComputedRef<string>, ui: AssessFillUi) {
  const page = ref<AssessFillPage | null>(null)
  const failed = ref(false)
  /** elementId → 选中的 rateValue */
  const answers = reactive<Record<number, number>>({})
  const draftVersion = ref(0)
  const submitting = ref(false)
  const draftSaving = ref(false)
  /** 当前题号（0 起） */
  const currentIndex = ref(0)

  /** 自动暂存的防抖句柄：每次选择都排一次，200ms 内的连续点击合并成一条请求 */
  let draftTimer: ReturnType<typeof setTimeout> | null = null

  const elements = computed<AssessFillElement[]>(() => page.value?.elements ?? [])
  const totalElements = computed(() => elements.value.length)
  const ratedCount = computed(() => elements.value.filter(e => answers[e.elementId] != null).length)
  const missingCount = computed(() => totalElements.value - ratedCount.value)
  const progressPercent = computed(() =>
    totalElements.value === 0 ? 0 : Math.round((ratedCount.value / totalElements.value) * 100)
  )
  /** 已提交：整页只读，不再提供任何写入入口（2026-11-16 由「提交后可改」改为单向提交） */
  const submitted = computed(() => page.value?.status === 'SUBMITTED')

  /** 状态页（已撤回 / 已取消）：不渲染表单，两端共用同一套判定 */
  const statusOnly = computed(
    () => !page.value || page.value.status === 'VOIDED' || page.value.status === 'CANCELLED'
  )

  const currentElement = computed<AssessFillElement | null>(() => elements.value[currentIndex.value] ?? null)
  const isLast = computed(() => currentIndex.value >= totalElements.value - 1)
  const currentUnanswered = computed(() => {
    const el = currentElement.value
    return !!el && answers[el.elementId] == null
  })

  /** 第一个未评要素的下标；全评完则为总数（即「没有可跳的未评题」） */
  const firstUnansweredIndex = computed(() => {
    const i = elements.value.findIndex(e => answers[e.elementId] == null)
    return i < 0 ? totalElements.value : i
  })

  /** 按类别分组，保持服务端返回的排序（类别 → sort_no），并带上全局题号 */
  const groupedElements = computed<AssessFillGroup[]>(() => {
    const groups: AssessFillGroup[] = []
    elements.value.forEach((element, index) => {
      let group = groups.find(g => g.categoryId === element.categoryId)
      if (!group) {
        group = { categoryId: element.categoryId, categoryName: element.categoryName, items: [] }
        groups.push(group)
      }
      group.items.push({ element, index })
    })
    return groups
  })

  const levelLabel = (element: AssessFillElement, rateValue: number): string => {
    const lv = element.levels.find(l => l.rateValue === rateValue)
    return lv ? lv.levelDesc || lv.levelNo + ' 级' : String(rateValue)
  }

  const load = async () => {
    try {
      const data = await getAssessFillPage(token.value)
      page.value = data
      // 状态页（VOIDED / CANCELLED）不初始化表单
      if (data.status === 'VOIDED' || data.status === 'CANCELLED') {
        return
      }
      draftVersion.value = data.draftVersion ?? 0
      for (const [elementId, levelNo] of Object.entries(data.draft ?? {})) {
        answers[Number(elementId)] = Number(levelNo)
      }
      // 断点续填：直接落到第一个未评的要素（不能跳题，所以这就是唯一的续填点，§4.2.2）
      currentIndex.value = Math.min(firstUnansweredIndex.value, Math.max(totalElements.value - 1, 0))
    } catch {
      failed.value = true
    }
  }

  const onSelect = (elementId: number, rateValue: number) => {
    // 已提交：只读查看。选择器已置灰，这里再兜一层，防止键盘/程序触发改动
    if (submitted.value) {
      ui.notify('评价已提交，不可再修改', 'warning')
      return
    }
    answers[elementId] = rateValue
    scheduleDraftSave()
  }

  /**
   * 跳题。为了满足「未评不能跳过」，只有「前面全部已评」的题才允许跳；
   * 跳到更靠后的未评题会提示先完成当前第一道未评题（§6.3.2）。
   *
   * @returns 是否真的跳过去了 —— 界面据此决定要不要关掉一览浮层
   */
  const jumpTo = (index: number): boolean => {
    if (index > firstUnansweredIndex.value) {
      const blocker = elements.value[firstUnansweredIndex.value]
      ui.notify(
        blocker ? `请先完成第 ${firstUnansweredIndex.value + 1} 项「${blocker.elementName}」` : '请按顺序作答',
        'warning'
      )
      return false
    }
    currentIndex.value = index
    return true
  }

  const goPrev = () => {
    if (currentIndex.value > 0) {
      currentIndex.value -= 1
    }
  }

  const goNext = () => {
    if (currentUnanswered.value || isLast.value) {
      return
    }
    currentIndex.value += 1
  }

  /** 每次选择自动暂存（§4.2.2），200ms 防抖 */
  const scheduleDraftSave = () => {
    if (draftTimer) {
      clearTimeout(draftTimer)
    }
    draftTimer = setTimeout(() => {
      void saveDraftNow(true)
    }, 200)
  }

  /** 离开页面前调用：把还没触发的暂存清掉，避免页面卸载后仍发请求 */
  const dispose = () => {
    if (draftTimer) {
      clearTimeout(draftTimer)
      draftTimer = null
    }
  }

  const currentRatings = (): AssessFillRating[] =>
    Object.entries(answers)
      .filter(([, levelNo]) => levelNo != null)
      .map(([elementId, levelNo]) => ({ elementId: Number(elementId), levelNo }))

  /**
   * 暂存草稿。版本冲突（其他标签页已写入）时用服务端返回的草稿覆盖本地，
   * 并把版本号对齐 —— 否则本次填写会被下一次暂存反复拒绝。
   */
  const saveDraftNow = async (silent = true) => {
    // 已提交：草稿即最终答案，前端不再发暂存请求（后端同样会拒绝）
    if (submitted.value) {
      return
    }
    const ratings = currentRatings()
    if (ratings.length === 0) {
      return
    }
    draftSaving.value = true
    try {
      const r = await saveAssessDraft(token.value, ratings, draftVersion.value)
      if (r.conflict) {
        draftVersion.value = r.draftVersion
        const latest = r.latestDraft ?? {}
        if (Object.keys(latest).length > 0) {
          ui.notify('该评价已在其他页面修改，已加载最新内容', 'warning')
          for (const key of Object.keys(answers)) {
            delete answers[Number(key)]
          }
          for (const [elementId, levelNo] of Object.entries(latest)) {
            answers[Number(elementId)] = Number(levelNo)
          }
        }
      } else {
        draftVersion.value = r.draftVersion
        if (!silent) {
          ui.notify('草稿已保存', 'success')
        }
      }
    } catch {
      // 暂存失败不阻断填写：用户仍可点提交，提交时才做完整性校验
    } finally {
      draftSaving.value = false
    }
  }

  const submit = async () => {
    if (missingCount.value > 0) {
      return
    }
    if (submitted.value) {
      return
    }
    // 单向提交：一旦提交不可更改，必须二次确认（2026-11-16）
    const ok = await ui.confirmSubmit()
    if (!ok) {
      return
    }
    submitting.value = true
    try {
      const r = await submitAssessFill(token.value, currentRatings())
      if (r.calculated) {
        ui.notify('已提交，全部评价人已完成，系统已计算得分', 'success')
      } else {
        ui.notify(r.message, 'success')
      }
      // 重新拉一次，拿到最新状态（SUBMITTED）与整体进度
      await load()
    } finally {
      submitting.value = false
    }
  }

  return {
    // 状态
    page,
    failed,
    answers,
    draftVersion,
    submitting,
    draftSaving,
    currentIndex,
    // 派生
    elements,
    totalElements,
    ratedCount,
    missingCount,
    progressPercent,
    submitted,
    statusOnly,
    currentElement,
    isLast,
    currentUnanswered,
    firstUnansweredIndex,
    groupedElements,
    // 行为
    levelLabel,
    load,
    onSelect,
    jumpTo,
    goPrev,
    goNext,
    saveDraftNow,
    submit,
    dispose,
  }
}
