<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">维度设置</h1>
        <p class="page-description">维度即租户：岗位、能力模型、人员岗位映射与测评数据按维度隔离；顶栏切换维度</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增维度</el-button>
    </div>

    <!-- 表格 -->
    <div class="card">
      <div class="card-body">
        <el-table :data="dimensionList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="dimensionName" label="维度名称" min-width="150" />
          <el-table-column prop="dimensionCode" label="编码" width="120">
            <template #default="{ row }">
              <span v-if="row.dimensionCode">{{ row.dimensionCode }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
          <el-table-column label="所属分部（授权分部）" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <template v-if="row.subcompanys && row.subcompanys.length">
                <el-tag
                  v-for="sub in row.subcompanys"
                  :key="sub.id"
                  size="small"
                  type="success"
                  effect="plain"
                  class="admin-tag"
                >
                  {{ sub.name || `ID:${sub.id}` }}
                </el-tag>
                <span v-if="row.excludedSubcompanys && row.excludedSubcompanys.length" class="exclude-note">
                  不含
                  <el-tag
                    v-for="sub in row.excludedSubcompanys"
                    :key="sub.id"
                    size="small"
                    type="danger"
                    effect="plain"
                    class="admin-tag"
                  >
                    {{ sub.name || `ID:${sub.id}` }}
                  </el-tag>
                </span>
              </template>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="授权人员（维度管理员）" min-width="180">
            <template #default="{ row }">
              <template v-if="row.persons && row.persons.length">
                <el-tag
                  v-for="person in row.persons"
                  :key="person.id"
                  size="small"
                  type="info"
                  effect="plain"
                  class="admin-tag"
                >
                  {{ person.name || `ID:${person.id}` }}
                </el-tag>
              </template>
              <span v-else class="text-muted">未配置</span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="80">
            <template #default="{ row }">
              <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="可用范围" width="140">
            <template #default="{ row }">
              <el-tag v-if="row.authCount === 0" type="warning" effect="plain">仅系统管理员</el-tag>
              <el-tag v-else type="info" effect="plain">{{ row.authCount }} 个授权对象</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="handleConfigAuth(row)">授权配置</el-button>
              <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="维度名称" prop="dimensionName">
            <el-input v-model="formData.dimensionName" placeholder="如：销售条线" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="维度编码" prop="dimensionCode">
            <el-input v-model="formData.dimensionCode" placeholder="可选，如 DIM-SALES" maxlength="50" />
          </el-form-item>
        </div>
        <el-form-item label="描述" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="3" placeholder="请输入描述信息" />
        </el-form-item>

        <div class="form-grid">
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="formData.status">
              <el-radio-button :value="1">启用</el-radio-button>
              <el-radio-button :value="0">禁用</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <el-input-number v-model="formData.sort" :min="0" :max="999" controls-position="right" class="full-width" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 授权配置对话框：分部 + 人员，合并后这里是维度权限的唯一入口 -->
    <el-dialog
      v-model="authDialogVisible"
      :title="`授权配置 - ${currentDimension?.dimensionName || ''}`"
      width="680px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="在这里统一配置维度的归属与可用范围。未配置任何授权对象时，只有系统管理员能进入该维度。"
        style="margin-bottom: 16px"
      />

      <div class="auth-section">
        <h3 class="form-section-title">授权分部</h3>
        <el-tree-select
          v-model="authSubcompanyIds"
          :data="subCompanyTree"
          multiple
          show-checkbox
          check-strictly
          filterable
          :reserve-keyword="false"
          clearable
          collapse-tags
          collapse-tags-tooltip
          :render-after-expand="false"
          default-expand-all
          node-key="id"
          :props="treeProps"
          placeholder="选择分部（可勾父分部，也可单独勾某一级下级）"
          style="width: 100%"
        />
        <div class="form-hint">
          勾选即代表两件事：该维度归属这几家分部；<b>这些分部及其全部下级分支的人员都能使用</b>这个维度。
        </div>
      </div>

      <div class="auth-section">
        <h3 class="form-section-title">排除分部</h3>
        <el-tree-select
          v-model="authExcludedSubcompanyIds"
          :data="excludableTree"
          :disabled="!authSubcompanyIds.length"
          multiple
          show-checkbox
          check-strictly
          filterable
          :reserve-keyword="false"
          clearable
          collapse-tags
          collapse-tags-tooltip
          :render-after-expand="false"
          default-expand-all
          node-key="id"
          :props="treeProps"
          :placeholder="
            authSubcompanyIds.length
              ? '从授权范围内选择要排除的分部'
              : '请先在上面选择授权分部'
          "
          style="width: 100%"
        />
        <div class="form-hint">
          这里只列出<b>授权范围之内</b>的分部：排除的分部连同它的全部下级都不归本维度。
          例如授权「国际贸易」、排除「中达科技」，中达科技及其下级的人就不再进来。
          <template v-if="!authSubcompanyIds.length">
            当前还没选授权分部，所以没有可排除的对象。
          </template>
        </div>
      </div>

      <div class="auth-section">
        <h3 class="form-section-title">人员</h3>
        <ResourceAutocomplete
          v-model="personPickerId"
          placeholder="搜索并选择人员后自动添加"
          reset-on-select
          @change="handleAddPerson"
        />
        <div v-if="authPersons.length" class="person-tags">
          <el-tag
            v-for="person in authPersons"
            :key="person.authId"
            closable
            type="primary"
            effect="plain"
            @close="removePerson(person.authId)"
          >
            {{ person.authName || `ID:${person.authId}` }}
          </el-tag>
        </div>
        <p v-else class="text-muted">暂未添加任何人员</p>
        <div class="form-hint">
          加进这里的人既是本维度的使用者，也是本维度的管理员：<b>能在测评报告里看到 360 测评人的真实姓名</b>，
          其他人只看到「测评人1 / 测评人2」。维度隔离不变，他们只看得到这个维度的数据。
        </div>
      </div>

      <template #footer>
        <el-button @click="authDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="authSaving" @click="handleSaveAuths">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'
import { listSubCompanyTree, type SubCompanyTreeNode } from '@/api/ecology'
import {
  listAllDimensions,
  createDimension,
  updateDimension,
  deleteDimension,
  listDimensionAuths,
  saveDimensionAuths,
} from '@/api/assessDimension'
import {
  AUTH_TYPE_SUBCOMPANY,
  AUTH_TYPE_EXCLUDED_SUBCOMPANY,
  AUTH_TYPE_RESOURCE,
  type AssessDimensionVO,
  type AssessDimensionAuthVO,
} from '@/types/assessDimension'
import { useDimensionStore } from '@/stores/dimension'

const dimensionList = ref<AssessDimensionVO[]>([])
/**
 * 分部树（2026-11-23 起授权按层级选）。
 * 原来是「只列一级分部」，因为授权等于勾父含子树；现在有了排除项，就必须能选到下级分支，
 * 否则「国际贸易整棵树、中达科技除外」这种配置根本表达不出来。
 */
const subCompanyTree = ref<SubCompanyTreeNode[]>([])
/**
 * el-tree-select 的字段映射。
 *
 * <p>只写 label/children 会把 el-tree 默认 props 里的 disabled 一并覆盖成空，
 * 显式补齐。取值走的是 node-key="id"，不在这里配。</p>
 */
const treeProps = { label: 'name', children: 'children', disabled: 'disabled' }
const dimensionStore = useDimensionStore()

const loadData = async () => {
  try {
    dimensionList.value = await listAllDimensions()
  } catch (error) {
    console.error('加载维度列表失败:', error)
  }
}

const loadSubCompanies = async () => {
  try {
    subCompanyTree.value = await listSubCompanyTree()
  } catch (error) {
    console.error('加载分部树失败:', error)
  }
}

onMounted(() => {
  loadData()
  loadSubCompanies()
})

// ==================== 新增 / 编辑 ====================

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  dimensionName: '',
  dimensionCode: '',
  description: '',
  status: 1,
  sort: 0,
})

