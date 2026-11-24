<template>
  <el-drawer
    v-model="visible"
    :title="title"
    :size="DRAWER_SIZE"
    destroy-on-close
  >
    <el-skeleton v-if="loading" :rows="10" animated />

    <template v-else-if="detail">
      <!--
        封存条（2026-11-24，sql/64）。

        放在正文最前面而不是塞进 el-descriptions：封存是「这份报告对外可见了吗」的
        开关，管理员进抽屉的第一件事就是确认它 —— 挤在九宫格里的某一格会被跳过。
        按钮只对能看见测评人姓名的身份渲染（后端给的 canSeal），本人视角连按钮的影子都没有。
      -->
      <div class="seal-bar" :class="sealed ? 'seal-bar-on' : 'seal-bar-off'">
        <div class="seal-info">
          <el-tag :type="sealed ? 'success' : 'info'" effect="dark" size="small">
            {{ sealed ? '已封存' : '未封存' }}
          </el-tag>
          <span v-if="sealed" class="seal-text">
            {{ detail.sealByName || '—' }} 于 {{ formatTime(detail.sealTime) }} 封存，内容已冻结
          </span>
          <span v-else class="seal-text">本人与直接上级目前看不到这份报告</span>
        </div>
        <div v-if="detail.canSeal" class="seal-actions">
          <!--
            未封存时按钮的可点条件由后端给（sealReady，2026-11-24 需求变更）：
            所有人都评完才允许点。灰掉比点下去弹错好 —— 后者要等一次往返才知道不行，
            而且会让人以为「是系统出错了」而不是「还有 2 个人没交」。
          -->
          <template v-if="!sealed">
            <span v-if="!detail.sealReady" class="seal-block">{{ sealBlockReason }}</span>
            <el-button
              type="primary"
              :disabled="!detail.sealReady"
              :loading="sealing"
              @click="onSeal"
            >
              确认封存
            </el-button>
          </template>
          <el-button v-else plain type="warning" :loading="sealing" @click="onUnseal">解除封存</el-button>
        </div>
      </div>

      <el-descriptions :column="3" border size="small" class="report-head">
        <el-descriptions-item label="姓名">{{ detail.resourceName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="年度">{{ detail.year }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.status)" size="small">{{ detail.statusName }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="部门">{{ detail.deptName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="岗位">{{ detail.postName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="直接上级">{{ detail.leaderName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="能力项">{{ detail.itemCount }} 项</el-descriptions-item>
        <el-descriptions-item label="360 测评人">{{ detail.rater360Count }} 人</el-descriptions-item>
        <!--
          总得分（2026-11-24）：与明细行、合计行同一套写法「分数 · 等级说明」。
          原来分数在这里、等级说明在下面单独一条横幅，同一个结论写了两处；
          合起来之后横幅撤掉，读者一次读完「多少分 + 算哪一档」。
        -->
        <el-descriptions-item label="总得分">
          <ScoreWithBand :score="detail.totalScore" :band="detail.bandName" />
        </el-descriptions-item>
      </el-descriptions>

      <el-table
        :data="detail.rows"
        border
        stripe
        size="small"
        :span-method="spanMethod"
        :show-summary="true"
        :summary-method="summaryMethod"
        class="report-table"
      >
        <el-table-column prop="categoryName" label="类别" width="110" fixed="left" align="center">
          <template #default="{ row }">
            <span>{{ row.categoryName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="能力项" min-width="180" fixed="left" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-item">
              <span class="cell-name">{{ row.itemName }}</span>
              <el-tooltip v-if="row.definition" :content="row.definition" placement="top">
                <span class="cell-def">定义</span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="权重(%)" width="90" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.weight == null ? '—' : row.weight }}</span>
          </template>
        </el-table-column>
        <el-table-column label="自评" width="100" align="center">
          <template #header>
            <div>自评</div>
            <!-- 自评不进任何公式（口径 #13）：页面上必须写出来，否则「自评 5 分但总分 3.8」会被当成算错 -->
            <div class="col-hint">不参与计算</div>
          </template>
          <template #default="{ row }">
            <el-tooltip
              v-if="row.selfLevelName"
              :content="levelTip(row.selfLevelName, row.selfScore)"
              placement="top"
            >
              <span class="level-chip level-chip-self">
                {{ shortLevel(row.selfLevelName, row.selfScore) }}
              </span>
            </el-tooltip>
            <span v-else class="muted">未评</span>
          </template>
        </el-table-column>
        <!--
          直接上级（2026-11-23）：不再单列「上级评价」，按测评人1 的代号出现。
          编号与评论区同一套 —— 明细说「测评人1 给了 4 分」，评论区的测评人1 必须是他。

          列头不写「直接上级」四个字，也不写「· 陈亿根」（本人视角）：代号的整个意义
          就是看不出是谁评的，挂个角色提示等于把上级身份点出来了。
          姓名只在管理员视角出现，格式与 360 列一致。
        -->
        <el-table-column :label="leaderColumnLabel" width="100" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.leaderLevelName"
              :content="levelTip(row.leaderLevelName, row.leaderScore)"
              placement="top"
            >
              <span class="level-chip">{{ shortLevel(row.leaderLevelName, row.leaderScore) }}</span>
            </el-tooltip>
            <span v-else class="muted">未评</span>
          </template>
        </el-table-column>
        <!-- 360 测评人逐人一列：人数由后端按任务名单给定，列头随数据变 -->
        <el-table-column
          v-for="(rater, idx) in raterColumns"
          :key="rater.key"
          :label="rater.label"
          width="100"
          align="center"
        >
          <template #default="{ row }">
            <el-tooltip
              v-if="row.rater360s[idx]?.levelName"
              :content="levelTip(row.rater360s[idx].levelName, row.rater360s[idx].score)"
              placement="top"
            >
              <span class="level-chip level-chip-360">
                {{ shortLevel(row.rater360s[idx].levelName, row.rater360s[idx].score) }}
              </span>
            </el-tooltip>
            <!--
              未分配 ≠ 未评（2026-11-23 起 360 按项指派）：
              「未评」是要催办的事，「未分配」是这项本来就没让他评 —— 混着写会让 HR 追着人问。
            -->
            <span v-else-if="row.rater360s[idx]?.assigned === false" class="muted muted-na">未分配</span>
            <span v-else class="muted">未评</span>
          </template>
        </el-table-column>
        <el-table-column label="综合得分" width="160" align="right" fixed="right">
          <template #default="{ row }">
            <!--
              「分数 · 档位名」（2026-11-24 需求变更）：描述取岗位分值区间（itemBandName），
              与头部总得分、合计行同一把尺子。原来用的是 itemLevelName（项自己的刻度，
              如「开创/专家」），与总得分的「专家」并排出现会让人以为是两件事。
            -->
            <ScoreWithBand :score="row.itemScore" :band="row.itemBandName" />
          </template>
        </el-table-column>
      </el-table>

      <div class="comment-block">
        <h3 class="comment-title">评价意见</h3>
        <template v-if="detail.comments.length">
          <div v-for="c in detail.comments" :key="c.sourceType + '-' + c.resourceId" class="comment-item">
            <p class="comment-line">
              <!--
                一行读成「谁：说了什么」（2026-11-23 第三版）。
                管理员视角直接写姓名（王建龙：优秀），不给编号 —— 有姓名时再叠「测评人2」
                是重复信息，HR 关心的是人。没姓名的视角（被测评人本人）只有编号。
                姓名来自后端：没有权限时是 null，前端不做二次判断。
              -->
              <span class="comment-who">{{ c.raterName || c.displayName }}：</span>
              <span class="comment-text">{{ c.comment }}</span>
              <span v-if="c.submitTime" class="comment-meta">· {{ formatTime(c.submitTime) }}</span>
            </p>
          </div>
        </template>
        <p v-else class="muted">暂无评价意见</p>
        <p class="comment-note">按评价人分段展示。姓名仅维度管理员可见。</p>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 个人测评报告抽屉（2026-11-23 抽出）。
 *
 * <p>为什么抽成组件：管理员页（PaReportList）与本人页（MyAssessTasks）要看同一份报告，
 * 唯一差别是接口不同（后者带归属校验、恒不返回姓名）。把渲染写两遍的话，
 * 分段评语、测评人编号这些「必须两边一致」的东西早晚各改各的。</p>
 */
import { computed, h, ref } from 'vue'
import type { VNode } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sealReport, unsealReport } from '@/api/paReport'
import type { PaReportDetail } from '@/types/paReport'

const props = defineProps<{
  modelValue: boolean
  loading: boolean
  detail: PaReportDetail | null
}>()

/**
 * changed：封存状态变了，父组件需要重拉这一行（列上那一格 + 抽屉正文）。
 * 抽屉自己不负责重拉 —— 它拿的是 props，写回去只有一个来源才不会打架。
 */
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const title = computed(() =>
  props.detail ? `${props.detail.resourceName ?? ''} · ${props.detail.year} 年度测评报告` : '测评报告',
)

/**
 * 抽屉宽度：直接占满视口。
 *
 * 原来按 `620 + 人数×100` 算、再封顶 1400px，两头都不对：
 * - 只有 1 个测评人时算出来 790px，在 1600 宽屏上刚好占一半，空得慌；
 * - 11 个测评人时列宽合计约 1790px，却被 1400px 卡住，横向拖是必然的
 *   —— 而「谁给了几分」恰恰要横向比着看。
 *
 * size 被 el-drawer 直接当 width 用，所以 96vw 这种视图单位合法，且拖窗口自动重算，
 * 不需要挂 resize 监听。左右各留 2vw 是为了不把抽屉贴死在屏幕边上，圆角和阴影才看得出来。
 */
const DRAWER_SIZE = '96vw'

const sealed = computed(() => props.detail?.sealStatus === 1)
const sealing = ref(false)

/**
 * 「确认封存」为什么点不了（2026-11-24）。
 *
 * 只说「不可用」是不够的：HR 的下一步动作完全取决于原因 ——
 * 差人是「去催办」，没出分是「等评价人交完」。两句话对应两个不同的去处。
 */
const sealBlockReason = computed(() => {
  const d = props.detail
  if (!d) return ''
  const pending = d.pendingRaterCount ?? 0
  if (pending > 0) return `还有 ${pending} 位评价人未提交`
  if (d.status !== 2) return '报告还未算出分数'
  return ''
})

/**
 * 确认封存。
 *
 * 二次确认里必须把「封存＝对外可见 + 内容冻结」两件事都说出来：
 * 只说「确定封存吗」的话，HR 会以为这是个打标记的动作，封完才发现本人立刻能看到了。
 */
const onSeal = async () => {
  const d = props.detail
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `封存后，${d.resourceName || '被测评人'}本人及其直接上级将立即可以看到这份报告，` +
        '并以此份内容为准冻结（后续重算 / 重新导入不再影响它）。确定封存吗？',
      '确认封存',
      { type: 'warning', confirmButtonText: '确认封存', cancelButtonText: '再核一眼' },
    )
  } catch {
    return
  }
  sealing.value = true
  try {
    await sealReport(d.sheetId)
    ElMessage.success('已封存，本人与直接上级现在可以看到报告了')
    emit('changed')
  } catch {
    ElMessage.error('封存失败')
  } finally {
    sealing.value = false
  }
}

