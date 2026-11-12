<template>
  <div class="review">
    <!-- 顶部：批次与统计 -->
    <div class="head">
      <div class="head-main">
        <b>能力结构审核</b>
        <el-tag size="small" :type="headTag.type">{{ headTag.text }}</el-tag>
        <span class="meta">{{ payload.categories?.length || 0 }} 类别 · {{ payload.elements?.length || 0 }} 要素 ·
          {{ levelCount }} 等级 · {{ payload.positionReqs?.length || 0 }} 岗位 · {{ payload.userReqs?.length || 0 }} 人员</span>
      </div>
      <div class="head-actions">
        <el-button size="small" plain @click="emit('cancel')">取消</el-button>
        <el-button type="primary" :loading="committing" @click="emit('confirm')">
          确认写入{{ commitable ? '' : '（有阻断项，不可写入）' }}
        </el-button>
      </div>
    </div>

    <!-- 阻断/待确认横幅：必须显式提示，否则用户以为全导进去了 -->
    <el-alert v-if="counts.blocked > 0" type="error" :closable="false" show-icon class="banner">
      有 {{ counts.blocked }} 项阻断，整批不会写入。请修正后重新解析。
    </el-alert>
    <el-alert v-else-if="pendingCount > 0" type="warning" :closable="false" show-icon class="banner">
      有 {{ pendingCount }} 项待确认（部门/岗位/人员对不上），<b>这些行不会写入</b>，其余照常导入。
      可在对应页签点「指定匹配」，把文件写法绑到系统里的部门/岗位，确认后记住、刷新即可生效。
    </el-alert>

    <!-- 五页签 -->
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane v-for="t in SECTION_TABS" :key="t.key" :name="t.key">
        <template #label>
          {{ t.label }}<span class="badge" :class="badgeClass(t.key)">{{ grouped[t.key].length }}</span>
        </template>
        <el-table :data="grouped[t.key]" size="small" border max-height="380" empty-text="无内容">
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="tagOf(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
          <el-table-column label="说明" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="t.key === 'position' || t.key === 'user'" label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING'" size="small" text type="primary"
                         @click="openMatch(row)">指定匹配</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane name="skipped">
        <template #label>排除行<span class="badge">{{ skippedRows.length }}</span></template>
        <el-table :data="skippedRows" size="small" border max-height="380" empty-text="无排除行">
          <el-table-column prop="rowNo" label="位置" width="200" show-overflow-tooltip />
          <el-table-column label="原文" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.raw || '—' }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 指定匹配弹窗 -->
    <el-dialog v-model="matchOpen" title="指定系统实体" width="460px">
      <div class="match-src">文件写法：<b>{{ matchRow?.name }}</b></div>
      <el-select v-model="matchKind" class="match-kind" @change="onKindChange">
        <el-option label="部门" value="DEPARTMENT" />
        <el-option label="岗位" value="JOB_TITLE" />
      </el-select>
      <el-select v-model="matchKeyword" filterable remote :remote-method="searchTargets"
                 :loading="searching" placeholder="输入关键字搜索" class="match-input">
        <el-option v-for="o in matchOptions" :key="o.id" :label="o.label" :value="o.id" />
      </el-select>
      <div class="match-hint">确认后会记住这条对应关系，同写法的文件下次自动匹配。</div>
      <template #footer>
        <el-button size="small" @click="matchOpen = false">取消</el-button>
        <el-button size="small" type="primary" :disabled="!matchTargetId" @click="saveMatch">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import {
  SECTION_TABS, groupLines, confirmAliases,
  type StructureCounts, type StructureLine, type StructurePayload, type StructureTab,
} from '@/api/structureImport'

const props = defineProps<{
  payload: StructurePayload
  lines: StructureLine[]
  counts: StructureCounts
  skippedRows: { rowNo: string; raw?: string | null; reason: string }[]
  commitable: boolean
  committing: boolean
}>()
const emit = defineEmits<{ (e: 'confirm'): void; (e: 'cancel'): void; (e: 'refresh'): void }>()

const tab = ref<StructureTab>('category')
const grouped = computed(() => groupLines(props.lines))
const levelCount = computed(() => (props.payload.elements || []).reduce((n, e) => n + (e.levels?.length || 0), 0))
/** counts.unresolved 已包含全部 PENDING 行（岗位/人员/要素未匹配），不要再叠加 userPending */
const pendingCount = computed(() => props.counts.unresolved || 0)

