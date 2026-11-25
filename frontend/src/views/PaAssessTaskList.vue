<template>
  <div class="assess-task-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">发起测评</h1>
        <p class="page-description">
          选择年度、被测人与岗位，直接上级按 OA 组织关系自动带出并锁定；所选岗位的能力模型里配了
          「360 测评人」这一评级主体时，还需要指定 360 测评人。
          被测人只能选已配置岗位部门的人，岗位也随之限定在该部门内。
          系统会生成自评、直接上级、360 三条独立链接，并通过泛微消息推送。
          评价人名单发起后不可追加或替换；名单选错只能撤回后重新发起。
        </p>
      </div>
      <!-- 本页不占菜单，靠「测评任务发起」页的按钮进来，回程也得给一个，否则只能浏览器后退 -->
      <el-button @click="router.push('/pa/assess-task-manage')">返回任务列表</el-button>
    </div>

    <el-card class="form-card">
      <!--
        重新发起（2026-11-25）：从「测评任务发起」列表的已撤回行点进来时，
        把原任务的年度/被测人/岗位/360 名单带出来。这里给一条横幅说明此刻在做什么，
        否则用户看到一份填好的表单，会分不清「这是新发起」还是「在重发那条撤回的」。
      -->
      <el-alert
        v-if="relaunchTaskId"
        class="relaunch-alert"
        type="info"
        :closable="false"
        show-icon
        :title="`正在按任务 #${relaunchTaskId} 重新发起`"
        :description="relaunchDescription"
      >
        <ul v-if="relaunchWarnings.length" class="relaunch-warnings">
          <li v-for="(w, i) in relaunchWarnings" :key="i">{{ w }}</li>
        </ul>
      </el-alert>

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
            :placeholder="form.year ? resourcePlaceholder : '请先选择年度'"
            no-data-text="没有匹配的人员，换个姓名或拼音试试"
            style="width: 320px"
            @change="onTargetChange"
          >
            <el-option
              v-for="r in resourceOptions"
              :key="r.id"
              :label="formatPerson(r)"
              :value="r.id"
            />
            <!--
              remote 模式下 Element Plus 不展开空的候选面板（useSelect 的 dropdownMenuVisible
              要求 !isRemoteSearchEmpty 或存在 #empty 插槽）。不给这个插槽，点开下拉什么都不会出现，
              用户以为控件坏了；给了才会显示「输入姓名搜索」这类引导。
            -->
            <template #empty>
              <div class="select-empty-hint">{{ resourceEmptyText }}</div>
            </template>
          </el-select>
          <span v-if="hasGenericPost && scopeDeptIds == null" class="form-hint">
            有「全公司通用」岗位，任何部门的人都能发起
          </span>
          <span v-else-if="hasGenericPost" class="form-hint">
            有「全公司通用」岗位，本维度授权分部的人都能发起
          </span>
          <span v-else-if="targetDeptIds.length" class="form-hint">
            只列「已配置岗位」部门的人（共 {{ targetDeptIds.length }} 个部门）
          </span>
          <span v-else-if="postListLoaded && postDeptIds.length" class="form-hint form-hint-warn">
            现有岗位所在的部门都不在本维度授权的分部内，请先在「岗位管理」为本分部的部门建岗位
          </span>
          <span v-else-if="postListLoaded" class="form-hint form-hint-warn">
            当前维度还没有启用中的岗位，请先到「岗位管理」新增
          </span>
        </el-form-item>

        <!--
          不加 required（2026-11-14）：星号的意思是「请你填」，而这一栏是系统按 OA 组织关系带出的，
          用户填不了。它确实卡流程（缺了就没有上级评级），但这个信息由下面的橙色提示
          和按钮侧的「没有直接上级无法发起」来传达，红色星号给不了。
        -->
        <el-form-item label="直接上级">
          <!--
            上级只做展示，不渲染成输入框：readonly 的 input 仍能聚焦并出现文本光标，看起来像坏了；
            而语义上它确实不可改 —— 取值唯一来源是 HrmResource.managerid，后端 create 也会忽略前端传上来的 leader。
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

        <!--
          岗位在此当场选定（2026-11-21 增补）：原先要先到「人员岗位映射」页把人和岗位配一遍，
          那张表只服务于「发起」这一个动作，多一道手续还得单独维护。现在选完就能看到
          「按哪套尺子评、几项、要不要选 360」。
        -->
        <el-form-item label="岗位" required>
          <el-select
            v-model="form.postId"
            filterable
            :loading="postListLoading"
            :disabled="!form.year || !form.resourceId"
            :placeholder="postPlaceholder"
            no-data-text="当前维度还没有启用中的岗位，请先到「岗位管理」新增"
            style="width: 420px"
            @change="onPostChange"
          >
            <el-option
              v-for="p in postChoices"
              :key="p.id"
              :label="p.departmentName ? `${p.departmentName} / ${p.postName}` : `${p.postName}（全公司通用）`"
              :value="p.id"
            />
          </el-select>
          <template v-if="post && post.postId">
            <span class="form-hint">共 {{ post.itemCount }} 个能力项</span>
            <span class="form-hint">权重合计 {{ post.weightTotal }}%</span>
          </template>
          <span v-else-if="postLoading" class="form-hint">正在读取…</span>
        </el-form-item>

        <!--
          逐项选人（2026-11-23）：原先一批 360 测评人要评岗位里全部 360 项。
          实际业务里不同能力项该问的人不一样（「数据分析」问数据组、「客户沟通」问对接的客户部门），
          所以改成一项一组人。

          表格上方不再留「批量套用」栏（2026-11-24 撤）：一行一项、每行自己选人已经是主路径，
          再挂一个批次框反而要人多判断一次「这框里的算不算已选」，且批量落下去之后
          到底有没有逐项改过、哪些没改，界面上看不出来。
        -->
        <el-form-item label="360 测评人">
          <div class="rater360-block">
            <template v-if="rater360Required">
              <el-table :data="rater360Rows" size="small" border class="rater360-table">
                <el-table-column prop="categoryName" label="类别" width="140">
                  <template #default="{ row }">{{ row.categoryName || '—' }}</template>
                </el-table-column>
                <el-table-column prop="itemName" label="能力项" min-width="180" />
                <!--
                  评价主体（2026-11-24 取代「权重」列）：这一项该问谁，来自岗位模型里
                  「评价主体」那一列的原文 —— 「内部客户360」和「跨部门360」不是一回事，
                  页面必须照抄原文。原文为空（手工新增/老数据）显示「—」，不按
                  rater_leader/rater_360 反推：反推出来的「360 测评人」看着像原文，其实是系统编的。
                -->
                <el-table-column label="评价主体" width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.raterText || '—' }}</template>
                </el-table-column>
                <el-table-column label="测评人" min-width="420">
                  <template #default="{ row }">
                    <el-select
                      v-model="row.resourceIds"
                      multiple
                      filterable
                      remote
                      :reserve-keyword="false"
                      collapse-tags
                      collapse-tags-tooltip
                      :max-collapse-tags="2"
                      :remote-method="searchRaters"
                      :loading="raterLoading"
                      :disabled="!form.year"
                      :placeholder="`为「${row.itemName}」选择测评人`"
                      :no-data-text="raterNoDataText"
                      style="width: 100%"
                    >
                      <el-option
                        v-for="r in optionsForRow(row)"
                        :key="r.id"
                        :label="formatPerson(r)"
                        :value="r.id"
                      />
                      <template #empty>
                        <div class="select-empty-hint">{{ raterNoDataText }}</div>
                      </template>
                    </el-select>
                  </template>
                </el-table-column>
              </el-table>

              <div class="rater360-summary">
                <span class="form-hint">
                  共 {{ rater360Rows.length }} 个能力项需要 360 评价，
                  当前已给 {{ filledItemCount }} 项选了人，涉及 {{ distinctRaterCount }} 位测评人
                </span>
                <span v-if="missingItemNames.length" class="form-hint form-hint-warn">
                  还有 {{ missingItemNames.length }} 项没选人：{{ missingItemNames.join('、') }}
                </span>
              </div>
            </template>
            <span v-else class="rater360-empty-hint">
              所选岗位没有需要 360 评价的能力项，可直接发起
            </span>
          </div>
        </el-form-item>

        <!--
          备注输入框 2026-11-24 撤：手工发起时几乎没人填，填了也只有点开「任务详情」才看得到
          （任务列表页不显示这一列），占一行表单反而让人多判断一次。
          后端字段与展示全部保留 —— 导入能力模型后自动重发任务时，后端会自己写一条
          「能力模型导入后自动重新发起（原任务 #N）」，那是 HR 分辨前后任务的唯一线索，
          删字段会把这条链路一起废掉。要填备注的场景以后走任务详情页补。
        -->

        <el-form-item>
          <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="submit">
            发起测评
          </el-button>
          <!-- 重新发起时按钮文案要说清楚这次发的是新的一条，不是把旧任务复活 -->
          <span v-if="relaunchTaskId" class="form-hint">
            将新建一条任务，原任务 #{{ relaunchTaskId }} 保持「已撤回」
          </span>
          <span v-if="leader && !leader.leaderResourceId" class="form-hint form-hint-warn">
            没有直接上级无法发起
          </span>
          <span v-else-if="post && !post.postId" class="form-hint form-hint-warn">
            {{ post.message || '请选择岗位' }}
          </span>
          <span v-else-if="rater360Required && missingItemNames.length" class="form-hint form-hint-warn">
            还有 {{ missingItemNames.length }} 个 360 能力项没选测评人
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

    <!-- 发起结果：每人一条专属链接（手工验证用；正式使用走消息推送） -->
    <el-card v-if="result" class="result-card">
      <template #header>
        <div class="result-head">
          <span>
            任务已发起（任务号 {{ result.taskId }}，能力项 {{ result.itemCount }} 个，
            360 测评人 {{ result.rater360Count }} 位）
          </span>
          <el-button link type="primary" @click="copyAll">复制全部链接</el-button>
        </div>
      </template>
      <el-table :data="result.raters" border>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">{{ RATER_TYPE_NAMES[row.raterType] || row.raterType }}</template>
        </el-table-column>
        <el-table-column prop="raterName" label="评价人" width="140" />
        <el-table-column label="待评项数" width="110" align="right">
          <template #default="{ row }">{{ row.itemCount == null ? '全部项' : `${row.itemCount} 项` }}</template>
        </el-table-column>
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
        <el-button type="primary" @click="router.push('/pa/assess-task-manage')">查看任务进度</el-button>
        <el-button @click="startAnother">再发起一个</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createPaTask,
  getPaLeader,
  getPaPost,
  getPaRelaunchDraft,
  precheckPaTask,
  relaunchPaTask,
} from '@/api/paTask'
import { listPeriods } from '@/api/assessPeriod'
import { listPaPosts } from '@/api/paPost'
import { getCurrentDimensionScope } from '@/api/assessDimension'
import request from '@/utils/request'
import type {
  PaLeaderInfo,
  PaPostInfo,
  PaPrecheckResult,
  PaRelaunchDraft,
  PaRelaunchPerson,
  PaTaskCreateResult,
} from '@/types/paTask'
import type { PaPostVO } from '@/types/paPost'
import type { PeriodItem } from '@/types/common'