const formRules: FormRules = {
  dimensionName: [
    { required: true, message: '请输入维度名称', trigger: 'blur' },
    { max: 100, message: '维度名称长度不能超过100', trigger: 'blur' },
  ],
}

const handleAdd = () => {
  dialogTitle.value = '新增维度'
  Object.assign(formData, {
    id: 0,
    dimensionName: '',
    dimensionCode: '',
    description: '',
    status: 1,
    sort: 0,
  })
  dialogVisible.value = true
}

const handleEdit = (row: AssessDimensionVO) => {
  dialogTitle.value = '编辑维度'
  Object.assign(formData, {
    id: row.id,
    dimensionName: row.dimensionName,
    dimensionCode: row.dimensionCode || '',
    description: row.description || '',
    status: row.status,
    sort: row.sort,
  })
  dialogVisible.value = true
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (formData.id) {
      await updateDimension({
        id: formData.id,
        dimensionName: formData.dimensionName,
        dimensionCode: formData.dimensionCode || undefined,
        description: formData.description || undefined,
        status: formData.status,
        sort: formData.sort,
      })
      ElMessage.success('更新成功')
    } else {
      await createDimension({
        dimensionName: formData.dimensionName,
        dimensionCode: formData.dimensionCode || undefined,
        description: formData.description || undefined,
        status: formData.status,
        sort: formData.sort,
      })
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    await loadData()
    await refreshSwitcher()
  } catch (error) {
    console.error('提交失败:', error)
  }
}

