import { computed, reactive, ref, type ComputedRef, type Ref } from 'vue'
import { getPaFillPage, savePaDraft, submitPaFill } from '@/api/paTask'
import type { PaFillItemVO, PaFillPage, PaFillRating } from '@/types/paTask'

/**
 * 评价填写页的全部业务逻辑（方案 17 §7.2），PC 与 H5 共用一份。
 *
 * <p>为什么单独一份而不是改旧的 useAssessFill：新模型的「评级」是
 * {@code uf_pa_item_level.id}（每个能力项自己一套档位、可增删），旧的是「等级号」。
 * 两者不是改名关系 —— 等级号是位置概念，增删档位后会指向别人。强行复用会把旧语义带进来。</p>
 *
 * <p>与旧版一致的三条规则（都不允许两端跑偏）：</p>
 * <ol>
 *   <li>未评不许跳题：只有「前面全部已评」的题才可跳。</li>
 *   <li>每次选择自动暂存，200ms 防抖；版本冲突用服务端草稿覆盖本地。</li>
 *   <li>提交单向、需二次确认；提交后整页只读。</li>
 * </ol>
 */

export interface PaFillUi {
  notify: (message: string, type?: 'success' | 'warning' | 'info' | 'error') => void
  confirmSubmit: () => Promise<boolean>
}

export interface PaFillGroup {
  categoryId: number
  categoryName: string | null
  items: { item: PaFillItemVO; index: number }[]
}

/** 回执页的一行：一项能力 + 我给它打的档位 */
export interface PaReceiptRow {
  item: PaFillItemVO
  index: number
  level: PaFillRatingDetail | null
}

/**
 * 档位快照（从该项自己的 levels 里按 levelId 找回）。
 *
 * <p>{@code levelDesc} 是这一档的行为描述，回执页要展示 —— 只给「良好 / 4 分」这种标签，
 * 提交后回头看根本想不起当初为什么选它。两端都从这里取，不另存字段。</p>
 */
export interface PaFillRatingDetail {
  id: number
  levelName: string
  score: number | null
  levelDesc: string | null
}

/** 回执页的一个分类小节 */
export interface PaReceiptGroup {
  categoryId: number
  categoryName: string | null
  rows: PaReceiptRow[]
}

/**
 * 档位分布：我在这套尺子上各档选了几项。
 *
 * <p>按<b>分值</b>而不是按等级 id 归并：每个能力项有自己的一套等级行（id 各不相同），
 * 而线上同一分值还存在两种写法（「5分 · 开创/专家」与「开创/专家」），
 * 按 id 或按名称都会把同一档拆成两行，只有分值是可比的共同刻度。</p>
 */
export interface PaLevelDistribution {
  score: number | null
  /** 该分值的代表性档位名（取第一项的名字，仅作展示） */
  levelName: string
  count: number
  /** 占全部已评项的比例（0-100 整数） */
  percent: number
}

