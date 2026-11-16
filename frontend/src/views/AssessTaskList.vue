<template>
  <div class="assess-task-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">发起测评</h1>
        <p class="page-description">
          选择年度与被测人，直接上级按 OA 组织关系自动带出并锁定，再指定客户评价人，
          系统会生成自评、直接上级、客户三条独立链接，并通过泛微消息推送给各评价人。
          评价人名单发起后不可追加或替换；名单选错只能撤回后重新发起。
        </p>
      </div>
      <!-- 本页不占菜单，靠「测评任务」页的按钮进来，回程也得给一个，否则只能浏览器后退 -->
      <el-button @click="router.push('/assess-task-manage')">返回任务列表</el-button>
    </div>

    <el-card class="form-card">
      <el-form :model="form" label-width="120px" :disabled="creating">
        <el-form-item label="年度" required>
          <el-select v-model="form.year" placeholder="选择年度" style="width: 220px" @change="onYearChange">
            <el-option v-for="p in openPeriods" :key="p.id" :label="`${p.year} 年度`" :value="p.year" />
          </el-select>
          <span v-if="!openPeriods.length" class="form-hint">当前维度没有进行中的年度，请先在「年度设置」中新增</span>
        </el-form-item>

        <el-form-item label="被测人" required>
          <el-select
            v-model="form.resourceId"
            filterable
            remote
            reserve-keyword
            :remote-method="searchResources"
            :loading="resourceLoading"
            :disabled="!form.year"
            :placeholder="form.year ? '输入姓名搜索（仅列出该年度已配置能力要求的人员）' : '请先选择年度'"
            no-data-text="该年度下没有匹配的人员，请先在「人员能力要求配置」中配置"
            style="width: 320px"
            @change="onTargetChange"
          >
            <el-option
              v-for="r in resourceOptions"
              :key="r.id"
              :label="r.departmentName ? `${r.lastname}（${r.departmentName}）` : r.lastname"
              :value="r.id"
            />
          </el-select>
        </el-form-item>

        <!--
          不加 required（2026-11-14）：星号的意思是「请你填」，而这一栏是系统按 OA 组织关系带出的，
          用户填不了。它确实卡流程（领导权重 75%，缺了算不出分），但这个信息由下面的橙色提示
          和按钮侧的「没有直接上级无法发起」来传达，红色星号给不了。
        -->
        <el-form-item label="直接上级">
          <!--
            上级只做展示，不渲染成输入框（2026-11-14）：
            readonly 的 input 仍能聚焦并出现文本光标，看起来像坏了；而语义上它确实不可改 ——
            取值唯一来源是 HrmResource.managerid，后端 create 也会忽略前端传上来的 leader。
          -->
          <div class="leader-readonly">
            <span v-if="leaderDisplay" class="leader-name">{{ leaderDisplay }}</span>
            <span v-else class="leader-empty">{{ leaderLoading ? '正在读取…' : '选择被测人后自动带出' }}</span>
          </div>
          <span class="form-hint">取自泛微组织关系，不可修改</span>
          <span v-if="leader && !leader.leaderResourceId" class="form-hint form-hint-warn">
            请先在泛微补齐该人员的直接上级
          </span>
        </el-form-item>

        <el-form-item label="客户评价人">
          <el-select
            v-model="form.clientResourceIds"
            multiple
            filterable
            remote
            reserve-keyword
            :remote-method="searchClients"
            :loading="clientLoading"
            placeholder="该人员有客户评价要素时必须选择"
            style="width: 480px"
          >
            <el-option v-for="r in clientOptions" :key="r.id" :label="r.lastname" :value="r.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submit">
            发起测评
          </el-button>
          <span v-if="leader && !leader.leaderResourceId" class="form-hint form-hint-warn">
            没有直接上级无法发起
          </span>
        </el-form-item>
      </el-form>

      <!-- 人员预检：把「登不进系统」的人一次性列清楚，避免改一个报一个 -->
      <el-alert
        v-if="precheck && !precheck.ok"
        class="precheck-alert"
        type="error"
        :closable="false"
        show-icon
        title="以下人员无法登录系统，不能参与本次测评"
      >
        <el-table :data="precheck.problems" size="small" border class="precheck-table">
          <el-table-column prop="name" label="姓名" width="120" />
          <el-table-column prop="role" label="角色" width="120" />
          <el-table-column prop="message" label="原因" />
        </el-table>
        <p class="precheck-hint">
          评价链接按「登录人必须是评价人本人」校验，登不进系统的人拿到链接也提交不了。
          请先在泛微中补齐 OA 登录名或调整人员状态，改完后重新预检。
        </p>
      </el-alert>
    </el-card>

    <!-- 发起结果：每人一条专属链接（S1 手工验证用，S2 起改由消息推送） -->
    <el-card v-if="result" class="result-card">
      <template #header>
        <div class="result-head">
          <span>任务已发起（任务号 {{ result.taskId }}，有效要素 {{ result.ratingCount }} 项，客户 {{ result.clientCount }} 位）</span>
          <el-button link type="primary" @click="copyAll">复制全部链接</el-button>
        </div>
      </template>
      <el-table :data="result.raters" border>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">{{ RATER_TYPE_NAMES[row.raterType] || row.raterType }}</template>
        </el-table-column>
        <el-table-column prop="raterName" label="评价人" width="140" />
        <el-table-column label="专属链接">
          <template #default="{ row }">
            <el-input :model-value="fillUrl(row.accessToken)" readonly>
              <template #append>
                <el-button @click="copyOne(row.accessToken)">复制</el-button>
              </template>
            </el-input>
          </template>
        </el-table-column>
      </el-table>
      <p class="result-hint">
        链接只对该评价人登录账号有效（服务端校验登录人 == 评价人），转发给别人也提交不了。
        评价人也会收到泛微消息提醒，链接丢失可在此重新复制。
      </p>
      <div class="result-actions">
        <el-button type="primary" @click="router.push('/assess-task-manage')">查看任务进度</el-button>
        <el-button @click="startAnother">再发起一个</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createAssessTask, getAssessLeader, precheckAssessTask } from '@/api/assessTask'