interface ResourceOption {
  id: number
  lastname: string
  /** 泛微部门 ID：被测人必须与所选岗位同部门（后端 ERR_POST_DEPT_MISMATCH 兜底） */
  departmentId?: number
  departmentName?: string
  companyName?: string
}

const RATER_TYPE_NAMES: Record<string, string> = { SELF: '自评', LEADER: '直接上级', RATER360: '360 测评人' }

const router = useRouter()
const route = useRoute()

/**
 * 重新发起：从列表页带过来的原任务号（`?fromTask=123`）。
 *
 * <p>有它时页面在加载完之后去读预填数据，把原任务的年度/被测人/岗位/360 名单摆出来；
 * 没有它就是普通的「发起测评」，行为与从前完全一致。
 * 真正提交时走 `POST /{id}/relaunch`，让后端回填「旧任务被谁替代」。</p>
 */
const relaunchTaskId = ref<number | null>(null)
/** 原配置里带不出来的部分，后端用中文说明，原样展示 */
const relaunchWarnings = ref<string[]>([])
/** 原任务已被重新发起过时的新任务号 */
const relaunchReplacedBy = ref<number | null>(null)

const relaunchDescription = computed(() => {
  const base = '下面已按原任务填好，可直接发起；要改哪一项都可以改。'
  return relaunchReplacedBy.value
    ? `${base}注意：这条任务此前已经被重新发起过一次（新任务 #${relaunchReplacedBy.value}）。`
    : base
})