const handleDelete = async (row: AssessDimensionVO) => {
  try {
    await ElMessageBox.confirm(
      `确定删除维度「${row.dimensionName}」吗？删除后该维度下不允许存在业务数据。`,
      '删除确认',
      { type: 'warning' }
    )
    await deleteDimension(row.id)
    ElMessage.success('删除成功')
    await loadData()
    await refreshSwitcher()
  } catch (error) {
    // 用户取消或接口报错（已全局提示）
    console.error('删除失败:', error)
  }
}

/** 维度列表变化后同步顶栏切换器（当前维度可能被删/禁用） */
const refreshSwitcher = async () => {
  const before = dimensionStore.currentId
  await dimensionStore.load()
  if (dimensionStore.currentId !== before) {
    window.location.reload()
  }
}

// ==================== 授权配置 ====================

const authDialogVisible = ref(false)
const authSaving = ref(false)
const currentDimension = ref<AssessDimensionVO | null>(null)
const authSubcompanyIds = ref<number[]>([])
const authExcludedSubcompanyIds = ref<number[]>([])
const authPersons = ref<AssessDimensionAuthVO[]>([])
const personPickerId = ref<number | undefined>(undefined)

/** 分部 id → 上级分部 id。树是从后端一次性拉回来的，这里就地索引，避免每次改动重查接口 */
const subCompanyParents = computed(() => {
  const parents = new Map<number, number | null>()
  const walk = (nodes: SubCompanyTreeNode[], parentId: number | null) => {
    for (const node of nodes) {
      parents.set(node.id, parentId)
      walk(node.children ?? [], node.id)
    }
  }
  walk(subCompanyTree.value, null)
  return parents
})

/**
 * 排除分部的可选项：只保留落在授权范围内的分支。
 *
 * <p>为什么必须联动而不是列整棵树：排除项在授权范围之外时是个永远不生效的配置 ——
 * 选了「中达科技」但没授权给「国际贸易」，页面上看着像配好了，实际一个人也没排除掉。
 * 这类「看着生效、其实空转」的配置比配错更难发现，所以干脆不提供。</p>
 *
 * <p>范围判定按「自身或任一祖先被授权」：授权了国际贸易，则中达科技与它下面的分支都可排除。</p>
 */
const excludableTree = computed<SubCompanyTreeNode[]>(() => {
  const granted = new Set(authSubcompanyIds.value)
  if (!granted.size) {
    return []
  }
  const parents = subCompanyParents.value
  const inScope = (id: number): boolean => {
    let current: number | null | undefined = id
    let depth = 0
    while (current != null && depth++ < 32) {
      if (granted.has(current)) {
        return true
      }
      current = parents.get(current)
    }
    return false
  }
  const prune = (nodes: SubCompanyTreeNode[]): SubCompanyTreeNode[] =>
    nodes
      .filter(node => inScope(node.id))
      .map(node => ({
        ...node,
        // 已授权的分部本身不能排除自己：授权 A 又排除 A 等于 A 一个人都不进来，
        // 那这次授权就是白配的，只可能是误操作，直接从可选项里禁掉。
        disabled: granted.has(node.id),
        children: prune(node.children ?? []),
      }))
  return prune(subCompanyTree.value)
})

/**
 * 授权分部一改，掉出范围的排除项要跟着清掉。
 *
 * <p>不清的话会留下「看不见但已存在」的条目：树里已经选不中它了，
 * 保存时却还在提交，下次组织调整把它又划回范围内时会突然开始生效。</p>
 *
 * <p>同时清掉「授权里已有」的排除项：先排除「中达科技」、再回头把它授权进来，
 * 这种自己排除自己的配置留着只会让这个维度一个人都不进（见 excludableTree 的 disabled）。
 * 用户真要排除，就只勾授权、不勾排除；所以这里直接以授权为准。</p>
 *
 * <p>用 flush:'sync' 而不是默认的 pre：加载已有配置时先写授权分部、下一行才写排除项，
 * 若回调延后到渲染前才跑，中间那一刻排除项还是空数组，看不出问题；但把两行顺序调换
 * 或以后有人在中间插一句 await，就会静默清掉用户配好的排除项。同步执行下读到的
 * 永远是写完的那一刻的值，不依赖行序。</p>
 */