import { listPeriods } from '@/api/assessPeriod'
import request from '@/utils/request'
import type { AssessLeaderInfo, AssessTaskCreateResult, AssessTaskPrecheck } from '@/types/assessTask'
import type { PeriodItem } from '@/types/assessSheet'

interface ResourceOption {
  id: number
  lastname: string
  departmentName?: string
}

const RATER_TYPE_NAMES: Record<string, string> = { SELF: '自评', LEADER: '直接上级', CLIENT: '客户' }

const router = useRouter()

const periods = ref<PeriodItem[]>([])
const form = reactive({
  year: null as number | null,
  resourceId: null as number | null,
  clientResourceIds: [] as number[],
  remark: '',
})

const resourceOptions = ref<ResourceOption[]>([])
const clientOptions = ref<ResourceOption[]>([])
const resourceLoading = ref(false)
const clientLoading = ref(false)

/** 直接上级：只读展示，取值来自被测人档案（OA 组织关系） */
const leader = ref<AssessLeaderInfo | null>(null)
const leaderLoading = ref(false)

const creating = ref(false)
const precheck = ref<AssessTaskPrecheck | null>(null)
const result = ref<AssessTaskCreateResult | null>(null)

const openPeriods = computed(() => periods.value.filter(p => p.status === 0))

const leaderDisplay = computed(() => {
  const info = leader.value
  if (!info || !info.leaderResourceId) {
    return ''
  }
  return info.deptName ? `${info.leaderName}（${info.deptName}）` : info.leaderName || ''
})

/** 上级未带出时不允许发起：后端同样会拒，这里提前拦住，省一轮报错 */
const canSubmit = computed(() => !!form.year && !!form.resourceId && !!leader.value?.leaderResourceId)

const loadPeriods = async () => {
  periods.value = await listPeriods()
  if (form.year == null && openPeriods.value.length > 0) {
    form.year = openPeriods.value[0].year
  }
}

const clearLeader = () => {
  leader.value = null
}

const onYearChange = () => {
  form.resourceId = null
  resourceOptions.value = []
  clearLeader()
}

const onTargetChange = async (resourceId: number | null) => {
  clearLeader()
  if (!resourceId) {
    return
  }
  leaderLoading.value = true
  try {
    leader.value = await getAssessLeader(resourceId)
  } catch {
    clearLeader()
  } finally {
    leaderLoading.value = false
  }
}

/**
 * 人员搜索：复用 /search/resources（支持姓名与拼音，已带部门名）。
 * 被测人必须限定「所选年度 + 当前维度已配置人员能力要求」的人 —— 没有要求的人发不起来，
 * 让他出现在下拉里只会让 HR 白选一次再吃报错。客户评价人不受此限，仍可搜全量在岗人员。
 */