const periods = ref<PeriodItem[]>([])

/** 一个 360 能力项上的选人行（发起页表格的一行） */
interface Rater360Row {
  itemId: number
  itemName: string
  categoryName: string | null
  /** 评价主体说明原文（岗位模型里的写法）；没填为 null，页面显示「—」 */
  raterText: string | null
  resourceIds: number[]
}

const form = reactive({
  year: null as number | null,
  resourceId: null as number | null,
  postId: null as number | null,
})

/** 逐项选人的结果：选完岗位后按岗位的 360 项铺开（2026-11-23） */
const rater360Rows = ref<Rater360Row[]>([])

const resourceOptions = ref<ResourceOption[]>([])
const raterOptions = ref<ResourceOption[]>([])
const resourceLoading = ref(false)
const raterLoading = ref(false)
/** 360 下拉当前的搜索词：空的时候不能显示「没有匹配的人员」——那还没搜过 */
const raterKeyword = ref('')

/** 直接上级：只读展示，取值来自被测人档案（OA 组织关系） */
const leader = ref<PaLeaderInfo | null>(null)
const leaderLoading = ref(false)

/** 可选岗位（当前维度启用中的），发起页当场选 */
const postOptions = ref<PaPostVO[]>([])
const postListLoading = ref(false)
const postListLoaded = ref(false)

/** 所选岗位的能力模型信息：决定「360 测评人」是否必填 */
const post = ref<PaPostInfo | null>(null)
const postLoading = ref(false)

const creating = ref(false)
const precheck = ref<PaPrecheckResult | null>(null)
const result = ref<PaTaskCreateResult | null>(null)

const openPeriods = computed(() => periods.value.filter(p => p.status === 0))

const leaderDisplay = computed(() => {
  const info = leader.value
  if (!info || !info.leaderResourceId) {
    return ''
  }
  return info.deptName ? `${info.leaderName}（${info.deptName}）` : info.leaderName || ''
})