/**
 * 解除封存。
 *
 * 后果要说透：解除后本人与直接上级立刻又看不到了 —— 他们可能已经看过了，
 * 这一下对他们来说是「报告被收回」，不是「状态改回去了」。
 */
const onUnseal = async () => {
  const d = props.detail
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `解除后，${d.resourceName || '被测评人'}本人及其直接上级将立刻看不到这份报告，` +
        '封存快照也会一并清除。确定解除封存吗？',
      '解除封存',
      { type: 'warning', confirmButtonText: '解除封存', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  sealing.value = true
  try {
    await unsealReport(d.sheetId)
    ElMessage.success('已解除封存')
    emit('changed')
  } catch {
    ElMessage.error('解除封存失败')
  } finally {
    sealing.value = false
  }
}

const statusTagType = (status: number): 'info' | 'warning' | 'success' => {
  if (status === 0) return 'info'
  return status === 1 ? 'warning' : 'success'
}

/**
 * 等级芯片上只显示分值（2026-11-23）。
 *
 * 等级名是「3分 · 精进/能手」这种长串，逐人展开后一屏能塞 5~6 个这样的人，
 * 列宽 100px 会被挤成两行。页面要的是「谁给了几分」的对比，档位名称在
 * 模型页看得到，这里取分值就够。完整等级名放 tooltip，需要时 hover 可见。
 *
 * 分值优先取评级快照里的 score（等级改名/改分后历史数据仍准）；
 * score 缺失时才从等级名开头剥数字，最后回退成整串名字。
 */
const shortLevel = (levelName: string | null, score: number | null): string => {
  if (score != null) {
    // 去掉小数尾零：3.00 → 3，3.50 → 3.5
    return `${Number(score)}分`
  }
  const m = levelName?.match(/^\s*(\d+(?:\.\d+)?)/)
  return m ? `${m[1]}分` : (levelName ?? '—')
}

/** 等级芯片的 tooltip 内容：分值 + 完整等级名 */
const levelTip = (levelName: string | null, score: number | null): string => {
  const short = shortLevel(levelName, score)
  return levelName && levelName !== short ? `${short} · ${levelName}` : short
}

/**
 * 360 列头。
 *
 * 列数由「该页最近一次任务的 360 名单」决定，取第一行的 rater360s —— 后端对每一行
 * 都按同一份名单铺格，所以任取一行长度一致。
 *
 * 编号从 2 起（2026-11-23）：上级占掉「测评人1」，这样评论区的「测评人2」和
 * 这里的第 1 个 360 列是同一个人。
 *
 * 姓名可见性由后端控制：无权限时 raterName 为 null，这里就只剩「测评人N」。
 * 前端不做二次判断 —— 接口没给，藏不藏都是没给。
 */
const raterColumns = computed(() => {
  const rows = props.detail?.rows ?? []
  const entries = rows[0]?.rater360s ?? []
  return entries.map((rater, idx) => ({
    key: rater.resourceId ?? idx,
    label: rater.raterName ? `测评人${idx + 2} · ${rater.raterName}` : `测评人${idx + 2}`,
  }))
})

/**
 * 测评人1 的列头：跟 360 列同一套写法 —— 管理员带姓名，其他人只有代号。
 *
 * 姓名不能拿 `leaderName` 是否为 null 判断：报告头部「直接上级」对本人也要显示，
 * 那个字段恒有值。用后端给的 `showRaterName` 显式开关。
 */
const leaderColumnLabel = computed(() =>
  props.detail?.showRaterName && props.detail?.leaderName
    ? `测评人1 · ${props.detail.leaderName}`
    : '测评人1',
)

/** 类别列按连续同类合并，避免同一类别重复出现 5 次 */
const spanMethod = ({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }) => {
  if (columnIndex !== 0) return { rowspan: 1, colspan: 1 }
  const rows = props.detail?.rows ?? []
  if (rowIndex > 0 && rows[rowIndex - 1]?.categoryName === rows[rowIndex]?.categoryName) {
    return { rowspan: 0, colspan: 0 }
  }
  let span = 1
  for (let i = rowIndex + 1; i < rows.length && rows[i].categoryName === rows[rowIndex].categoryName; i++) {
    span++
  }
  return { rowspan: span, colspan: 1 }
}

/**
 * 合计行：按列给的是各主体的加权平均，不是把明细行加起来 ——
 * 明细是「每项的得分」，合计是「按权重加权平均后的总分」，两者相加没有意义。
 *
 * 列序（2026-11-23 起）：0 类别 1 能力项 2 权重 3 自评 4 上级(测评人1) 5..(5+N-1) 各 360，末列 综合得分。
 * 360 列数随任务名单变，所以不能再按下标写死供应值 —— 用末列 + 固定头部定位。
 * 360 单元格的合计不逐人给（一个人一项没评，他的合计是「按他评过的项加权平均」，
 * 与「他这一项得几分」不是一回事，塞进合计行会让人误读成「他的总评」），
 * 那一格留给汇总的平均分。
 *
 * <p><b>末列给的是 VNode 而不是字符串</b>（2026-11-24 需求）：合计行的综合得分要和明细行
 * 长得一样 —— 「5.00 · 专家」，数字正常色、描述淡色小一号。字符串合计只有一个文本节点，
 * 套不出这种结构；el-table 的 footer 把 {@code sums[i]} 原样塞进单元格，给 VNode 就能渲染。</p>
 *
 * <p>三处「描述」用的都是<b>岗位分值区间的等级名</b>（新手/熟手/能手/高手/专家）：
 * 头部总得分、合计行、明细行（{@code itemBandName}）。明细行另有 {@code itemLevelName}
 * （能力项自己的等级名，形如「精进/能手」），那是<b>另一把尺子</b>，2026-11-24 需求变更后
 * 页面不再显示它 —— 统一成「分数 · 岗位档位」这一个写法，读的人不用再分辨两个名字谁是谁。</p>
 */
const summaryMethod = ({ columns }: { columns: unknown[] }): (string | VNode)[] => {
  const sums: (string | VNode)[] = columns.map(() => '')
  const d = props.detail
  if (!d) return sums
  sums[0] = '合计'
  const weightTotal = d.rows.reduce((acc, r) => acc + (r.weight ?? 0), 0)
  sums[2] = String(Number(weightTotal.toFixed(2)))
  // 3 自评 / 4 测评人1(上级) 是固定位置（在 360 列之前）
  sums[3] = d.selfScore == null ? '—' : d.selfScore.toFixed(2)
  sums[4] = d.leaderScore == null ? '—' : d.leaderScore.toFixed(2)
  // 综合得分永远在末列：与明细行同一套写法（分数 + 点 + 描述）
  sums[columns.length - 1] = h(ScoreWithBand, { score: d.totalScore, band: d.bandName })
  return sums
}

/**
 * 「分数 · 描述」的统一写法（2026-11-24）。
 *
 * <p>明细行的项得分、合计行的总得分、抽屉头部的总得分，三处必须是同一个样子 ——
 * 否则同一个数字在页面上有两个长相，读的人会怀疑是不是两回事。</p>
 *
 * <p>做成<b>函数式组件</b>而不是返回 VNode 的工具函数：模板里要用 {@code <component :is>} 的话，
 * 传字符串会被当成组件名去解析（「—」会报「Failed to resolve component」）。
 * 函数式组件既能写进模板，也能在合计行里用 {@code h(ScoreWithBand, ...)} 生成单元格内容。</p>
 *
 * <p>没出分显示「—」；有分没描述就只给分数（存量岗位可能没配分值区间）。</p>
 */
const ScoreWithBand = (props: { score: number | null; band?: string | null }): VNode => {
  if (props.score == null) {
    return h('span', { class: 'sc-wrap' }, [h('span', { class: 'muted' }, '—')])
  }
  const children: VNode[] = [h('span', { class: 'num sc-value' }, props.score.toFixed(2))]
  if (props.band) {
    children.push(h('span', { class: 'sc-band' }, props.band))
  }
  return h('span', { class: 'sc-wrap' }, children)
}

/** 提交时间只到分钟：秒对「谁说的」没有意义，反而让每行更长 */
const formatTime = (iso: string | null): string => {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<style scoped>
.seal-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 12px;
  border-radius: 4px;
  border: 1px solid;
  font-size: 13px;
}
.seal-bar-on {
  background: #f0f9eb;
  border-color: #c2e7b0;
}
.seal-bar-off {
  background: #f4f4f5;
  border-color: #e9e9eb;
}
.seal-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.seal-text {
  color: #606266;
}
.seal-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.seal-block {
  color: #e6a23c;
  font-size: 12px;
}
.muted {
  color: #aaa;
}
/* 「未分配」比「未评」更淡：一个不用管，一个要催 */
.muted-na {
  color: #c8c9cc;
  font-size: 12px;
}
.num {
  font-variant-numeric: tabular-nums;
}

.report-head {
  margin-bottom: 14px;
}
.cell-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cell-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-def {
  font-size: 12px;
  color: #409eff;
  border-bottom: 1px dashed #a0cfff;
  cursor: help;
}
.level-chip {
  display: inline-block;
  padding: 0 8px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 22px;
  background: #eef6ff;
  color: #1d4f91;
}
/* 自评用灰底：视觉上就与「计入总分」的几列区分开 */
.level-chip-self {
  background: #f4f4f5;
  color: #606266;
}
/* 360 用浅绿：一眼能看出「这一列是外部视角」，与上级区分 */
.level-chip-360 {
  background: #eefaf3;
  color: #1c6b47;
}
.col-hint {
  font-size: 11px;
  color: #c0c4cc;
  font-weight: 400;
}
.comment-block {
  margin-top: 18px;
  padding: 12px 14px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}
.comment-title {
  margin: 0 0 10px;
  font-size: 15px;
}
/* 一段一条：左边一条竖线把「谁说的」和「说了什么」包在一起，比空行分隔清楚。
   多人评价时多条连排，只靠 8px 间距看着像一整段 —— 间距加到 14px 并补一条虚线，
   让「换人了」一眼可见。 */
.comment-item {
  padding: 8px 0 14px 12px;
  border-left: 3px solid #e4e7ed;
  margin-bottom: 14px;
}
.comment-item:not(:last-of-type) {
  border-bottom: 1px dashed #ebeef5;
}
.comment-item:last-of-type {
  margin-bottom: 0;
}
.comment-who {
  font-size: 13px;
  font-weight: 600;
  color: #1d4f91;
}
.comment-line {
  margin: 0;
  line-height: 1.7;
}
.comment-text {
  white-space: pre-wrap;
  color: #303133;
}
.comment-meta {
  font-size: 12px;
  color: #c0c4cc;
  margin-left: 6px;
}
.comment-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #999;
}
</style>

<style>
/*
 * 「分数 · 描述」这一组样式**不能写在 scoped 块里**（2026-11-24）。
 *
 * ScoreWithBand 是函数式组件，内部节点由 h() 现造，不带 data-v-xxx —— scoped 规则
 * 匹配不到它们，表现为「·」分隔符不出现、描述是黑字同字号，数字和描述糊成一串。
 * 类名已加 sc- 前缀避免漏到全局，所以放在非 scoped 块里是安全的。
 */
.sc-wrap {
  white-space: nowrap;
}
.sc-value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
/* 描述跟在分值后面：同色但更淡更小，读起来是「3.67 · 精进/能手」一个词组，不是两列数据 */
.sc-band {
  margin-left: 4px;
  font-size: 12px;
  color: #909399;
}
.sc-band::before {
  content: '·';
  margin-right: 4px;
}
.pa-report-drawer-table .el-table__footer-wrapper .el-table__cell {
  font-weight: 600;
  background: #fafafa;
}
</style>
