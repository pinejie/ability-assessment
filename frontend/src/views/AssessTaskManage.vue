<template>
  <div class="task-manage-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">测评任务发起</h1>
        <p class="page-description">
          查看本期测评任务的进度，可催办未提交者；发起有误可撤回后重新发起。
          撤回会删除已提交的评价并通知当事人，不可撤销。
        </p>
      </div>
      <el-button type="primary" @click="router.push('/assess-tasks')">发起测评</el-button>
    </div>

    <el-card class="filter-card">
      <!--
        条件变更即查（2026-11-14）：下拉离散选择，改一次查一次；文字输入用防抖，
        停止输入 400ms 后才发请求，避免逐字打就逐字查。
        回车仍然立即查，手快的人不用等防抖。
      -->
      <el-form :inline="true" :model="query">
        <el-form-item label="年度">
          <el-select v-model="query.year" clearable placeholder="全部" style="width: 140px"
                     @change="reload">
            <el-option v-for="p in periods" :key="p.id" :label="`${p.year} 年度`" :value="p.year" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="有效任务" style="width: 160px"
                     @change="reload">
            <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="被测人">
          <el-input v-model="query.keyword" clearable placeholder="姓名或工号" style="width: 180px"
                    @input="onKeywordInput" @keyup.enter="reloadNow" @clear="reloadNow" />
        </el-form-item>
        <el-form-item>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-loading="loading">
      <el-table :data="items" border @row-click="openDetail">
        <el-table-column prop="year" label="年度" width="80" />
        <el-table-column prop="targetName" label="被测人" width="110" />
        <el-table-column prop="deptName" label="部门" width="150" show-overflow-tooltip />
        <el-table-column prop="leaderName" label="直接上级" width="100" />
        <el-table-column label="进度" width="150">
          <template #default="{ row }">
            <span class="progress-text">{{ row.submittedCount }} / {{ row.raterTotal }}</span>
            <el-progress
              :percentage="row.raterTotal ? Math.round((row.submittedCount / row.raterTotal) * 100) : 0"
              :stroke-width="6"
              :show-text="false"
              class="progress-bar"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="110">
          <template #default="{ row }">
            <span v-if="row.actualScore != null">{{ row.actualScore.toFixed(2) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="gradeName" label="等级" width="90">
          <template #default="{ row }">{{ row.gradeName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="发起时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 0"
              link
              type="primary"
              @click.stop="doRemind(row)"
            >催办</el-button>
            <el-button
              v-if="canRetry(row)"
              link
              type="primary"
              @click.stop="doRetry(row)"
            >重算</el-button>
            <el-button
              v-if="row.status === 0 || row.status === 2"
              link
              type="danger"
              @click.stop="openVoid(row)"
            >撤回</el-button>
            <el-button
              v-if="row.status === 3 || row.status === 4"
              link
              type="danger"
              @click.stop="doDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !items.length" description="暂无测评任务" />

      <div v-if="total > 0" class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </el-card>

    <!-- 撤回对话框：原因会外发给已提交的评价人（§8.6.3） -->
    <el-dialog v-model="voidDialog.visible" title="撤回测评" width="560px" :close-on-click-modal="false">
      <div v-if="voidDialog.task" class="void-body">
        <el-alert type="warning" :closable="false" show-icon class="void-alert">
          <template #title>撤回不可撤销</template>
          <div class="alert-body">
            <p>本次撤回将：</p>
            <ul>
              <li>作废 <b>{{ voidDialog.task.raterTotal }}</b> 位评价人中已提交的评价</li>
              <li>被测人 <b>{{ voidDialog.task.targetName }}</b> 的得分退回「未评价」状态</li>
              <li>该测评页上已写的评价意见（上级 / 客户评语）一并删除</li>
              <li>旧评价链接立即失效，已提交的评价人会收到通知</li>
            </ul>
          </div>
        </el-alert>

        <el-form label-position="top">
          <el-form-item label="撤回原因（必填，5–200 字）" required>
            <el-input
              v-model="voidDialog.reason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="例如：客户评价人漏选财务部王强，需重新发起"
            />
          </el-form-item>
        </el-form>
        <p class="void-warn">
          ⚠ 该原因将随通知消息发送给已提交评价的
          <b>{{ voidDialog.task.submittedCount }}</b> 人，请使用客观、中性的表述。
        </p>

        <el-checkbox v-if="voidDialog.task.status === 2" v-model="voidDialog.force">
          该任务已完成并已算出分数，我确认要撤回
        </el-checkbox>
      </div>
      <template #footer>
        <el-button @click="voidDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="voidDialog.submitting" @click="submitVoid">确认撤回</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detail.visible" title="任务详情" size="640px">
      <div v-if="detail.data" class="detail-body">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="被测人">{{ detail.data.task.targetName }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ detail.data.task.deptName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="年度">{{ detail.data.task.year }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.data.task.status)" size="small">
              {{ detail.data.task.statusName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="有效要素">{{ detail.data.task.ratingCount }}</el-descriptions-item>
          <el-descriptions-item label="客户评价人">{{ detail.data.task.clientCount }}</el-descriptions-item>
          <el-descriptions-item label="得分">
            {{ detail.data.task.actualScore != null ? detail.data.task.actualScore.toFixed(2) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="等级">{{ detail.data.task.gradeName || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.data.task.voidReason" label="撤回原因" :span="2">
            {{ detail.data.task.voidReason }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="detail-title">评价人进度</h3>
        <el-table :data="detail.data.raters" border size="small">
          <el-table-column prop="raterTypeName" label="角色" width="100" />
          <el-table-column prop="raterName" label="评价人" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : row.status === 3 ? 'info' : 'primary'" size="small">
                {{ row.statusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="submitTime" label="提交时间" width="170">
            <template #default="{ row }">{{ row.submitTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="催办" width="90">
            <template #default="{ row }">{{ row.remindCount || 0 }} 次</template>
          </el-table-column>
        </el-table>

        <div v-if="detail.data.task.status === 4" class="detail-actions">
          <el-button size="small" @click="doResendNotice">补发撤回通知</el-button>
          <span class="muted">撤回时通知失败的，可在此补发；已发过的会跳过。</span>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteAssessTask,
  getAssessTaskDetail,
  listAssessTasks,
  remindAssessTask,
  resendVoidNotice,
  retryAssessTask,
  voidAssessTask,
} from '@/api/assessTask'
import { listPeriods } from '@/api/assessPeriod'
import type { AssessTaskDetail, AssessTaskItem } from '@/types/assessTask'
import type { PeriodItem } from '@/types/assessSheet'

const STATUS_OPTIONS = [
  { value: 0, label: '进行中' },
  { value: 2, label: '已完成' },
  // 「已取消」自 2026-11-14 起不再产生（取消功能已移除），保留此项只为查看存量数据
  { value: 3, label: '已取消' },
  { value: 4, label: '已撤回' },
]

const router = useRouter()
const periods = ref<PeriodItem[]>([])
const items = ref<AssessTaskItem[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  page: 1,
  size: 20,
  year: null as number | null,
  status: null as number | null,
  keyword: '',
})

const voidDialog = reactive({
  visible: false,
  submitting: false,
  reason: '',
  force: false,
  task: null as AssessTaskItem | null,
})

const detail = reactive({
  visible: false,
  data: null as AssessTaskDetail | null,
})

const statusTagType = (status: number): 'primary' | 'success' | 'warning' | 'info' | 'danger' => {
  switch (status) {
    case 0: return 'primary'
    case 2: return 'success'
    case 3: return 'info'
    case 4: return 'danger'
    default: return 'info'
  }
}

const load = async () => {
  loading.value = true
  try {
    const page = await listAssessTasks({
      page: query.page,
      size: query.size,
      year: query.year,
      status: query.status,
      keyword: query.keyword || null,
    })
    items.value = page.items
    total.value = page.total
  } catch {
    ElMessage.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

/**
 * 筛选一律「改完就查」（2026-11-14），不再需要查询按钮。
 * 下拉是离散选择，一个动作只产生一次变化，直接查；
 * 文字输入要防抖，否则每敲一个字就发一次请求。
 */
const KEYWORD_DEBOUNCE_MS = 400
let keywordTimer: ReturnType<typeof setTimeout> | null = null

/** 输入停顿后才查；清空按钮走 @clear 立即查，不用等 400ms */
const onKeywordInput = () => {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
  }
  keywordTimer = setTimeout(() => {
    keywordTimer = null
    reload()
  }, KEYWORD_DEBOUNCE_MS)
}

/** 回车：取消待触发的防抖，马上查，免得连发两次 */
const reloadNow = () => {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
    keywordTimer = null
  }
  reload()
}

const reload = () => {
  query.page = 1
  load()
}

/** 重置：条件先清完再查一次，不能让每个 @change 各发一次请求 */
const reset = () => {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
    keywordTimer = null
  }
  query.year = null
  query.status = null
  query.keyword = ''
  reload()
}

const openDetail = async (row: AssessTaskItem) => {
  try {
    detail.data = await getAssessTaskDetail(row.taskId)
    detail.visible = true
  } catch {
    ElMessage.error('加载任务详情失败')
  }
}

/** 重算的显示条件：必须全员提交（未提交前算分会得到不完整的分数） */
const canRetry = (row: AssessTaskItem): boolean => {
  if (row.status !== 0 && row.status !== 2) return false
  return row.raterTotal > 0 && row.submittedCount >= row.raterTotal
}

const doRemind = async (row: AssessTaskItem) => {
  const pending = Math.max(0, (row.raterTotal ?? 0) - (row.submittedCount ?? 0))
  if (pending === 0) {
    ElMessage.info('所有评价人均已提交，无需催办')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将向 ${pending} 位未提交的评价人发送催办提醒。\n\n` +
      '· 消息通过泛微 OA 发送，通常 1 分钟内送达\n' +
      '· 已提交的评价人不会收到\n' +
      '· 可重复催办，评价人不会因此丢失已填内容',
      '确认催办',
      { type: 'info', confirmButtonText: '确认催办', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  try {
    const r = await remindAssessTask(row.taskId)
    ElMessage.success(r.message)
    load()
  } catch {
    // 错误提示由请求拦截器统一处理
  }
}

const doRetry = async (row: AssessTaskItem) => {
  // 进行中的任务点重算会自动结项，代价比已完成的大，提示语要分开
  const hint = row.status === 0
    ? '\n\n⚠️ 该任务当前为「进行中」，重算后会自动标记为「已完成」，\n   评价人将无法再提交。'
    : ''
  try {
    await ElMessageBox.confirm(
      `将按当前评级重新计算 ${row.targetName ?? '该人员'} 的得分，覆盖现有分数。${hint}`,
      '确认重新计算',
      { type: 'warning', confirmButtonText: '确认重算', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  try {
    const r = await retryAssessTask(row.taskId)
    ElMessage.success(r.actualScore != null ? `已重新计算，得分 ${r.actualScore.toFixed(2)}` : '已重新计算')
    load()
  } catch {
    // 统一提示
  }
}

/**
 * 删除已作废的任务。
 * 只删任务与评价人（评价链接随之失效），该人员的测评页保留 —— 那张「人+年」的页面
 * 可能还被别的任务引用，删不得。弹窗里把这点讲明白，免得用户以为数据全清了。
 */
const doDelete = async (row: AssessTaskItem) => {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${row.targetName ?? '该人员'} ${row.year} 年度这条「${row.statusName}」任务吗？\n\n` +
      '· 任务记录与评价链接将被删除，无法恢复\n' +
      '· 该人员的测评页会保留，可在「测评数据汇总」中查看',
      '确认删除任务',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  try {
    await deleteAssessTask(row.taskId)
    ElMessage.success('任务已删除，测评页已保留')
    load()
  } catch {
    // 统一提示
  }
}

const openVoid = (row: AssessTaskItem) => {
  voidDialog.task = row
  voidDialog.reason = ''
  voidDialog.force = false
  voidDialog.visible = true
}

const submitVoid = async () => {
  if (!voidDialog.task) {
    return
  }
  const reason = voidDialog.reason.trim()
  if (reason.length < 5 || reason.length > 200) {
    ElMessage.warning('撤回原因需 5–200 字')
    return
  }
  if (voidDialog.task.status === 2 && !voidDialog.force) {
    ElMessage.warning('该任务已完成，请勾选确认后再撤回')
    return
  }
  voidDialog.submitting = true
  try {
    const r = await voidAssessTask(voidDialog.task.taskId, { reason, force: voidDialog.force })
    voidDialog.visible = false
    if (r.noticeWritten) {
      ElMessage.success(`已撤回，${r.notifiedRaterCount} 位评价人已收到通知`)
    } else {
      // 通知失败必须让操作人知道，不能静默（§8.6.4）
      ElMessage.warning(r.noticeError || '撤回成功，但通知发送失败，请在详情页补发')
    }
    load()
  } catch {
    // 统一提示
  } finally {
    voidDialog.submitting = false
  }
}

const doResendNotice = async () => {
  if (!detail.data) {
    return
  }
  try {
    const r = await resendVoidNotice(detail.data.task.taskId)
    if (r.noticeWritten) {
      ElMessage.success(`补发完成，${r.notifiedRaterCount} 人已通知`)
    } else {
      ElMessage.warning(r.noticeError || '补发未写入新消息（可能此前已发过）')
    }
  } catch {
    // 统一提示
  }
}

onMounted(async () => {
  try {
    periods.value = await listPeriods()
  } catch {
    periods.value = []
  }
  load()
})

/** 切走页面时清掉挂起的防抖，否则 400ms 后还会对着已卸载的页面发一次请求 */
onUnmounted(() => {
  if (keywordTimer) {
    clearTimeout(keywordTimer)
    keywordTimer = null
  }
})
</script>

<style scoped>
.task-manage-page {
  padding: 0;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.filter-card {
  margin-bottom: 16px;
}

.progress-text {
  font-size: 13px;
  color: #4e5969;
}

.progress-bar {
  margin-top: 4px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.muted {
  color: #86909c;
  font-size: 13px;
}

.void-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.void-alert {
  align-items: flex-start;
}

.alert-body ul {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 13px;
}

.void-warn {
  margin: 0;
  font-size: 13px;
  color: #d25f00;
}

.detail-title {
  margin: 20px 0 12px;
  font-size: 15px;
}

.detail-actions {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