/**
 * 「360 测评人」必填与否由岗位模型决定（口径 #9：前端必填、后端不硬拦）：
 * 岗位里一个配了 360 主体的能力项都没有时，选 360 是没有意义的多余动作。
 */
const rater360Required = computed(() => (post.value?.rater360ItemCount ?? 0) > 0)

/** 已经选了人的 360 项数 */
const filledItemCount = computed(() => rater360Rows.value.filter(r => r.resourceIds.length > 0).length)

/** 一个没选人的 360 项名：发起前必须清零，否则后端要按项校验、必然退回 */
const missingItemNames = computed(() =>
  rater360Rows.value.filter(r => r.resourceIds.length === 0).map(r => r.itemName)
)

/** 本次涉及多少位不同的测评人（同一人可评多项，只发一条链接） */
const distinctRaterCount = computed(() => {
  const set = new Set<number>()
  rater360Rows.value.forEach(r => r.resourceIds.forEach(id => set.add(id)))
  return set.size
})

/** 上级未带出时不允许发起：后端同样会拒，这里提前拦住，省一轮报错 */
const canSubmit = computed(() => {
  if (!form.year || !form.resourceId) {
    return false
  }
  if (!leader.value?.leaderResourceId) {
    return false
  }
  if (!form.postId) {
    return false
  }
  if (post.value && !post.value.postId) {
    return false
  }
  if (rater360Required.value && missingItemNames.value.length > 0) {
    return false
  }
  return true
})

/**
 * 被测人的候选范围 = 有启用岗位的部门（2026-11-22），存在通用岗位时放宽到维度授权全部部门。
 *
 * 发起一次测评，本质是「拿这套尺子评这个人」。只配了 A 部门的岗位时，
 * 从全量 2307 人里挑人只会挑出 B 部门的人，然后按 A 部门的模型算分 —— 报告上部门与岗位
 * 对不上，等级也没有业务含义。所以候选先按部门收窄，岗位下拉再跟着所选人的部门收窄。
 *
 * 但「全公司通用」岗位（sql/63）不属于任何部门，它的候选人范围就是维度授权的全部部门 ——
 * 否则一个通用岗位在「有岗位的部门」里一个都匹配不上，候选人被清空，通用岗位等于用不了。
 */
const postDeptIds = computed(() =>
  Array.from(new Set(
    postOptions.value
      .map(p => p.departmentId)
      // 全公司通用岗位的 departmentId 是 null（不绑定任何部门）：
      // 放进「可选人部门」集合只会让 filter 永远匹配不到人，把候选人清空。
      .filter((id): id is number => id != null),
  )),
)

/** 是否有启用中的通用岗位：有的话候选人不再按「有岗位的部门」收窄 */
const hasGenericPost = computed(() => postOptions.value.some(p => p.departmentId == null))

/**
 * 本维度授权的部门（来自「维度设置 → 授权分部」）。
 *
 * 一座岗位只要部门还留在维度里就还能用，但人选不能越出维度的组织范围 ——
 * 授权分部之外的部门里的人，本来就进不了这个维度，评了也看不到自己的报告。
 */
const scopeDeptIds = ref<number[] | null>(null)

/**
 * 被测人候选部门 = 有启用岗位的部门 ∩ 维度授权部门；
 * 存在全公司通用岗位时，不再按「有岗位的部门」收窄，直接用维度授权部门（不限则不限）。
 *
 * 取交集而不是只用授权部门：岗位是发起环节的必填项，人所在的部门没有岗位，
 * 选完人岗位下拉就是空的，等于走到死路；反过来只用岗位部门也不够 ——
 * 岗位是存量数据，可能是在维度收窄之前建到外分部去的，那正是一条越权入口。
 *
 * 通用岗位把「没岗位的部门」这条死路解开了：任何部门的人都能用通用岗位发起，
 * 所以候选人放宽到整个授权范围。没有通用岗位时行为与之前完全一致。
 */
const targetDeptIds = computed(() => {
  if (hasGenericPost.value) {
    // scopeDeptIds 为 null 表示本维度不限分部；这时也不能返回空数组（会被当成「没有部门」发出去）
    return scopeDeptIds.value ?? []
  }
  const postDepts = postDeptIds.value
  if (scopeDeptIds.value == null) {
    return postDepts
  }
  const allowed = new Set(scopeDeptIds.value)
  return postDepts.filter(id => allowed.has(id))
})

/** 所选被测人的部门（选人时从候选项里取，候选项会随搜索变化，必须存下来） */
const targetDeptId = ref<number | null>(null)

/**
 * 岗位候选：这个部门的岗位 + 全部通用岗位；没选人时不出（禁用）。
 *
 * 通用岗位排后面：同一个岗位名如果本部门也建了一份，用户多半想用的是本部门那份。
 */
const postChoices = computed(() => {
  if (targetDeptId.value == null) {
    return []
  }
  const own = postOptions.value.filter(p => p.departmentId === targetDeptId.value)
  const generic = postOptions.value.filter(p => p.departmentId == null)
  return [...own, ...generic]
})

