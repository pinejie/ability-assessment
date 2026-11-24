<template>
  <div class="structure-import-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">能力结构导入</h1>
        <p class="page-description">
          上传岗位能力模型 Excel，系统识别列结构后，把拿不准的地方一次性问你；
          确认无误再写入。导入过程可以切页面，回来接着看。
        </p>
      </div>
      <div class="header-actions">
        <el-tag v-if="state" :type="statusTagType" size="large">{{ state.statusText }}</el-tag>
        <el-button v-if="state && !state.busy" @click="handleAbandon">放弃本次导入</el-button>
      </div>
    </div>

    <!-- ============ 步骤条 ============ -->
    <el-card shadow="never" class="step-card">
      <el-steps :active="stepActive" finish-status="success" simple>
        <el-step title="上传文件" />
        <el-step title="选择工作表" />
        <el-step title="确认疑问" />
        <el-step title="预览影响" />
        <el-step title="写入" />
      </el-steps>
    </el-card>

    <!-- ============ 安全阀横幅 ============ -->
    <el-alert
      v-if="state?.status === 'MANUAL'"
      type="error"
      show-icon
      :closable="false"
      class="manual-banner"
      :title="manualTitle"
    >
      <p class="banner-text">
        {{ state.error }}
      </p>
      <p class="banner-text">
        已经确认的内容不会丢失。剩下的问题靠对话解决不了：
        档位类请回表格补齐后重新上传，其余可到「岗位能力模型」页面手工维护。
      </p>
    </el-alert>

    <!-- ============ 空态：上传 ============ -->
    <el-card v-if="!state" class="upload-card">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".xlsx,.xls"
        :on-change="handleFileChange"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">
          把 Excel 拖到这里，或<em>点击选择文件</em>
        </div>
        <template #tip>
          <div class="upload-tip">
            支持 .xlsx / .xls，单文件 ≤ 20MB。一个工作表对应一个岗位能力模型，
            文件里没有写部门的话，扫描后会问你是哪个部门。
          </div>
        </template>
      </el-upload>
    </el-card>

    <!-- ============ 进行中 / 结果 ============ -->
    <template v-else>
      <!-- 忙碌时的进度 -->
      <el-card v-if="state.busy" shadow="never" class="progress-card">
        <div class="progress-head">
          <span class="progress-stage">{{ state.stage || state.statusText }}</span>
          <span class="progress-file">{{ state.fileName }}</span>
        </div>
        <el-progress :percentage="state.progress || 0" :stroke-width="10" striped striped-flow />
        <p class="progress-hint">
          这一步由模型读表，单张表大约 20 秒。可以切到别的页面，回来自动接着看。
        </p>
      </el-card>

      <!-- 失败 / 过期 -->
      <el-alert
        v-else-if="state.status === 'FAILED' || state.status === 'EXPIRED'"
        :type="state.status === 'EXPIRED' ? 'info' : 'error'"
        show-icon
        :closable="false"
        class="result-alert"
        :title="state.status === 'EXPIRED' ? '会话已过期' : '导入失败'"
      >
        <p class="banner-text">{{ state.error || '未知原因' }}</p>
        <el-button type="primary" size="small" @click="reset">重新上传</el-button>
      </el-alert>

      <!-- 完成 -->
      <el-card v-else-if="state.status === 'DONE'" shadow="never" class="result-card">
        <div class="result-head">
          <el-icon class="result-icon success"><CircleCheckFilled /></el-icon>
          <div>
            <h2 class="result-title">导入完成</h2>
            <p class="result-sub">{{ resultSummary }}</p>
          </div>
        </div>

        <el-descriptions :column="3" border size="small" class="result-desc">
          <el-descriptions-item label="新建岗位">
            {{ state.result?.created.length || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="覆盖岗位">
            {{ state.result?.overwritten.length || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="跳过岗位">
            {{ state.result?.skipped.length || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="新增 / 更新能力项">
            {{ state.result?.itemCreated || 0 }} / {{ state.result?.itemUpdated || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="停用 / 删除能力项">
            {{ state.result?.itemDisabled || 0 }} / {{ state.result?.itemRemoved || 0 }}
          </el-descriptions-item>
          <el-descriptions-item label="分值区间">
            {{ state.result?.bandCreated || 0 }} 段
          </el-descriptions-item>
          <el-descriptions-item v-if="state.result?.voidedTaskIds?.length" label="撤回的测评">
            {{ state.result?.voidedTaskIds.length }} 个
          </el-descriptions-item>
          <el-descriptions-item v-if="state.result?.relaunchedTaskIds?.length" label="重新发起">
            {{ state.result?.relaunchedTaskIds.length }} 个
          </el-descriptions-item>
          <el-descriptions-item v-if="state.result?.recalculatedSheetIds?.length" label="重算的报告">
            {{ state.result?.recalculatedSheetIds.length }} 份
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-for="(w, i) in state.result?.warnings || []"
          :key="i"
          type="warning"
          show-icon
          :closable="false"
          class="warn-item"
          :title="w"
        />

        <div class="result-actions">
          <el-button type="primary" @click="goToModels">去看岗位能力模型</el-button>
          <el-button @click="reset">再导一份</el-button>
        </div>
      </el-card>

      <!-- ============ 选 sheet ============ -->
      <el-card v-else-if="state.status === 'SELECTING'" shadow="never" class="select-card">
        <h2 class="card-title">这份文件里有 {{ state.overview?.sheets.length || 0 }} 个工作表</h2>
        <p class="card-desc">
          勾选要做成岗位能力模型的工作表。模型已经给出了初步判断，
          但最终以你的选择为准 —— 一次导入会为每个勾选的表建一个岗位。
        </p>

        <el-alert
          v-for="(n, i) in state.overview?.notes || []"
          :key="i"
          type="info"
          show-icon
          :closable="false"
          class="note-item"
          :title="n"
        />

        <el-table
          ref="sheetTableRef"
          :data="state.overview?.sheets || []"
          row-key="sheetName"
          border
          size="small"
          max-height="480"
          @selection-change="handleSheetSelection"
        >
          <el-table-column type="selection" width="46" />
          <el-table-column prop="sheetName" label="工作表" min-width="180" show-overflow-tooltip />
          <el-table-column label="模型判断" width="110">
            <template #default="{ row }">
              <el-tag :type="row.action === 'IMPORT' ? 'success' : 'info'" size="small">
                {{ row.action === 'IMPORT' ? '像岗位模型' : '不像' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="postName" label="岗位名（模型读到的）" min-width="160" show-overflow-tooltip />
          <el-table-column prop="rowCount" label="行数" width="80" align="right" />
          <el-table-column prop="columnCount" label="列数" width="80" align="right" />
          <el-table-column label="表头预览" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              {{ (row.headerPreview || []).join('  ') }}
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="依据" min-width="200" show-overflow-tooltip />
        </el-table>

        <div class="select-actions">
          <span class="select-count">已选 {{ pickedSheets.length }} 个</span>
          <el-button type="primary" :disabled="!pickedSheets.length" @click="handleSelectSheets">
            识别选中的工作表
          </el-button>
        </div>
      </el-card>

      <!-- ============ 回答疑问 ============ -->
      <el-card v-else-if="state.status === 'NEEDS_CONFIRM'" shadow="never" class="confirm-card">
        <h2 class="card-title">
          {{ blockingQuestions.length }} 个地方需要你确认
          <el-tag v-if="noticeQuestions.length" type="info" size="small" class="title-tag">
            另有 {{ noticeQuestions.length }} 条提醒
          </el-tag>
        </h2>
        <p class="card-desc">
          所有问题一次问完。回答后如果还有新问题会再问一轮
          （已问 {{ state.roundNo || 0 }}/{{ state.maxRounds || 10 }} 轮）。
        </p>

        <el-alert
          v-if="state.error"
          type="warning"
          show-icon
          :closable="false"
          class="note-item"
          :title="state.error"
        />

        <!-- 部门：必须选真实部门，不让人手打名字（重名部门很常见）。
             一次导入只指定一个部门，所以这里刻意是单选而不是「每个岗位各选一个」。
             这条问题不放进下面的 v-for：它已经在 blockingQuestions 里，
             再走一遍循环会让同一个问题在页面上出现两遍。 -->
        <div v-if="needDepartment" class="dept-picker">
          <div class="question-head">
            <span class="question-no">{{ deptQuestion?.no }}</span>
            <el-tag
              v-if="deptQuestion?.type"
              size="small"
              :type="typeTagOf(deptQuestion.type)"
            >{{ typeNameOf(deptQuestion.type) }}</el-tag>
          </div>
          <div class="question-topic">
            {{ deptQuestion?.question || '这些岗位归到哪个部门？' }}
          </div>
          <div v-if="deptPostNames.length" class="dept-posts">
            这次导入的岗位：{{ deptPostNames.join('、') }}
          </div>
          <!-- 全公司通用：勾上就不选部门。用勾选框而不是部门树里加一个「通用」节点 ——
               树节点必须是真实部门 id，塞假节点会让写库拿去查 HrmDepartment 然后报错 -->
          <el-checkbox v-model="genericDepartment" class="dept-generic">
            全公司通用（不绑定部门，所有部门共用这一套）
          </el-checkbox>
          <el-tree-select
            v-model="pickedDepartmentId"
            :data="treeData"
            :props="deptTreeProps"
            node-key="id"
            check-strictly
            filterable
            :disabled="genericDepartment"
            :placeholder="genericDepartment ? '已选全公司通用' : '选择部门'"
            style="width: 320px"
          />
        </div>

        <div v-for="q in otherQuestions" :key="q.slot + q.no" class="question-item">
          <div class="question-head">
            <span class="question-no">{{ q.no }}</span>
            <el-tag size="small" :type="typeTagOf(q.type)">{{ typeNameOf(q.type) }}</el-tag>
            <span v-if="q.sheetName" class="question-sheet">{{ q.sheetName }}</span>
          </div>
          <div class="question-topic">{{ q.question || q.topic }}</div>

          <el-radio-group
            v-if="q.candidates && q.candidates.length"
            v-model="answers[q.slot]"
            class="question-options"
          >
            <el-radio v-for="c in q.candidates" :key="c" :value="c" border>{{ c }}</el-radio>
          </el-radio-group>
          <el-input
            v-else
            v-model="answers[q.slot]"
            type="textarea"
            :rows="2"
            :placeholder="q.suggestion ? `建议：${q.suggestion}` : '直接写你的确认'"
            class="question-input"
          />
        </div>

        <div v-if="noticeQuestions.length" class="notice-block">
          <div class="notice-title">提醒（不影响导入，可以不管）</div>
          <ul class="notice-list">
            <li v-for="q in noticeQuestions" :key="q.slot + q.no">{{ q.question || q.topic }}</li>
          </ul>
        </div>

        <div class="confirm-actions">
          <el-input
            v-model="freeText"
            type="textarea"
            :rows="2"
            placeholder="也可以直接用一句话说明（例如「部门是质量部，权重就按 98% 导」）"
            class="free-text"
          />
          <el-button type="primary" :loading="submitting" @click="handleAnswer">
            提交确认
          </el-button>
        </div>
      </el-card>

      <!-- ============ 预览 + 二次确认 ============ -->
      <template v-else-if="state.status === 'READY' || state.status === 'APPLYING'">
        <el-card shadow="never" class="preview-card">
          <h2 class="card-title">导入预览</h2>
          <p class="card-desc">
            下面是写入后每个岗位的变化。核对无误再点最下方的「确认导入」——
            写入是一个事务，中途失败会全部回滚。
          </p>

          <el-table :data="state.preview?.posts || []" border size="small" row-key="sheetName">
            <el-table-column prop="postName" label="岗位" min-width="160" show-overflow-tooltip />
            <el-table-column prop="departmentName" label="部门" min-width="140" show-overflow-tooltip />
            <el-table-column label="动作" width="110">
              <template #default="{ row }">
                <el-tag :type="actionTagOf(row.action)" size="small">{{ actionNameOf(row.action) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类别/能力项" width="120" align="center">
              <template #default="{ row }">{{ row.categoryCount }} / {{ row.itemCount }}</template>
            </el-table-column>
            <el-table-column label="权重合计" width="110" align="right">
              <template #default="{ row }">{{ formatWeight(row.weightTotal) }}</template>
            </el-table-column>
            <el-table-column label="变化" min-width="240">
              <template #default="{ row }">
                <div class="diff-line">
                  <el-tag v-if="row.changed.length" size="small" type="warning">改 {{ row.changed.length }}</el-tag>
                  <el-tag v-if="row.added.length" size="small" type="success">增 {{ row.added.length }}</el-tag>
                  <el-tag v-if="row.removed.length" size="small" type="danger">减 {{ row.removed.length }}</el-tag>
                  <span v-if="!row.changed.length && !row.added.length && !row.removed.length" class="diff-none">
                    与库里一致
                  </span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="summary" label="说明" min-width="320" show-overflow-tooltip />
          </el-table>
        </el-card>

        <!-- 影响面：进行中的测评 -->
        <el-card
          v-if="(state.preview?.impact.runningCount || 0) > 0"
          shadow="never"
          class="impact-card"
        >
          <h3 class="impact-title">
            <el-icon><WarningFilled /></el-icon>
            有 {{ state.preview?.impact.runningCount }} 个进行中的测评任务受影响
          </h3>
          <p class="impact-desc">
            这些任务已经按旧模型把问卷发下去了。
            如果只改模型不撤回，评出来的分数跟新模型对不上；
            撤回会作废评价人已提交的 {{ state.preview?.impact.submittedRaterCount }} 份内容，
            重新发起后评价人会<strong>再收到一条邀请</strong>（所以一共两条消息）。
          </p>

          <el-table
            :data="state.preview?.impact.running || []"
            border
            size="small"
            max-height="240"
          >
            <el-table-column prop="year" label="年度" width="80" />
            <el-table-column prop="resourceName" label="被测人" min-width="120" />
            <el-table-column prop="departmentName" label="部门" min-width="140" show-overflow-tooltip />
            <el-table-column prop="postName" label="岗位" min-width="140" show-overflow-tooltip />
            <el-table-column label="已提交" width="120" align="center">
              <template #default="{ row }">{{ row.submittedRaters }} / {{ row.totalRaters }}</template>
            </el-table-column>
          </el-table>

          <div class="impact-options">
            <el-checkbox v-model="planForm.voidRunning" @change="handlePlanChange">
              撤回这些任务（推荐）
            </el-checkbox>
            <el-checkbox
              v-model="planForm.relaunchRunning"
              :disabled="!planForm.voidRunning"
              @change="handlePlanChange"
            >
              撤回后按原参数重新发起
            </el-checkbox>
            <el-input
              v-if="planForm.voidRunning"
              v-model="planForm.voidReason"
              placeholder="撤回原因（会发给评价人，不填用默认文案）"
              maxlength="200"
              show-word-limit
              class="void-reason"
              @blur="handlePlanChange"
            />
          </div>
        </el-card>

        <!-- 影响面：已封存报告 -->
        <el-card
          v-if="(state.preview?.impact.frozenCount || 0) > 0"
          shadow="never"
          class="impact-card"
        >
          <h3 class="impact-title">
            <el-icon><Lock /></el-icon>
            有 {{ state.preview?.impact.frozenCount }} 份已封存的报告
          </h3>
          <p class="impact-desc">
            封存报告是已经发给业务方的结果（分数、等级都已定）。
            默认<strong>保持原样</strong>；如果这次导入是纠错，可以按新模型重算 ——
            重算用的仍是当初评价人打的分，不会重新发问卷。
          </p>

          <div class="impact-options">
            <el-checkbox v-model="planForm.regenerateFrozen" @change="handlePlanChange">
              重新生成已封存报告
            </el-checkbox>
          </div>

          <div v-if="planForm.regenerateFrozen" class="year-picker">
            <span class="year-label">按年度选择：</span>
            <el-checkbox-group v-model="planForm.regenerateYears" @change="handlePlanChange">
              <el-checkbox
                v-for="y in state.preview?.impact.years || []"
                :key="y"
                :value="y"
              >
                {{ y }} 年
              </el-checkbox>
            </el-checkbox-group>
            <span class="year-hint">不勾任何年份 = 全部重算</span>
          </div>

          <el-table
            :data="frozenPreview"
            border
            size="small"
            max-height="200"
            class="frozen-table"
          >
            <el-table-column prop="year" label="年度" width="80" />
            <el-table-column prop="resourceName" label="被测人" min-width="120" />
            <el-table-column prop="departmentName" label="部门" min-width="140" show-overflow-tooltip />
            <el-table-column prop="postName" label="岗位" min-width="140" show-overflow-tooltip />
            <el-table-column label="当前分数" width="110" align="right">
              <template #default="{ row }">{{ row.totalScore ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="bandName" label="等级" width="120" />
          </el-table>
        </el-card>

        <!-- 覆盖策略 -->
        <el-card shadow="never" class="impact-card">
          <h3 class="impact-title">同名岗位怎么处理</h3>
          <el-radio-group v-model="planForm.existing" @change="handlePlanChange">
            <el-radio value="OVERWRITE" border>
              覆盖 —— 以文件为准（一致也重写一遍）
            </el-radio>
            <el-radio value="SKIP" border>跳过 —— 保留库里现状，只建新岗位</el-radio>
          </el-radio-group>
        </el-card>

        <!-- 提醒 -->
        <el-alert
          v-for="(w, i) in state.preview?.warnings || []"
          :key="i"
          type="warning"
          show-icon
          :closable="false"
          class="warn-item"
          :title="w"
        />

        <el-card shadow="never" class="apply-card">
          <div class="apply-actions">
            <div class="apply-summary">
              将写入 {{ createdCount }} 个新岗位、覆盖 {{ overwrittenCount }} 个
              <template v-if="planForm.voidRunning && (state.preview?.impact.runningCount || 0)">
                ，撤回 {{ state.preview?.impact.runningCount }} 个测评任务
              </template>
              <template v-if="planForm.regenerateFrozen">
                ，重算 {{ regenerateCount }} 份报告
              </template>
            </div>
            <el-button
              type="primary"
              size="large"
              :loading="applying || state.status === 'APPLYING'"
              :disabled="state.status === 'APPLYING'"
              @click="handleApply"
            >
              确认导入
            </el-button>
          </div>
        </el-card>
      </template>

      <!-- 对话留痕：所有阶段都展示 -->
      <el-card v-if="state.dialog?.length" shadow="never" class="dialog-card">
        <div class="dialog-head">
          <h3 class="card-title">确认过程</h3>
          <el-button link size="small" @click="showDialog = !showDialog">
            {{ showDialog ? '收起' : '展开' }}
          </el-button>
        </div>
        <div v-show="showDialog" class="dialog-list">
          <div v-for="(d, i) in state.dialog" :key="i" class="dialog-row" :class="`kind-${d.kind}`">
            <span class="dialog-at">{{ d.at }}</span>
            <span class="dialog-kind">{{ kindNameOf(d.kind) }}</span>
            <span class="dialog-text">{{ d.text }}</span>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 能力结构导入页。
 *
 * 交互口径（业务确认过的）：
 * - 每个动作都是「提交 → 轮询」，不用一个请求等到底
 * - 会话落库，切页面回来自动恢复（进页面调 /sessions/current）
 * - 所有问题一次问完，解决不了再问下一轮；超过安全阀在页面上明确提示
 * - 预览是「二次确认」：覆盖已有模型、重算封存报告、撤回进行中任务都要在这里点头
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CircleCheckFilled,
  Lock,
  UploadFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  abandonImport,
  applyImport,
  getCurrentSession,
  getImportState,
  pollImport,
  selectImportSheets,
  submitImportAnswers,
  updateImportPlan,
  uploadImportFile,
} from '@/api/paImport'
import { deptTreeProps, useDepartmentTree } from '@/composables/useDepartmentTree'
import type {
  PaImportPlan,
  PaImportQuestion,
  PaImportSessionVO,
  PaImportSheetView,
} from '@/types/paImport'

const router = useRouter()
const { treeData, load: loadDeptTree } = useDepartmentTree()

const state = ref<PaImportSessionVO | null>(null)
const submitting = ref(false)
const applying = ref(false)
const showDialog = ref(true)

/** 回答：slot → 值 */
const answers = reactive<Record<string, string>>({})
/** 整段自然语言回答 */
const freeText = ref('')
/** 选的部门 */
const pickedDepartmentId = ref<number | undefined>()
/** 选了「全公司通用」：岗位不绑部门，此时部门树被禁用 */
const genericDepartment = ref(false)
/** 部门槽位：走部门树/通用勾选框，不进 answers（见 handleAnswer 里的说明） */
const DEPT_SLOT = 'dept'
/** 选的 sheet */
const pickedSheets = ref<string[]>([])
const sheetTableRef = ref()

/** 计划表单（与后端 PaImportPlan 同结构） */
const planForm = reactive<PaImportPlan>({
  existing: 'OVERWRITE',
  regenerateFrozen: false,
  regenerateYears: [],
  voidRunning: false,
  relaunchRunning: true,
  voidReason: '',
  selectedSheets: [],
  resolved: {},
})

let stopPolling: (() => void) | null = null
/** 当前订阅的是哪个会话：避免重复起轮询 */
let polledKey: string | null = null

// ---------------------------------------------------------------- 计算属性

const stepActive = computed(() => {
  const s = state.value
  if (!s) return 0
  switch (s.status) {
    case 'PARSING':
    case 'CHECKING':
      return 1
    case 'SELECTING':
      return 1
    case 'NEEDS_CONFIRM':
    case 'MANUAL':
      return 2
    case 'READY':
    case 'APPLYING':
      return 3
    case 'DONE':
      return 5
    default:
      return 0
  }
})

const statusTagType = computed(() => {
  const s = state.value?.status
  if (s === 'DONE') return 'success'
  if (s === 'FAILED' || s === 'MANUAL') return 'danger'
  if (s === 'EXPIRED') return 'info'
  if (s === 'READY') return 'warning'
  return 'primary'
})

/**
 * MANUAL 有两种成因，标题不能写死。
 * 后端在「剩余问题一轮对话都解决不了」（目前只有等级档位）时会直接转人工，
 * 一轮都没问就写「追问次数已达上限」会让人以为自己答错了什么。
 */
const manualTitle = computed(() => {
  const err = state.value?.error || ''
  return err.includes('追问次数已达上限')
    ? '追问次数已达上限，自动识别无法继续'
    : '有些问题对话里改不了，需要人工处理'
})

const blockingQuestions = computed<PaImportQuestion[]>(() =>
  (state.value?.questions || []).filter((q) => q.blocking),
)
const noticeQuestions = computed<PaImportQuestion[]>(() =>
  (state.value?.questions || []).filter((q) => !q.blocking),
)

/** 有待确认的部门问题（没有候选值、类型是 MISSING 且 slot 是 dept） */
const deptQuestion = computed<PaImportQuestion | undefined>(() =>
  blockingQuestions.value.find((x) => x.slot === 'dept'),
)
const needDepartment = computed(() => Boolean(deptQuestion.value))

/** 除部门以外的阻塞问题：部门有专门的选择器，不能再走通用循环 */
const otherQuestions = computed<PaImportQuestion[]>(() =>
  blockingQuestions.value.filter((x) => x.slot !== 'dept'),
)

/** 部门问题会一起归到这个部门的岗位名（从问题上下文里取） */
const deptPostNames = computed<string[]>(() => {
  const posts = deptQuestion.value?.context?.posts
  return Array.isArray(posts) ? (posts as string[]) : []
})

const frozenPreview = computed(() => {
  const frozen = state.value?.preview?.impact.frozen || []
  if (!planForm.regenerateFrozen) {
    return frozen
  }
  if (!planForm.regenerateYears.length) {
    return frozen
  }
  return frozen.filter((f) => planForm.regenerateYears.includes(f.year))
})

const createdCount = computed(
  () => state.value?.preview?.posts.filter((p) => p.action === 'CREATE').length || 0,
)
const overwrittenCount = computed(
  () => state.value?.preview?.posts.filter((p) => p.action === 'OVERWRITE').length || 0,
)
const regenerateCount = computed(() => (planForm.regenerateFrozen ? frozenPreview.value.length : 0))

const resultSummary = computed(() => {
  const r = state.value?.result
  if (!r) return ''
  const parts: string[] = []
  if (r.created.length) parts.push(`新建 ${r.created.length} 个岗位`)
  if (r.overwritten.length) parts.push(`覆盖 ${r.overwritten.length} 个岗位`)
  if (r.skipped.length) parts.push(`跳过 ${r.skipped.length} 个`)
  return parts.join('，') || '没有任何岗位被写入'
})

// ---------------------------------------------------------------- 生命周期

onMounted(async () => {
  loadDeptTree()
  try {
    const session = await getCurrentSession()
    if (session) {
      applyState(session)
      ensurePolling()
    }
  } catch {
    /* 没有会话是正常情况 */
  }
})

onBeforeUnmount(() => {
  stopPoll()
})

// ---------------------------------------------------------------- 状态

function applyState(next: PaImportSessionVO): void {
  state.value = next

  // 计划回显：用户刷新/切页面回来，勾选状态要还在
  if (next.plan) {
    planForm.existing = next.plan.existing || 'OVERWRITE'
    planForm.regenerateFrozen = next.plan.regenerateFrozen
    planForm.regenerateYears = next.plan.regenerateYears || []
    planForm.voidRunning = next.plan.voidRunning
    planForm.relaunchRunning = next.plan.relaunchRunning
    planForm.voidReason = next.plan.voidReason || ''
  }
  if (next.selectedSheets?.length && !pickedSheets.value.length) {
    pickedSheets.value = next.selectedSheets
  }
  if (next.status === 'SELECTING' && next.selectedSheets?.length) {
    void nextTick(() => syncSheetSelection(next.selectedSheets))
  }

  // 问题列表变化时预填建议值（只在用户没填过的时候填）。
  // 但 dept 槽位不预填：它在页面上是专门的部门选择器，没有输入框，
  // 预填进来用户看不见，提交时却会跟真正选的部门一起上去 ——
  // 2026-11-23 线上实测：选了部门，导入出来却是「全公司通用」。
  next.questions?.forEach((q) => {
    if (q.slot && q.slot !== DEPT_SLOT && answers[q.slot] === undefined && q.suggestion) {
      answers[q.slot] = q.suggestion
    }
  })

  if (next.status !== 'CHECKING') {
    submitting.value = false
  }
  if (next.status !== 'APPLYING') {
    applying.value = false
  }
}

/**
 * 轮询只有这一个入口：只要后端在跑（busy）就保持订阅，跑完自动停。
 *
 * 不做「POST 之后手动起一次、其它地方再手动停」—— 那样很容易出现
 * 「本地状态以为闲、后端其实还在跑」，用户就会在模型读表期间点第二次确认。
 */
function ensurePolling(): void {
  const key = state.value?.sessionKey
  if (!key) return
  if (!state.value?.busy) {
    stopPolling?.()
    stopPolling = null
    polledKey = null
    return
  }
  if (polledKey !== key) {
    stopPolling?.()
    polledKey = key
    stopPolling = pollImport(key, (s) => onPoll(s), onPollError, 1500)
  }
}

function stopPoll(): void {
  stopPolling?.()
  stopPolling = null
  polledKey = null
}

function onPoll(next: PaImportSessionVO): void {
  applyState(next)
  // 后端跑完了：停订阅，交给用户操作；下次动作后由 ensurePolling 重新起
  if (!next.busy) {
    stopPoll()
  }
}

function onPollError(): void {
  // 单次轮询失败不打断：网络抖动很常见，下一轮会补上
  if (!state.value?.busy) {
    stopPoll()
  }
}

/**
 * SELECTING 阶段回显已勾的工作表。
 *
 * el-table 的勾选状态只存在组件内部，离开页面再回来是空的，
 * 而 session 里已经记了用户上次勾了什么 —— 不刷回去，用户会以为选择丢了。
 */
function syncSheetSelection(names: string[]): void {
  const table = sheetTableRef.value
  const sheets = state.value?.overview?.sheets
  if (!table || !sheets) return
  table.clearSelection()
  sheets.forEach((row) => {
    if (names.includes(row.sheetName)) {
      table.toggleRowSelection(row, true)
    }
  })
  pickedSheets.value = sheets.filter((s) => names.includes(s.sheetName)).map((s) => s.sheetName)
}

// ---------------------------------------------------------------- 交互

async function handleFileChange(uploadFile: { raw?: File }): Promise<void> {
  const file = uploadFile.raw
  if (!file) return
  try {
    const { sessionKey } = await uploadImportFile(file, true)
    ElMessage.success('文件已接收，正在识别')
    applyState(await getImportState(sessionKey))
    ensurePolling()
  } catch {
    /* 拦截器已提示 */
  }
}

function handleSheetSelection(rows: PaImportSheetView[]): void {
  pickedSheets.value = rows.map((r) => r.sheetName)
}

async function handleSelectSheets(): Promise<void> {
  if (!state.value) return
  if (!pickedSheets.value.length) {
    ElMessage.warning('请至少选一个工作表')
    return
  }
  try {
    const next = await selectImportSheets(state.value.sessionKey, {
      selectedSheets: pickedSheets.value,
    })
    applyState(next)
    ensurePolling()
  } catch {
    /* 拦截器已提示 */
  }
}

async function handleAnswer(): Promise<void> {
  if (!state.value) return
  const values: Record<string, string> = {}
  blockingQuestions.value.forEach((q) => {
    // dept 不进 values：它走部门树 / 通用勾选框两个独立字段。
    // 混进 values 的话，一个看不见的残留值就能把用户选的部门顶掉
    if (q.slot === DEPT_SLOT) return
    const v = answers[q.slot]
    if (v && v.trim()) {
      values[q.slot] = v.trim()
    }
  })
  if (!Object.keys(values).length && !freeText.value.trim()
      && !pickedDepartmentId.value && !genericDepartment.value) {
    ElMessage.warning('还没有填写任何确认内容')
    return
  }
  submitting.value = true
  try {
    const next = await submitImportAnswers(state.value.sessionKey, {
      values,
      text: freeText.value.trim() || undefined,
      // 通用时不要把上次选的部门一起发上去：后端 departmentId 优先，会把通用顶掉
      departmentId: genericDepartment.value ? undefined : pickedDepartmentId.value,
      genericDepartment: genericDepartment.value || undefined,
    })
    freeText.value = ''
    pickedDepartmentId.value = undefined
    genericDepartment.value = false
    applyState(next)
    ensurePolling()
  } catch {
    submitting.value = false
  }
}

/**
 * 改计划 → 后端重算预览。
 *
 * 不回写 planForm：用户正在改的就是它，用响应覆盖会把刚勾/刚取消的勾选弹回去
 * （一份预览重算要几百毫秒，够用户再点两下）。
 */
async function handlePlanChange(): Promise<void> {
  if (!state.value) return
  try {
    const next = await updateImportPlan(state.value.sessionKey, {
      existing: planForm.existing,
      regenerateFrozen: planForm.regenerateFrozen,
      regenerateYears: planForm.regenerateYears,
      voidRunning: planForm.voidRunning,
      relaunchRunning: planForm.relaunchRunning,
      voidReason: planForm.voidReason || undefined,
      selectedSheets: [],
      resolved: {},
    })
    state.value = next
  } catch {
    /* 拦截器已提示 */
  }
}

async function handleApply(): Promise<void> {
  if (!state.value) return
  const impact = state.value.preview?.impact
  const lines: string[] = []
  if (impact?.runningCount) {
    lines.push(
      planForm.voidRunning
        ? `撤回 ${impact.runningCount} 个进行中的测评（作废 ${impact.submittedRaterCount} 份已提交内容${
            planForm.relaunchRunning ? '，并重新发起' : ''
          }）`
        : `保留 ${impact.runningCount} 个进行中的测评（它们仍按旧模型评价）`,
    )
  }
  if (impact?.frozenCount) {
    lines.push(
      planForm.regenerateFrozen
        ? `重算 ${regenerateCount.value} 份已封存报告`
        : `保留 ${impact.frozenCount} 份已封存报告原样`,
    )
  }
  lines.push(`写入 ${createdCount.value} 个新岗位、覆盖 ${overwrittenCount.value} 个岗位`)
  // 最后一条自己带句号，拼完不要再补一个，否则会出现「全部回滚。。确定继续？」
  lines.push('写入是一个事务，中途失败会全部回滚')

  try {
    await ElMessageBox.confirm(lines.join('；') + '。确定继续？', '确认导入', {
      confirmButtonText: '确认导入',
      cancelButtonText: '再检查一下',
      type: 'warning',
    })
  } catch {
    return
  }

  applying.value = true
  try {
    const next = await applyImport(state.value.sessionKey)
    applyState(next)
    ensurePolling()
  } catch {
    applying.value = false
  }
}

async function handleAbandon(): Promise<void> {
  if (!state.value) return
  try {
    await ElMessageBox.confirm('放弃后这次识别结果不会保留，下次要重新上传文件。', '放弃本次导入', {
      confirmButtonText: '放弃',
      cancelButtonText: '继续导入',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await abandonImport(state.value.sessionKey)
    reset()
    ElMessage.success('已放弃')
  } catch {
    /* 拦截器已提示 */
  }
}

function reset(): void {
  stopPoll()
  state.value = null
  pickedSheets.value = []
  pickedDepartmentId.value = undefined
  freeText.value = ''
  Object.keys(answers).forEach((k) => delete answers[k])
}

function goToModels(): void {
  void router.push('/pa-models')
}

// ---------------------------------------------------------------- 展示辅助

function typeNameOf(type: string): string {
  const map: Record<string, string> = {
    MISSING: '缺信息',
    AMBIGUOUS: '有歧义',
    CONFLICT: '自相矛盾',
    UNKNOWN_VOCAB: '取值不认识',
    COLLISION: '岗位已存在',
    SUSPECT: '疑似有问题',
    MISMATCH: '与原文不符',
  }
  return map[type] || type
}

function typeTagOf(type: string): 'success' | 'info' | 'warning' | 'danger' {
  if (type === 'CONFLICT' || type === 'MISMATCH') return 'danger'
  if (type === 'COLLISION' || type === 'UNKNOWN_VOCAB') return 'warning'
  if (type === 'SUSPECT') return 'info'
  return 'warning'
}

function kindNameOf(kind: string): string {
  const map: Record<string, string> = {
    QUESTION: '问',
    ANSWER: '答',
    RESOLVED: '记',
    NOTE: '注',
  }
  return map[kind] || kind
}

function actionNameOf(action: string): string {
  const map: Record<string, string> = {
    CREATE: '新建',
    OVERWRITE: '覆盖',
    SKIP: '跳过',
  }
  return map[action] || action
}

function actionTagOf(action: string): 'success' | 'warning' | 'info' {
  if (action === 'CREATE') return 'success'
  if (action === 'OVERWRITE') return 'warning'
  return 'info'
}

function formatWeight(weight: number): string {
  if (weight === null || weight === undefined) return '—'
  const rounded = Math.round(weight * 100) / 100
  return `${rounded}%`
}
</script>

<style scoped>
.structure-import-page {
  padding: 16px 20px 40px;
  max-width: 1400px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 20px;
}
.page-description {
  margin: 0;
  color: #666;
  font-size: 13px;
  line-height: 1.6;
  max-width: 780px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.step-card {
  margin-bottom: 12px;
}

.manual-banner {
  margin-bottom: 12px;
}
.banner-text {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.6;
}

.upload-card {
  margin-bottom: 12px;
}
.upload-icon {
  font-size: 52px;
  color: #c0c4cc;
  margin-bottom: 8px;
}
.upload-text {
  font-size: 14px;
  color: #606266;
}
.upload-text em {
  color: #409eff;
  font-style: normal;
}
.upload-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.progress-card {
  margin-bottom: 12px;
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.progress-stage {
  font-size: 15px;
  font-weight: 500;
}
.progress-file {
  font-size: 12px;
  color: #909399;
}
.progress-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
}

.result-alert {
  margin-bottom: 12px;
}

.result-card {
  margin-bottom: 12px;
}
.result-head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}
.result-icon {
  font-size: 36px;
}
.result-icon.success {
  color: #67c23a;
}
.result-title {
  margin: 0 0 4px;
  font-size: 18px;
}
.result-sub {
  margin: 0;
  color: #666;
  font-size: 13px;
}
.result-desc {
  margin-bottom: 12px;
}
.result-actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}

.card-title {
  margin: 0 0 6px;
  font-size: 16px;
}
.title-tag {
  margin-left: 8px;
  vertical-align: middle;
}
.card-desc {
  margin: 0 0 12px;
  color: #666;
  font-size: 13px;
  line-height: 1.7;
}

.note-item {
  margin-bottom: 8px;
}

.select-card {
  margin-bottom: 12px;
}
.select-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
}
.select-count {
  font-size: 13px;
  color: #606266;
}

.confirm-card {
  margin-bottom: 12px;
}
.dept-picker {
  padding: 12px 14px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 14px;
}
.dept-posts {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
  line-height: 1.6;
}
.dept-generic {
  display: block;
  margin-bottom: 8px;
}
.question-item {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.question-item:last-of-type {
  border-bottom: none;
}
.question-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.question-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 12px;
}
.question-sheet {
  font-size: 12px;
  color: #909399;
}
.question-topic {
  font-size: 14px;
  color: #303133;
  line-height: 1.7;
  margin-bottom: 8px;
}
.question-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.question-input {
  max-width: 640px;
}

.notice-block {
  margin-top: 14px;
  padding: 10px 14px;
  background: #fdf6ec;
  border-radius: 6px;
}
.notice-title {
  font-size: 13px;
  font-weight: 500;
  color: #b88230;
  margin-bottom: 6px;
}
.notice-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: #606266;
  line-height: 1.8;
}

.confirm-actions {
  margin-top: 18px;
  display: flex;
  gap: 12px;
  align-items: flex-end;
}
.free-text {
  flex: 1;
}

.preview-card,
.impact-card,
.apply-card,
.dialog-card {
  margin-bottom: 12px;
}
.impact-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 15px;
  color: #303133;
}
.impact-title .el-icon {
  color: #e6a23c;
}
.impact-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: #606266;
  line-height: 1.8;
}
.impact-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.void-reason {
  max-width: 520px;
}
.year-picker {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.year-label {
  font-size: 13px;
  color: #606266;
}
.year-hint {
  font-size: 12px;
  color: #909399;
}
.frozen-table {
  margin-top: 12px;
}

.diff-line {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.diff-none {
  font-size: 12px;
  color: #67c23a;
}

.warn-item {
  margin-bottom: 8px;
}

.apply-card {
  position: sticky;
  bottom: 0;
  z-index: 10;
}
.apply-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.apply-summary {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.dialog-list {
  margin-top: 10px;
  max-height: 260px;
  overflow-y: auto;
}
.dialog-row {
  display: flex;
  gap: 10px;
  padding: 4px 0;
  font-size: 13px;
  line-height: 1.6;
}
.dialog-at {
  color: #c0c4cc;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.dialog-kind {
  flex-shrink: 0;
  width: 28px;
  text-align: center;
  border-radius: 3px;
  background: #f0f2f5;
  color: #606266;
}
.kind-QUESTION .dialog-kind {
  background: #ecf5ff;
  color: #409eff;
}
.kind-ANSWER .dialog-kind {
  background: #f0f9eb;
  color: #67c23a;
}
.kind-RESOLVED .dialog-kind {
  background: #f0f9eb;
  color: #529b2e;
}
.dialog-text {
  color: #303133;
}
</style>