const headTag = computed(() => props.counts.blocked > 0
  ? { text: '有阻断项', type: 'danger' as const }
  : pendingCount.value > 0 ? { text: '部分待确认', type: 'warning' as const }
    : { text: '校验通过', type: 'success' as const })

function badgeClass(key: StructureTab): string {
  return grouped.value[key].some(l => l.status === 'BLOCK') ? 'badge-danger'
    : grouped.value[key].some(l => l.status === 'PENDING') ? 'badge-warn' : ''
}

function tagOf(s: string): 'success' | 'info' | 'warning' | 'danger' {
  return s === 'CREATE' ? 'success' : s === 'SKIP' ? 'info' : s === 'PENDING' ? 'warning' : 'danger'
}
function statusText(s: string): string {
  return s === 'CREATE' ? '新增' : s === 'SKIP' ? '已存在跳过' : s === 'PENDING' ? '待确认' : s === 'BLOCK' ? '阻断' : s
}

// ---------- 指定匹配（别名沉淀） ----------
const matchOpen = ref(false)
const matchRow = ref<StructureLine | null>(null)
const matchKind = ref<'DEPARTMENT' | 'JOB_TITLE'>('DEPARTMENT')
const matchKeyword = ref('')
const matchTargetId = ref<number | null>(null)
const matchTargetName = ref('')
const matchOptions = ref<{ id: number; label: string }[]>([])
const searching = ref(false)

function openMatch(row: StructureLine): void {
  matchRow.value = row
  matchKind.value = row.section === 'USER' ? 'JOB_TITLE' : 'DEPARTMENT'
  matchKeyword.value = ''; matchTargetId.value = null; matchOptions.value = []
  matchOpen.value = true
}
function onKindChange(): void {
  matchKeyword.value = ''; matchTargetId.value = null; matchOptions.value = []
}

async function searchTargets(kw: string): Promise<void> {
  if (!kw || kw.trim().length === 0) { matchOptions.value = []; return }
  searching.value = true
  try {
    const path = matchKind.value === 'DEPARTMENT' ? '/search/departments' : '/search/job-titles'
    const page = await request.get(path, { params: { keyword: kw, pageNum: 1, pageSize: 20 } }) as unknown as
      { list: Record<string, unknown>[] }
    matchOptions.value = (page.list || []).map(r => ({
      id: Number(r.id),
      label: String(r.departmentmark ?? r.jobtitlemark ?? r.id),
    }))
  } catch {
    matchOptions.value = []
  } finally {
    searching.value = false
  }
}

async function saveMatch(): Promise<void> {
  if (!matchRow.value || !matchTargetId.value) return
  const chosen = matchOptions.value.find(o => o.id === matchTargetId.value)
  matchTargetName.value = chosen?.label || String(matchTargetId.value)
  // 岗位行的 name 形如「部门 · 岗位」，取后半段做别名源名
  const source = matchRow.value.section === 'POSITION'
    ? (matchRow.value.name.split('·').pop() || '').trim() : matchRow.value.name.trim()
  try {
    await confirmAliases([{
      kind: matchKind.value, sourceName: source,
      targetId: matchTargetId.value, targetName: matchTargetName.value,
    }])
    ElMessage.success('已记住这条对应关系')
    matchOpen.value = false
    emit('refresh')
  } catch (e) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    ElMessage.error(err?.response?.data?.message || err?.message || '确认失败')
  }
}
</script>

<style scoped>
.review { margin-top: 6px; }
.head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.head-main { display: flex; align-items: center; gap: 8px; flex: 1; flex-wrap: wrap; }
.meta { color: var(--el-text-color-secondary); font-size: 12px; }
.head-actions { display: flex; gap: 8px; }
.banner { margin-bottom: 8px; }
.tabs { margin-top: 4px; }
.badge { margin-left: 5px; font-size: 11px; background: var(--el-fill-color); border-radius: 8px; padding: 0 5px; }
.badge-warn { background: var(--el-color-warning-light-7); }
.badge-danger { background: var(--el-color-danger-light-7); }
.match-src { margin-bottom: 10px; font-size: 13px; }
.match-kind { width: 130px; margin-right: 8px; }
.match-input { width: 240px; }
.match-hint { margin-top: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
</style>