const resourcePlaceholder = computed(() => {
  if (!postListLoaded.value) {
    return '正在读取岗位配置…'
  }
  // 有通用岗位时哪个部门的人都能发起，不再说「限已配置岗位的部门」
  if (hasGenericPost.value) {
    return scopeDeptIds.value == null ? '输入姓名搜索' : '输入姓名搜索（限本维度授权的分部）'
  }
  return targetDeptIds.value.length ? '输入姓名搜索（限已配置岗位的部门）' : '当前没有启用中的岗位，无法选人'
})

const postPlaceholder = computed(() => {
  if (!form.year) {
    return '请先选择年度'
  }
  if (!form.resourceId) {
    return '请先选择被测人'
  }
  if (postChoices.value.length) {
    return '选择该人员本次测评使用的岗位'
  }
  return hasGenericPost.value ? '当前没有启用中的岗位' : '该人员所在部门没有启用中的岗位'
})

const loadPeriods = async () => {
  periods.value = await listPeriods()
  if (form.year == null && openPeriods.value.length > 0) {
    form.year = openPeriods.value[0].year
  }
}

/** 岗位下拉：只取当前维度启用中的岗位，停用的不应再出现在发起页 */
const loadPosts = async () => {
  postListLoading.value = true
  try {
    postOptions.value = await listPaPosts()
  } catch {
    postOptions.value = []
  } finally {
    postListLoading.value = false
    postListLoaded.value = true
  }
}

const clearLeader = () => {
  leader.value = null
}

/**
 * 清空逐项选人的结果。
 *
 * <p>不保留「上次给谁选过」是刻意的：换岗位后能力项 ID 全变了，
 * 留着上一套的行只会让人以为选过、实际提交的是空。</p>
 */
const resetRater360 = () => {
  rater360Rows.value = []
}

const onYearChange = () => {
  form.resourceId = null
  form.postId = null
  resetRater360()
  resourceOptions.value = []
  targetDeptId.value = null
  clearLeader()
  post.value = null
}

/**
 * 换人要把岗位与 360 名单一起清掉。岗位是「这次评谁、按哪套尺子」的一部分，
 * 留着上一个人的岗位，很容易在下一个人身上直接点「发起测评」而没注意岗位没换 ——
 * 评错尺子的代价远大于重新选一次下拉。
 *
 * 部门也要一起换：岗位候选按部门收窄，上一个人的部门留着会让下拉列出别人部门的岗位。
 */
const onTargetChange = async (resourceId: number | null) => {
  clearLeader()
  post.value = null
  form.postId = null
  resetRater360()
  targetDeptId.value = resourceOptions.value.find(r => r.id === resourceId)?.departmentId ?? null
  if (!resourceId) {
    return
  }
  leaderLoading.value = true
  try {
    leader.value = await getPaLeader(resourceId)
  } catch {
    clearLeader()
  } finally {
    leaderLoading.value = false
  }
}

/**
 * 选了岗位才去取能力模型：决定「360 测评人」是否必填、权重合计够不够 100%。
 *
 * <p>拿到岗位的 360 项后按项铺开表格（2026-11-23）：每项一行、各自选人。
 * 换岗位必须重铺，项 ID 变了，旧行留着会提交到不存在的项上。</p>
 */
const onPostChange = async (postId: number | null) => {
  resetRater360()
  post.value = null
  if (!postId) {
    return
  }
  postLoading.value = true
  try {
    post.value = await getPaPost(postId)
  } catch {
    post.value = null
  } finally {
    postLoading.value = false
  }
  rater360Rows.value = (post.value?.rater360Items ?? []).map(item => ({
    itemId: item.itemId,
    itemName: item.itemName,
    categoryName: item.categoryName,
    raterText: item.raterText ?? null,
    resourceIds: [],
  }))
}

/**
 * 人员搜索：复用 /search/resources（支持姓名与拼音，已带部门名与公司名）。
 *
 * 被测人只搜「有启用岗位的部门 ∩ 维度授权部门」（departmentIds，2026-11-22 起，
 * 2026-11-23 加上授权范围）——发起测评是「用这个部门的尺子评这个部门的人」，
 * 候选不该越过岗位配置的范围，更不该越过维度的组织范围。
 * 360 测评人不传部门：跨部门互评本来就是 360 的语义，收窄反而把有用的评价人挡在外面。
 *
 * 空关键词一律清空列表、不发请求：两个下拉都是「输入才出人」。
 * （原先把「选完一个自动清空搜索词」也当成搜索信号补一页默认名单，
 * 那页人跟本次测评无关，纯噪音，2026-11-23 一并去掉。）
 */