watch(authSubcompanyIds, () => {
  if (!authExcludedSubcompanyIds.value.length) {
    return
  }
  const allowed = new Set<number>()
  const granted = new Set(authSubcompanyIds.value)
  const collect = (nodes: SubCompanyTreeNode[]) => {
    for (const node of nodes) {
      // 已授权的分部本身留在树里（当父节点用），但不能再作为排除项
      if (!granted.has(node.id)) {
        allowed.add(node.id)
      }
      collect(node.children ?? [])
    }
  }
  collect(excludableTree.value)
  const next = authExcludedSubcompanyIds.value.filter(id => allowed.has(id))
  if (next.length !== authExcludedSubcompanyIds.value.length) {
    authExcludedSubcompanyIds.value = next
  }
}, { flush: 'sync' })

const handleConfigAuth = async (row: AssessDimensionVO) => {
  currentDimension.value = row
  personPickerId.value = undefined
  try {
    const auths = await listDimensionAuths(row.id)
    authSubcompanyIds.value = auths.filter(a => a.authType === AUTH_TYPE_SUBCOMPANY).map(a => a.authId)
    authExcludedSubcompanyIds.value = auths
      .filter(a => a.authType === AUTH_TYPE_EXCLUDED_SUBCOMPANY)
      .map(a => a.authId)
    authPersons.value = auths.filter(a => a.authType === AUTH_TYPE_RESOURCE)
    authDialogVisible.value = true
  } catch (error) {
    console.error('加载授权失败:', error)
  }
}

const handleAddPerson = (value: number | undefined, data: { lastname: string } | undefined) => {
  if (!value) return
  if (authPersons.value.some(p => p.authId === value)) {
    ElMessage.warning('该人员已在授权列表中')
  } else {
    authPersons.value.push({ id: 0, authType: AUTH_TYPE_RESOURCE, authId: value, authName: data?.lastname })
  }
  // 清空选择器，便于连续添加
  personPickerId.value = undefined
}

const removePerson = (authId: number) => {
  authPersons.value = authPersons.value.filter(p => p.authId !== authId)
}

const handleSaveAuths = async () => {
  if (!currentDimension.value) return
  authSaving.value = true
  try {
    const items = [
      ...authSubcompanyIds.value.map(id => ({ authType: AUTH_TYPE_SUBCOMPANY, authId: id })),
      ...authExcludedSubcompanyIds.value.map(id => ({
        authType: AUTH_TYPE_EXCLUDED_SUBCOMPANY,
        authId: id,
      })),
      ...authPersons.value.map(p => ({ authType: AUTH_TYPE_RESOURCE, authId: p.authId })),
    ]
    await saveDimensionAuths(currentDimension.value.id, items)
    ElMessage.success('授权保存成功')
    authDialogVisible.value = false
    await loadData()
  } catch (error) {
    console.error('保存授权失败:', error)
  } finally {
    authSaving.value = false
  }
}
</script>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1D2129;
}

.page-description {
  margin-top: 4px;
  font-size: 13px;
  color: #86909C;
}

.card {
  background: #FFFFFF;
  border: 1px solid #E5E6EB;
  border-radius: 10px;
}

.card-body {
  padding: 16px 20px;
}

.status-tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 10px;
}

.status-tag.active {
  background: #E8F7EE;
  color: #00A85A;
}

.status-tag.disabled {
  background: #FFF3E8;
  color: #FF7D00;
}

.text-muted {
  color: #C9CDD4;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1D2129;
  margin-bottom: 10px;
}

.auth-section {
  margin-bottom: 20px;
}

.person-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

/* 维度管理员在列表里可能有好几个，横向排开、超出换行 */
.admin-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

/* 「不含 中达科技」：排除项跟在授权分部后面，要一眼看出是减法而不是加法 */
.exclude-note {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-left: 4px;
  font-size: 12px;
  color: #86909C;
}

/* 字段下方的解释文字：这两项不是纯配置，得写清「它会影响什么」 */
.form-hint {
  font-size: 12px;
  color: #86909C;
  line-height: 1.6;
  margin-top: 6px;
}

.full-width {
  width: 100%;
}
</style>