const searchPeople = async (
  keyword: string,
  target: typeof resourceOptions,
  loading: typeof resourceLoading,
  requireYear?: number | null
) => {
  if (!keyword || !keyword.trim()) {
    target.value = []
    return
  }
  loading.value = true
  try {
    const params: Record<string, string | number> = {
      keyword: keyword.trim(),
      pageNum: 1,
      pageSize: 30,
    }
    if (requireYear) {
      params.requireYear = requireYear
    }
    const page = await request.get<{
      list: { id: number; lastname: string; departmentName?: string }[]
    }>('/search/resources', { params })
    target.value = page.list.map(r => ({
      id: r.id,
      lastname: r.lastname,
      departmentName: r.departmentName || undefined,
    }))
  } catch {
    target.value = []
  } finally {
    loading.value = false
  }
}

const searchResources = (kw: string) => searchPeople(kw, resourceOptions, resourceLoading, form.year)
const searchClients = (kw: string) => searchPeople(kw, clientOptions, clientLoading)

const fillUrl = (token: string) => `${window.location.origin}/assess/fill/${token}`

const copyOne = async (token: string) => {
  try {
    await navigator.clipboard.writeText(fillUrl(token))
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选中链接复制')
  }
}

const copyAll = async () => {
  if (!result.value) {
    return
  }
  const text = result.value.raters
    .map(r => `${RATER_TYPE_NAMES[r.raterType] || r.raterType} ${r.raterName || ''}：${fillUrl(r.accessToken)}`)
    .join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('全部链接已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请逐条复制')
  }
}

const submit = async () => {
  if (!form.year) {
    ElMessage.warning('请选择年度')
    return
  }
  if (!form.resourceId) {
    ElMessage.warning('请选择被测人')
    return
  }
  const leaderId = leader.value?.leaderResourceId
  if (!leaderId) {
    ElMessage.warning(leader.value?.message || '该人员没有直接上级，无法发起')
    return
  }
  creating.value = true
  try {
    // 先预检：一次列出全部登不进系统的人。有问题就停下来让 HR 改，
    // 不把「注定发死链」的任务发出去。
    precheck.value = await precheckAssessTask({
      year: form.year,
      resourceId: form.resourceId,
      leaderResourceId: leaderId,
      clientResourceIds: form.clientResourceIds,
    })
    if (!precheck.value.ok) {
      ElMessage.error('有人员无法登录系统，请按下方清单处理后重试')
      return
    }

    result.value = await createAssessTask({
      year: form.year,
      resourceId: form.resourceId,
      leaderResourceId: leaderId,
      clientResourceIds: form.clientResourceIds,
      remark: form.remark || null,
    })
    ElMessage.success('任务已发起')
  } finally {
    creating.value = false
  }
}

/** 再发起一个：只清掉本次发起的结果，年度保留（同一批人通常在同一年度里连续发起） */
const startAnother = () => {
  result.value = null
  precheck.value = null
  form.resourceId = null
  form.clientResourceIds = []
  form.remark = ''
  resourceOptions.value = []
  clientOptions.value = []
  clearLeader()
}

onMounted(loadPeriods)
</script>

<style scoped>
.assess-task-page {
  padding: 0;
}

.form-card {
  margin-bottom: 16px;
}

.form-hint {
  margin-left: 12px;
  font-size: 13px;
  color: #86909c;
}

/*
  只读展示位：与输入框同高、同宽，但不带边框和背景，避免「看着能点」。
  再禁掉选中，点上去连文本光标和选区都不会出现 —— 让它一眼就是「信息」而不是「控件」。
*/
.leader-readonly {
  display: inline-flex;
  align-items: center;
  width: 320px;
  min-height: 32px;
  font-size: 14px;
  line-height: 1.4;
  user-select: none;
  cursor: default;
}

.leader-name {
  color: #1d2129;
  font-weight: 500;
}

.leader-empty {
  color: #a8adb5;
}

.form-hint-warn {
  color: #e6a23c;
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.result-hint {
  margin-top: 12px;
  font-size: 13px;
  color: #86909c;
}

.result-actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}

.precheck-alert {
  margin-top: 8px;
}

.precheck-table {
  margin-top: 8px;
}

.precheck-hint {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: #86909c;
}
</style>