/**
 * 搜到过的人（id → 候选行），只用于「套用到全部项」之后把名字显示出来。
 *
 * <p>Element Plus 的 remote 下拉只渲染当次搜索结果；一次套用会往十几行里写入一批
 * 从未在该行下拉里出现过的 id，Vue 拿不到对应 option，标签就会退化成裸 ID。
 * 所以把搜到过的人记下来，渲染时把这几个补进该行的候选。</p>
 */
const knownPeople = ref(new Map<number, ResourceOption>())

const rememberPeople = (list: ResourceOption[]) => {
  list.forEach(r => knownPeople.value.set(r.id, r))
}

const searchPeople = async (
  keyword: string,
  target: typeof resourceOptions,
  loading: typeof resourceLoading,
  options: { departmentIds?: number[] } = {}
) => {
  const kw = keyword?.trim() ?? ''
  if (!kw) {
    target.value = []
    return
  }
  loading.value = true
  try {
    const params: Record<string, string | number> = {
      keyword: kw,
      pageNum: 1,
      pageSize: 30,
    }
    // 数组按 Spring 的 List<Long> 绑定格式传（逗号分隔），axios 默认序列化成 a[]=1&a[]=2 收不到
    if (options.departmentIds?.length) {
      params.departmentIds = options.departmentIds.join(',')
    }
    const page = await request.get<{
      list: {
        id: number
        lastname: string
        departmentid?: number
        departmentName?: string
        companyName?: string
      }[]
    }>('/search/resources', { params })
    target.value = page.list.map(r => ({
      id: r.id,
      lastname: r.lastname,
      departmentId: r.departmentid || undefined,
      departmentName: r.departmentName || undefined,
      companyName: r.companyName || undefined,
    }))
    // 搜到过就记住：remote 下拉只渲染当次结果，跨行套用时要靠这份缓存把名字显示出来
    rememberPeople(target.value)
  } catch {
    target.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 下拉里的人员展示：姓名（公司·部门）。同名的人靠括号部分区分，公司必须有 ——
 * 光看部门不够，「财务部」「销售部」这类名字在多家子公司都存在。两者都缺时退回纯姓名。
 */
const formatPerson = (r: ResourceOption) => {
  const org = [r.companyName, r.departmentName].filter(Boolean).join('·')
  return org ? `${r.lastname}（${org}）` : r.lastname
}

const searchResources = (kw: string) =>
  searchPeople(kw, resourceOptions, resourceLoading, { departmentIds: targetDeptIds.value })

/**
 * 360 测评人不传部门、也不传 allowEmpty：跨部门互评本就是 360 的语义，
 * 而预拉一页「按姓名排序的前 30 人」跟本次测评毫无关系，纯噪音 ——
 * 和测评报告的搜索框一样，输入姓名/拼音才出人。
 */
const searchRaters = (kw: string) => {
  raterKeyword.value = kw?.trim() ?? ''
  searchPeople(kw, raterOptions, raterLoading)
}

/**
 * 某一行的候选：当次搜索结果 + 该行已选中但搜不到的人。
 *
 * <p>补后者是因为 Element Plus 的 remote 下拉只渲染当次结果：切到另一行搜索、
 * 再切回来时，这一行选中的人不在新的结果集里，缺了这步标签会退化成裸 ID。
 * 用 Set 去重，免得同一个人被塞两遍产生重复 key。</p>
 */
const optionsForRow = (row: Rater360Row): ResourceOption[] => {
  const list = [...raterOptions.value]
  const seen = new Set(list.map(r => r.id))
  for (const id of row.resourceIds) {
    const person = knownPeople.value.get(id)
    if (person && !seen.has(id)) {
      list.push(person)
      seen.add(id)
    }
  }
  return list
}

/** 空关键词时下拉里的提示语：没搜过就说「输入姓名搜索」，别报「没有匹配的人员」 */
const raterNoDataText = computed(() =>
  raterKeyword.value ? '没有匹配的人员，换个姓名或拼音试试' : '输入姓名或拼音搜索'
)

/** 被测人下拉同理：没搜过时不该说「没有匹配的人员」 */
const resourceEmptyText = computed(() =>
  targetDeptIds.value.length ? '输入姓名或拼音搜索' : '当前维度没有可选的部门，无法选人'
)

const fillUrl = (token: string) => `${window.location.origin}/pa/assess-fill/${token}`

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
  if (!leader.value?.leaderResourceId) {
    ElMessage.warning(leader.value?.message || '该人员没有直接上级，无法发起')
    return
  }
  if (!form.postId) {
    ElMessage.warning('请选择岗位')
    return
  }
  if (post.value && !post.value.postId) {
    ElMessage.warning(post.value.message || '所选岗位不可用，请重新选择')
    return
  }
  // 前端先拦一道：后端同样会拒（40308），但让用户在这里看到原因，不用等一次失败请求。
  // 通用岗位没有部门（departmentId 为 null），部门一致性与它无关，跳过（sql/63）。
  if (post.value?.departmentId != null && targetDeptId.value != null
      && post.value.departmentId !== targetDeptId.value) {
    ElMessage.warning('被测人与所选岗位不属于同一部门，请重新选择岗位')
    return
  }
  if (rater360Required.value && missingItemNames.value.length > 0) {
    ElMessage.warning(
      `还有 ${missingItemNames.value.length} 个能力项没选测评人：${missingItemNames.value.join('、')}`
    )
    return
  }
  creating.value = true
  try {
    const items = rater360ItemsPayload()
    // 一人可评多项，预检只问「这些人能不能登录」，与项无关 —— 传去重后的扁平名单。
    // （GET 的查询串也传不了嵌套对象，后端 PrecheckRequest 因此只收 List<Long>。）
    const allRaterIds = Array.from(new Set(items.flatMap(i => i.resourceIds)))

    // 先预检：一次列出全部登不进系统的人。有问题就停下来让 HR 改，
    // 不把「注定发死链」的任务发出去。
    precheck.value = await precheckPaTask({
      year: form.year,
      resourceId: form.resourceId,
      rater360ResourceIds: allRaterIds,
    })
    if (!precheck.value.ok) {
      ElMessage.error('有人员无法登录系统，请按下方清单处理后重试')
      return
    }

    const payload = {
      year: form.year,
      resourceId: form.resourceId,
      postId: form.postId,
      rater360Items: items,
      // 不传 remark：发起页已撤掉输入框（2026-11-24），后端收到 null 就不写。
      // 自动重发那条备注由后端自己写，与这里无关。
    }
    // 重新发起走 /{id}/relaunch：它复用同一套 create 校验，另外回填「旧任务被谁替代」，
    // 列表上才看得出前后两条是一回事（否则旧行状态 4、新行状态 0，像两条无关的任务）。
    const taskId = relaunchTaskId.value
    result.value = taskId
      ? await relaunchPaTask(taskId, payload)
      : await createPaTask(payload)
    ElMessage.success(
      allRaterIds.length
        ? `任务已发起，${allRaterIds.length} 位 360 测评人分别收到自己的链接`
        : '任务已发起'
    )
    // 发出去之后这条横幅就没意义了：再说「按任务 #N 重新发起」会让人以为又要发一次
    relaunchTaskId.value = null
    relaunchWarnings.value = []
    relaunchReplacedBy.value = null
    // 地址上的 fromTask 也要抹掉：留着它，刷新一下又会按原任务重新预填一遍，
    // 而那条任务此刻已经重新发起过了，看起来就像「没发成功」
    if (route.query.fromTask) {
      void router.replace({ path: '/pa/assess-tasks' })
    }
  } finally {
    creating.value = false
  }
}

/** 提交载荷：只带选了人的项，空行不上送（后端对没选人的项另有提示） */
const rater360ItemsPayload = () =>
  rater360Rows.value
    .filter(r => r.resourceIds.length > 0)
    .map(r => ({ itemId: r.itemId, resourceIds: [...r.resourceIds] }))

/** 再发起一个：只清掉本次发起的结果，年度保留（同一批人通常在同一年度里连续发起） */
const startAnother = () => {
  result.value = null
  precheck.value = null
  form.resourceId = null
  form.postId = null
  resourceOptions.value = []
  raterOptions.value = []
  targetDeptId.value = null
  clearLeader()
  post.value = null
  resetRater360()
}

/**
 * 拉本维度授权的部门，用于把被测人候选收窄到维度的组织范围内。
 * 失败时保持 null（= 不额外收窄），仍按「有岗位的部门」走 —— 这只是收紧一道口子，
 * 拉不到不该让人发不了测评。真正的越权拦截在后端。
 */
const loadScope = async () => {
  try {
    const scope = await getCurrentDimensionScope()
    scopeDeptIds.value = scope.restricted ? scope.departmentIds : null
  } catch {
    scopeDeptIds.value = null
  }
}

/**
 * 重新发起：把原任务当初的配置回填到表单（2026-11-25）。
 *
 * <p><b>回填顺序不能乱</b>：先年度 → 再被测人（会带出上级、定下部门）→ 再岗位
 * （岗位候选正是按部门收窄的）→ 最后逐项 360。反过来做，中途每一个 on*Change
 * 都会把刚填好的值清掉（这是那几处「换人清岗位」的保护逻辑在起作用，
 * 不该为了回填去改它）。</p>
 *
 * <p><b>带不出来的不猜</b>：年度已关闭 / 岗位已停用时后端不回这两个值，
 * 这里就留空让人重选 —— 猜一个值填上去，用户会以为原样重发成功。</p>
 */
const loadRelaunchDraft = async (taskId: number) => {
  let draft: PaRelaunchDraft
  try {
    draft = await getPaRelaunchDraft(taskId)
  } catch {
    // 接口层已弹过提示（跨维度 / 非已撤回 / 不存在），这里把参数抹掉，
    // 免得用户刷新后还挂着一个读不出来的 fromTask
    void router.replace({ path: '/pa/assess-tasks' })
    return
  }
  relaunchTaskId.value = taskId
  relaunchWarnings.value = draft.warnings ?? []
  relaunchReplacedBy.value = draft.replacedByTaskId ?? null

  // 人名缓存：remote 下拉只渲染当次搜索结果，预填进来的人不在结果里，
  // 不塞进 knownPeople 的话标签会显示成裸数字
  const rememberDraftPeople = (people: PaRelaunchPerson[] | null | undefined) => {
    (people ?? []).forEach(p => {
      if (!p.name) {
        return
      }
      knownPeople.value.set(p.resourceId, {
        id: p.resourceId,
        lastname: p.name,
        departmentId: p.departmentId ?? undefined,
        departmentName: p.departmentName ?? undefined,
        companyName: p.companyName ?? undefined,
      })
    })
  }
  rememberDraftPeople([draft.target].filter((p): p is PaRelaunchPerson => p != null))
  rememberDraftPeople(draft.rater360People)

  // ① 年度：后端只在不关闭时给值
  if (draft.year != null) {
    form.year = draft.year
  }

  // ② 被测人：先把候选塞进去，再赋值并走一遍 onTargetChange（带出上级、定下部门）
  if (draft.target) {
    const target: ResourceOption = {
      id: draft.target.resourceId,
      lastname: draft.target.name ?? draft.targetName ?? String(draft.resourceId),
      departmentId: draft.target.departmentId ?? undefined,
      departmentName: draft.target.departmentName ?? undefined,
      companyName: draft.target.companyName ?? undefined,
    }
    resourceOptions.value = [target]
    form.resourceId = target.id
    await onTargetChange(target.id)
  }

  // ③ 岗位：停用/删除的岗位后端不给值，这里就不选
  if (draft.postId != null) {
    // 用 postChoices（页面真正列出来的候选）判断，而不是 postOptions：
    // 岗位还在、但被测人所属部门已经变了时，它不会出现在这个部门的候选里，
    // 直接赋上去下拉只会显示一个裸 ID，提交也会被后端以「岗位与部门不一致」拒绝。
    if (!postChoices.value.some(p => p.id === draft.postId)) {
      relaunchWarnings.value = [
        ...relaunchWarnings.value,
        `原任务的岗位「${draft.postName ?? draft.postId}」已不在可选范围（岗位已停用，或被测人现在所属部门与该岗位的部门不一致），请重新选择`,
      ]
    } else {
      form.postId = draft.postId
      // onPostChange 会按岗位重铺 360 行（并清空名单），所以必须等它跑完再填人
      await onPostChange(draft.postId)
    }
  }

  // ④ 逐项 360：按项 ID 把人填回对应的行；项已经不在页面上的不再处理（后端已说明）
  const byItem = new Map((draft.rater360Items ?? []).map(i => [i.itemId, i]))
  rater360Rows.value = rater360Rows.value.map(row => {
    const hit = byItem.get(row.itemId)
    return hit ? { ...row, resourceIds: hit.people.map(p => p.resourceId) } : row
  })
  if (draft.legacyAllItems && draft.rater360People.length > 0) {
    // 老口径（这批人评全部 360 项）：每一项都勾上同一批人，与当时的行为一致
    const everyone = draft.rater360People.map(p => p.resourceId)
    rater360Rows.value = rater360Rows.value.map(row => ({ ...row, resourceIds: [...everyone] }))
  }
  if (draft.rater360Items?.length) {
    // 预填之后把搜索词清掉：留着上一轮的词会让下拉显示「没有匹配的人员」
    raterKeyword.value = ''
  }
}

onMounted(async () => {
  await Promise.all([loadPeriods(), loadPosts(), loadScope()])
  // 预填要等年度/岗位/授权范围都就绪：岗位候选按部门过滤、人员候选按授权收窄，
  // 提前塞进去的值会被随后的加载覆盖掉。
  const fromTask = Number(route.query.fromTask)
  if (Number.isFinite(fromTask) && fromTask > 0) {
    await loadRelaunchDraft(fromTask)
  }
})
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
  min-width: 320px;
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

/* 远程搜索下拉的空面板提示：和 el-select 自带的 no-data 保持同样的观感 */
.select-empty-hint {
  padding: 10px 0;
  font-size: 14px;
  color: #909399;
  text-align: center;
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

/* 逐项选人（2026-11-23）：一表一行一项，每行自己选人 */
.rater360-block {
  width: 100%;
  max-width: 1000px;
}

.rater360-table {
  width: 100%;
}

.rater360-summary {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 8px;
}

/* 这两处的 form-hint 是整块说明，不是跟在输入框后面，去掉左边距 */
.rater360-summary .form-hint {
  margin-left: 0;
}

.rater360-empty-hint {
  font-size: 13px;
  color: #86909c;
}

/* 重新发起横幅（2026-11-25）：横幅本身说明「此刻在做什么」，带不出来的项列在下面 */
.relaunch-alert {
  margin-bottom: 18px;
}

.relaunch-warnings {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}
</style>