export function usePaFill(token: Ref<string> | ComputedRef<string>, ui: PaFillUi) {
  const page = ref<PaFillPage | null>(null)
  const failed = ref(false)
  /** itemId → 选中的 levelId */
  const answers = reactive<Record<number, number>>({})
  const draftVersion = ref(0)
  const submitting = ref(false)
  const draftSaving = ref(false)
  /** 当前题号（0 起） */
  const currentIndex = ref(0)

  /** 评价意见（口径 #14）：直接上级 / 360 必填，自评不显示 */
  const comment = ref('')

  let draftTimer: ReturnType<typeof setTimeout> | null = null

  const items = computed<PaFillItemVO[]>(() => page.value?.items ?? [])
  const totalElements = computed(() => items.value.length)
  const ratedCount = computed(() => items.value.filter(i => answers[i.itemId] != null).length)
  const missingCount = computed(() => totalElements.value - ratedCount.value)
  const progressPercent = computed(() =>
    totalElements.value === 0 ? 0 : Math.round((ratedCount.value / totalElements.value) * 100)
  )
  /** 已提交：整页只读，不再提供任何写入入口 */
  const submitted = computed(() => page.value?.status === 'SUBMITTED')

  const commentRequired = computed(() => page.value?.commentRequired === true)

  /**
   * 评价意见是独立的一步，排在所有能力项之后。所以 currentIndex 的取值范围是
   * 0..N-1（能力项）再加一格 N（评价意见），N 只在需要评语时存在。
   */
  const hasCommentStep = computed(() => commentRequired.value && totalElements.value > 0)
  const totalSteps = computed(() => totalElements.value + (hasCommentStep.value ? 1 : 0))
  const onCommentStep = computed(
    () => hasCommentStep.value && currentIndex.value >= totalElements.value
  )
  const isLastStep = computed(() => currentIndex.value >= totalSteps.value - 1)
  const nextIsCommentStep = computed(
    () =>
      hasCommentStep.value &&
      !onCommentStep.value &&
      currentIndex.value === totalElements.value - 1
  )
  const displayIndex = computed(() =>
    Math.min(currentIndex.value + 1, Math.max(totalElements.value, 1))
  )

  const commentMissing = computed(() => commentRequired.value && comment.value.trim().length === 0)

  const currentItem = computed<PaFillItemVO | null>(() => items.value[currentIndex.value] ?? null)
  const isLast = computed(() => currentIndex.value >= totalElements.value - 1)
  const currentUnanswered = computed(() => {
    const item = currentItem.value
    return !!item && answers[item.itemId] == null
  })

  const firstUnansweredIndex = computed(() => {
    const i = items.value.findIndex(it => answers[it.itemId] == null)
    return i < 0 ? totalElements.value : i
  })

  /** 按类别分组，保持服务端返回的排序，并带上全局题号 */
  const groupedItems = computed<PaFillGroup[]>(() => {
    const groups: PaFillGroup[] = []
    items.value.forEach((item, index) => {
      const categoryId = item.categoryId ?? 0
      let group = groups.find(g => g.categoryId === categoryId)
      if (!group) {
        group = { categoryId, categoryName: item.categoryName, items: [] }
        groups.push(group)
      }
      group.items.push({ item, index })
    })
    return groups
  })

  const levelLabel = (item: PaFillItemVO, levelId: number): string => {
    const lv = item.levels.find(l => l.id === levelId)
    return lv ? lv.levelName : String(levelId)
  }

  /** 按 levelId 找回档位快照；草稿里存的是 id，等级被删后可能找不回 —— 返回 null 而不是瞎猜 */
  const levelOf = (item: PaFillItemVO, levelId: number | undefined): PaFillRatingDetail | null => {
    if (levelId == null) {
      return null
    }
    const lv = item.levels.find(l => l.id === levelId)
    if (!lv) {
      return null
    }
    return {
      id: lv.id,
      levelName: lv.levelName,
      score: lv.score ?? null,
      levelDesc: lv.levelDesc ?? null,
    }
  }

  /** 回执页的分组明细：分类 → 能力项 + 我打的档 */
  const receiptGroups = computed<PaReceiptGroup[]>(() =>
    groupedItems.value.map(group => ({
      categoryId: group.categoryId,
      categoryName: group.categoryName,
      rows: group.items.map(({ item, index }) => ({
        item,
        index,
        level: levelOf(item, answers[item.itemId]),
      })),
    }))
  )

  /**
   * 档位分布，按分值从高到低。只统计已评项，分母也是已评项数 ——
   * 用总项数作分母的话，未评完时各项占比加起来不等于 100%，看着像算错了。
   */
  const levelDistribution = computed<PaLevelDistribution[]>(() => {
    const byScore = new Map<number | null, PaLevelDistribution>()
    let rated = 0
    for (const item of items.value) {
      const lv = levelOf(item, answers[item.itemId])
      if (!lv) {
        continue
      }
      rated += 1
      const existing = byScore.get(lv.score)
      if (existing) {
        existing.count += 1
      } else {
        byScore.set(lv.score, {
          score: lv.score,
          levelName: lv.levelName,
          count: 1,
          percent: 0,
        })
      }
    }
    const list = [...byScore.values()]
    // 分值降序；分值为空的排最后（脏数据不该顶到最上面冒充最高档）
    list.sort((a, b) => {
      if (a.score == null) return 1
      if (b.score == null) return -1
      return b.score - a.score
    })
    for (const row of list) {
      row.percent = rated === 0 ? 0 : Math.round((row.count / rated) * 100)
    }
    return list
  })

  /** 我打的分数区间：最低档与最高档，回执页用一句话概括「整体偏松还是偏紧」 */
  const ratedScoreRange = computed<{ min: number; max: number } | null>(() => {
    const scores = items.value
      .map(item => levelOf(item, answers[item.itemId])?.score)
      .filter((s): s is number => s != null)
    if (!scores.length) {
      return null
    }
    return { min: Math.min(...scores), max: Math.max(...scores) }
  })

  /** 提交时间（本地化展示用；未提交为 null） */
  const submitTimeText = computed(() => {
    const raw = page.value?.submitTime
    if (!raw) {
      return null
    }
    const d = new Date(raw)
    if (Number.isNaN(d.getTime())) {
      return raw
    }
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  })

  const load = async () => {
    try {
      const data = await getPaFillPage(token.value)
      page.value = data
      if (data.status === 'VOIDED' || data.status === 'CANCELLED' || data.status === 'PERIOD_CLOSED') {
        return
      }
      draftVersion.value = data.draftVersion ?? 0
      comment.value = data.comment ?? ''
      for (const [itemId, levelId] of Object.entries(data.draft ?? {})) {
        answers[Number(itemId)] = Number(levelId)
      }
      // 断点续填：落到第一个未评的能力项；全评完则落到评语那一步
      const firstMissing = firstUnansweredIndex.value
      const lastIndex = Math.max(totalElements.value - 1, 0)
      const allRated = firstMissing >= totalElements.value
      if (allRated && hasCommentStep.value && (commentMissing.value || submitted.value)) {
        currentIndex.value = totalElements.value
      } else {
        currentIndex.value = Math.min(firstMissing, lastIndex)
      }
    } catch {
      failed.value = true
    }
  }

  const onSelect = (itemId: number, levelId: number) => {
    if (submitted.value) {
      ui.notify('评价已提交，不可再修改', 'warning')
      return
    }
    answers[itemId] = levelId
    scheduleDraftSave()
  }

  /**
   * 跳题：只有「前面全部已评」的题才允许跳，否则提示先完成第一个未评项。
   * index 允许等于 totalElements（评价意见那一步），受同一条规则约束。
   */
  const jumpTo = (index: number): boolean => {
    if (index > firstUnansweredIndex.value) {
      const blocker = items.value[firstUnansweredIndex.value]
      ui.notify(
        blocker ? `请先完成第 ${firstUnansweredIndex.value + 1} 项「${blocker.itemName}」` : '请按顺序作答',
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
    if (currentUnanswered.value || isLastStep.value) {
      return
    }
    currentIndex.value += 1
  }

  const scheduleDraftSave = () => {
    if (draftTimer) {
      clearTimeout(draftTimer)
    }
    draftTimer = setTimeout(() => {
      void saveDraftNow(true)
    }, 200)
  }

  const dispose = () => {
    if (draftTimer) {
      clearTimeout(draftTimer)
      draftTimer = null
    }
  }

  /** 评语只在需要的身份上发送；自评传 null，服务端也会忽略 */
  const currentComment = (): string | null => {
    const text = comment.value.trim()
    return commentRequired.value && text.length > 0 ? text : null
  }

  const onCommentInput = () => {
    if (submitted.value) {
      return
    }
    scheduleDraftSave()
  }

  const currentRatings = (): PaFillRating[] =>
    Object.entries(answers)
      .filter(([, levelId]) => levelId != null)
      .map(([itemId, levelId]) => ({ itemId: Number(itemId), levelId }))

  const saveDraftNow = async (silent = true) => {
    if (submitted.value) {
      return
    }
    const ratings = currentRatings()
    if (ratings.length === 0 && comment.value.trim().length === 0) {
      return
    }
    draftSaving.value = true
    try {
      const r = await savePaDraft(token.value, ratings, draftVersion.value, currentComment())
      if (r.conflict) {
        draftVersion.value = r.draftVersion
        const latest = r.latestDraft ?? {}
        if (Object.keys(latest).length > 0) {
          ui.notify('该评价已在其他页面修改，已加载最新内容', 'warning')
          for (const key of Object.keys(answers)) {
            delete answers[Number(key)]
          }
          for (const [itemId, levelId] of Object.entries(latest)) {
            answers[Number(itemId)] = Number(levelId)
          }
        }
      } else {
        draftVersion.value = r.draftVersion
        if (!silent) {
          ui.notify('草稿已保存', 'success')
        }
      }
    } catch {
      // 暂存失败不阻断填写：提交时才做完整性校验
    } finally {
      draftSaving.value = false
    }
  }

  const submit = async () => {
    if (missingCount.value > 0) {
      return
    }
    if (commentMissing.value) {
      ui.notify('请先填写评价意见', 'warning')
      return
    }
    if (submitted.value) {
      return
    }
    const ok = await ui.confirmSubmit()
    if (!ok) {
      return
    }
    submitting.value = true
    try {
      const r = await submitPaFill(token.value, currentRatings(), currentComment())
      ui.notify(r.message, 'success')
      // 重新拉一次，拿到最新状态（SUBMITTED）与整体进度
      await load()
    } finally {
      submitting.value = false
    }
  }

  return {
    page,
    failed,
    answers,
    comment,
    draftVersion,
    submitting,
    draftSaving,
    currentIndex,
    items,
    totalElements,
    ratedCount,
    missingCount,
    progressPercent,
    submitted,
    commentRequired,
    commentMissing,
    hasCommentStep,
    totalSteps,
    onCommentStep,
    isLastStep,
    nextIsCommentStep,
    displayIndex,
    currentItem,
    isLast,
    currentUnanswered,
    firstUnansweredIndex,
    groupedItems,
    levelLabel,
    levelOf,
    receiptGroups,
    levelDistribution,
    ratedScoreRange,
    submitTimeText,
    load,
    onSelect,
    onCommentInput,
    jumpTo,
    goPrev,
    goNext,
    saveDraftNow,
    submit,
    dispose,
  }
}
