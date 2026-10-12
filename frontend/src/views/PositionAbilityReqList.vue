<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">岗位能力要求配置</h1>
        <p class="page-description">为各部门下的岗位配置需要的能力要素（类别+要素），用于人员能力要求自动带出</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增配置</el-button>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">配置总数</div>
        <div class="stat-value">{{ pagination.total }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">涉及部门</div>
        <div class="stat-value">{{ departmentCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">涉及岗位</div>
        <div class="stat-value">{{ jobTitleCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">能力要素</div>
        <div class="stat-value">{{ totalItems }}</div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">配置列表</h2>
      </div>
      <div class="card-body">
        <el-table :data="reqList" class="data-table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-content">
                <div class="items-title">能力要素配置（{{ row.items?.length || 0 }}个）</div>
                <div class="items-list">
                  <div v-for="item in row.items" :key="item.id" class="item-row">
                    <span class="category-tag">{{ item.categoryName }}</span>
                    <span class="element-name">{{ item.elementName }}</span>
                  </div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="departmentName" label="部门" width="180" />
          <el-table-column prop="jobTitleName" label="岗位" width="180" />
          <el-table-column label="能力要素" width="140">
            <template #default="{ row }">
              <span v-if="row.items && row.items.length > 0">{{ row.items.length }} 个（展开查看）</span>
              <span v-else class="text-muted">未配置</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination-wrapper">
          <el-pagination
            v-model:current-page="pagination.currentPage"
            v-model:page-size="pagination.pageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="pagination.total"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="handleSizeChange"
            @current-change="handlePageChange"
          />
        </div>
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="860px"
      class="form-dialog"
      top="8vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        class="form-content"
      >
        <el-form-item label="部门" prop="departmentId">
          <el-tree-select
            ref="deptTreeSelectRef"
            v-model="formData.departmentId"
            :data="deptTreeData"
            :props="deptTreeProps"
            node-key="id"
            :default-expand-all="!!formData.departmentId"
            placeholder="请选择部门"
            style="width: 100%"
            popper-class="fixed-width-tree-dropdown"
            check-strictly
            filterable
            @visible-change="handleDeptTreeDropdownVisibleChange"
            @change="handleDepartmentChange"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <el-icon v-if="data.type === 'company'" style="color: #2563EB; margin-right: 4px;">
                  <OfficeBuilding />
                </el-icon>
                <el-icon v-else style="color: #10B981; margin-right: 4px;">
                  <Folder />
                </el-icon>
                <span>{{ data.name }}</span>
              </span>
            </template>
          </el-tree-select>
          <div class="form-tip">从泛微系统部门表中选择（只能选择部门）</div>
        </el-form-item>

        <el-form-item label="岗位" prop="jobTitleId">
          <el-select
            v-model="formData.jobTitleId"
            placeholder="请先选择部门"
            :disabled="!formData.departmentId"
            :loading="jobTitleLoading"
            style="width: 100%"
            filterable
          >
            <el-option
              v-for="item in jobTitleOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <div class="form-tip">根据部门联动显示该部门下所有人员涉及的岗位</div>
        </el-form-item>

        <el-divider />

        <el-form-item label="能力要素配置" class="element-section">
          <div class="element-summary">
            已选 <strong>{{ formData.items.length }}</strong> 个能力要素，涉及
            <strong>{{ selectedCategoryCount }}</strong> 个类别
          </div>
          <div class="category-list">
            <div v-for="cat in visibleCategories" :key="cat.id" class="category-row">
              <div class="category-name">
                <span>{{ cat.categoryName }}</span>
                <el-tag v-if="cat.status !== 1" type="info" size="small">已停用</el-tag>
                <span class="category-count">{{ selectedIdsOf(cat.id).length }} 个</span>
              </div>
              <el-select
                :model-value="selectedIdsOf(cat.id)"
                multiple
                filterable
                clearable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="4"
                placeholder="搜索并选择能力要素"
                :loading="!!elementLoading[cat.id]"
                class="category-select"
                @update:model-value="(val: number[]) => setCategoryElements(cat.id, val)"
              >
                <el-option
                  v-for="elem in elementCache[cat.id] || []"
                  :key="elem.id"
                  :label="elem.elementName"
                  :value="elem.id"
                />
              </el-select>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmit">确定</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type { RawSubCompany, RawDepartment } from '@/types/org'
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { OfficeBuilding, Folder } from '@element-plus/icons-vue'
import {
  pagePositionAbilityReqs,
  createPositionAbilityReq,
  updatePositionAbilityReq,
  deletePositionAbilityReq,
  listJobTitlesByDepartment,
} from '@/api/positionAbilityReq'
import { listAbilityCategories, getAbilityCategoryById } from '@/api/abilityCategory'
import { listElementsByCategoryId, getAbilityElementById } from '@/api/abilityElement'
import type { PositionAbilityReqVO } from '@/types/positionAbilityReq'
import type { AbilityCategoryVO } from '@/types/abilityCategory'
import type { AbilityElementVO } from '@/types/abilityElement'
import request from '@/utils/request'

interface TreeNode {
  id: number | string
  name: string
  type: 'company' | 'department'
  children?: TreeNode[]
  [key: string]: unknown
}

/** 一个已选能力要素（扁平结构，提交时直接使用） */
interface SelectedItem {
  categoryId: number
  elementId: number
}

const reqList = ref<PositionAbilityReqVO[]>([])
const categoryList = ref<AbilityCategoryVO[]>([])

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

// 部门树数据
const deptTreeData = ref<TreeNode[]>([])
interface TreeRefLike {
  getNode: (key: number) => { key: string | number } | null
  $el: HTMLElement
}
interface TreeSelectRefLike {
  treeRef?: TreeRefLike | null
}

const deptTreeSelectRef = ref<TreeSelectRefLike>()

// 部门树配置
const deptTreeProps = {
  label: 'name',
  value: 'id',
  children: 'children',
  disabled: (data: TreeNode) => data.type !== 'department'
}

// 岗位下拉框数据
const jobTitleOptions = ref<{ id: number; name: string }[]>([])
const jobTitleLoading = ref(false)

// 加载部门树
const loadDeptTree = async () => {
  try {
    const [companyResult, deptResult] = await Promise.all([
      request.get('/sub-companies?pageNum=1&pageSize=100'),
      request.get('/departments?pageNum=1&pageSize=1000')
    ])
    const companies = companyResult.data?.list || companyResult.list || []
    const departments = deptResult.list || []

    const companyMap = new Map<number, TreeNode>()
    companies.forEach((c: RawSubCompany) => {
      companyMap.set(c.id, {
        id: c.id,
        name: c.subcompanyname,
        type: 'company',
        children: []
      })
    })

    const rootCompanies: TreeNode[] = []
    companies.forEach((c: RawSubCompany) => {
      const node = companyMap.get(c.id)!
      if (c.supsubcomid && companyMap.has(c.supsubcomid)) {
        const parent = companyMap.get(c.supsubcomid)!
        parent.children = parent.children || []
        parent.children.push(node)
      } else {
        rootCompanies.push(node)
      }
    })

    const deptMap = new Map<number, TreeNode>()
    departments.forEach((d: RawDepartment) => {
      deptMap.set(d.id, {
        id: d.id,
        name: d.departmentmark,
        type: 'department',
        children: []
      })
    })

    departments.forEach((d: RawDepartment) => {
      const node = deptMap.get(d.id)!
      if (d.supdepid && deptMap.has(d.supdepid)) {
        const parent = deptMap.get(d.supdepid)!
        parent.children = parent.children || []
        parent.children.push(node)
      } else {
        const company = d.subcompanyid1 != null ? companyMap.get(d.subcompanyid1) : undefined
        if (company) {
          company.children = company.children || []
          company.children.push(node)
        }
      }
    })

    deptTreeData.value = rootCompanies
  } catch (error) {
    console.error('加载部门树失败:', error)
  }
}

// 加载岗位列表（根据部门）
const loadJobTitlesByDepartment = async (departmentId: number) => {
  jobTitleLoading.value = true
  try {
    jobTitleOptions.value = await listJobTitlesByDepartment(departmentId)
  } catch (error) {
    console.error('加载岗位列表失败:', error)
    jobTitleOptions.value = []
  } finally {
    jobTitleLoading.value = false
  }
}

// 加载能力类别
const loadCategoryList = async () => {
  try {
    categoryList.value = await listAbilityCategories()
  } catch (error) {
    console.error('加载能力类别失败:', error)
  }
}

// 加载某类别下的要素
const loadElementsByCategory = async (categoryId: number): Promise<AbilityElementVO[]> => {
  try {
    return await listElementsByCategoryId(categoryId)
  } catch (error) {
    console.error('加载能力要素失败:', error)
    return []
  }
}

// 部门下拉框显示时滚动到选中节点
const handleDeptTreeDropdownVisibleChange = (isVisible: boolean) => {
  if (!isVisible || !formData.departmentId) return

  const tryScroll = (retries: number) => {
    if (retries <= 0) return

    const treeSelect = deptTreeSelectRef.value
    if (!treeSelect) return
    const tree = treeSelect.treeRef
    if (!tree) return

    const node = tree.getNode(formData.departmentId!)
    if (!node) return

    const nodeElement = tree.$el?.querySelector(`.el-tree-node[data-key="${node.key}"]`) as HTMLElement
    if (!nodeElement) return

    const scrollContainer = tree.$el as HTMLElement
    if (!scrollContainer) return

    const rect = nodeElement.getBoundingClientRect()
    if (rect.height === 0) {
      setTimeout(() => tryScroll(retries - 1), 200)
      return
    }

    const containerRect = scrollContainer.getBoundingClientRect()
    const nodeRelativeTop = rect.top - containerRect.top
    const containerHeight = scrollContainer.clientHeight
    const nodeHeight = nodeElement.offsetHeight
    const scrollTop = nodeRelativeTop - (containerHeight / 2) + (nodeHeight / 2)

    scrollContainer.scrollTo({
      top: scrollContainer.scrollTop + scrollTop,
      behavior: 'smooth'
    })
  }

  setTimeout(() => tryScroll(5), 100)
}

const departmentCount = computed(() => {
  const deptIds = new Set(reqList.value.map(r => r.departmentId))
  return deptIds.size
})

const jobTitleCount = computed(() => {
  const jobTitleIds = new Set(reqList.value.map(r => r.jobTitleId))
  return jobTitleIds.size
})

const totalItems = computed(() => {
  return (reqList.value || []).reduce((sum, r) => sum + (r.items?.length || 0), 0)
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  departmentId: undefined as number | undefined,
  jobTitleId: undefined as number | undefined,
  description: '',
  items: [] as SelectedItem[],
})

const formRules: FormRules = {
  departmentId: [
    { required: true, message: '请选择部门', trigger: 'change' },
  ],
  jobTitleId: [
    { required: true, message: '请选择岗位', trigger: 'change' },
  ],
}

const loadData = async () => {
  try {
    const result = await pagePositionAbilityReqs(pagination.currentPage, pagination.pageSize)
    reqList.value = result.list || []
    pagination.total = result.total
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const handlePageChange = (page: number) => {
  pagination.currentPage = page
  loadData()
}

const handleSizeChange = (size: number) => {
  pagination.pageSize = size
  pagination.currentPage = 1
  loadData()
}

// 按类别缓存的能力要素选项，同一类别只请求一次
const elementCache = ref<Record<number, AbilityElementVO[]>>({})
const elementLoading = ref<Record<number, boolean>>({})

const preloadElements = async (categoryIds: number[]) => {
  await Promise.all(categoryIds.map(async (id) => {
    if (elementCache.value[id] || elementLoading.value[id]) return
    elementLoading.value[id] = true
    try {
      elementCache.value[id] = await loadElementsByCategory(id)
    } finally {
      elementLoading.value[id] = false
    }
  }))
}

// 打开弹窗时预加载所有类别的要素
watch(dialogVisible, (visible) => {
  if (visible) preloadElements(categoryList.value.map(c => c.id))
})

// 当前类别下已选的要素ID
const selectedIdsOf = (categoryId: number): number[] =>
  formData.items.filter(i => i.categoryId === categoryId).map(i => i.elementId)

// 替换某类别下的已选要素（天然去重）
const setCategoryElements = (categoryId: number, ids: number[]) => {
  const others = formData.items.filter(i => i.categoryId !== categoryId)
  formData.items = [...others, ...ids.map(elementId => ({ categoryId, elementId }))]
}

// 展示的类别：启用的，或已有选中项的（含已停用但仍被引用的）
const visibleCategories = computed(() =>
  categoryList.value.filter(c => c.status === 1 || selectedIdsOf(c.id).length > 0)
)

const selectedCategoryCount = computed(() => new Set(formData.items.map(i => i.categoryId)).size)

const handleAdd = () => {
  dialogTitle.value = '新增配置'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: PositionAbilityReqVO) => {
  dialogTitle.value = '编辑配置'
  formData.id = row.id
  formData.departmentId = row.departmentId
  formData.jobTitleId = row.jobTitleId
  formData.description = row.description || ''
  
  // 加载岗位列表
  if (row.departmentId) {
    await loadJobTitlesByDepartment(row.departmentId)
  }
  
  // 加载明细项：扁平化为 {categoryId, elementId}
  formData.items = (row.items || [])
    .filter(item => item.categoryId && item.elementId)
    .map(item => ({ categoryId: item.categoryId as number, elementId: item.elementId as number }))

  // 补充已停用的类别，保证能显示名称而非ID
  const categoryIds = [...new Set(formData.items.map(i => i.categoryId))]
  for (const categoryId of categoryIds) {
    if (!categoryList.value.find(c => c.id === categoryId)) {
      try {
        const category = await getAbilityCategoryById(categoryId)
        if (category) categoryList.value.push(category)
      } catch (e) {
        console.warn('加载已停用的类别失败:', categoryId, e)
      }
    }
  }

  // 预加载要素，并补充已停用的要素
  await preloadElements(categoryIds)
  for (const item of formData.items) {
    const options = elementCache.value[item.categoryId] || []
    if (!options.find(e => e.id === item.elementId)) {
      try {
        const element = await getAbilityElementById(item.elementId)
        if (element) elementCache.value[item.categoryId] = [...options, element]
      } catch (e) {
        console.warn('加载已停用的要素失败:', item.elementId, e)
      }
    }
  }

  dialogVisible.value = true
}

const handleDelete = async (row: PositionAbilityReqVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该配置吗？删除后不可恢复。', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await deletePositionAbilityReq(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleDepartmentChange = async (departmentId: number) => {
  formData.jobTitleId = undefined
  if (departmentId) {
    await loadJobTitlesByDepartment(departmentId)
  } else {
    jobTitleOptions.value = []
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.departmentId || !formData.jobTitleId) {
      ElMessage.error('请选择部门和岗位')
      return
    }

    if (formData.items.length === 0) {
      ElMessage.error('请至少选择一个能力要素')
      return
    }
    const items = formData.items.map(i => ({ categoryId: i.categoryId, elementId: i.elementId }))

    if (formData.id) {
      // 编辑模式
      await updatePositionAbilityReq({
        id: formData.id,
        departmentId: formData.departmentId,
        jobTitleId: formData.jobTitleId,
        items,
        description: formData.description,
      })
      ElMessage.success('更新成功')
    } else {
      // 新增模式
      await createPositionAbilityReq({
        departmentId: formData.departmentId,
        jobTitleId: formData.jobTitleId,
        items,
        description: formData.description,
      })
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    await loadData()
  } catch (error) {
    console.error('提交失败:', error)
  }
}

const resetForm = () => {
  formData.id = 0
  formData.departmentId = undefined
  formData.jobTitleId = undefined
  formData.description = ''
  formData.items = []
  jobTitleOptions.value = []
  formRef.value?.resetFields()
}

onMounted(() => {
  loadData()
  loadCategoryList()
  loadDeptTree()
})
</script>

<style scoped>
.page-container {
  max-width: 1400px;
  margin: 0 auto;
}

/* 页面标题区 */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 8px;
}

.page-description {
  font-size: 14px;
  color: var(--color-text-muted);
}

/* 按钮 */
.btn-primary {
  display: inline-flex;
  align-items: center;
  padding: 10px 20px;
  background: var(--color-primary);
  color: var(--color-card);
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-primary:hover {
  background: var(--color-primary-dark);
}

.btn-icon {
  margin-right: 6px;
  font-size: 16px;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  padding: 10px 20px;
  background: var(--color-card);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: var(--color-bg);
  border-color: #C9CDD4;
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: var(--color-card);
  border-radius: 8px;
  padding: 20px;
  border: 1px solid var(--color-border);
}

.stat-label {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: var(--color-text);
}

/* 卡片 */
.card {
  background: var(--color-card);
  border-radius: 8px;
  border: 1px solid var(--color-border);
}

.card-header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
}

.card-body {
  padding: 0;
}

/* 表格 */
.data-table {
  width: 100%;
}

.category-tag {
  display: inline-block;
  padding: 2px 8px;
  background: #F0F5FF;
  color: #4096FF;
  border-radius: 4px;
  font-size: 13px;
  margin-right: 8px;
}

.expand-content {
  padding: 16px 20px;
}

.items-title {
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 12px;
  font-size: 14px;
}

.items-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.item-row {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  background: var(--color-bg);
  border-radius: 6px;
}

.element-name {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.action-btn {
  padding: 6px 12px;
  border: none;
  background: none;
  font-size: 13px;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s ease;
}

.action-btn.edit {
  color: var(--color-primary);
}

.action-btn.edit:hover {
  background: var(--color-primary-light);
}

.action-btn.delete {
  color: var(--color-danger);
}

.action-btn.delete:hover {
  background: var(--color-danger-light);
}

/* 表单 */
.form-content {
  padding: 20px 0;
}

.form-tip {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-top: 4px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

/* 能力要素配置（分组多选） */
.element-summary {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 10px;
}

.category-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.category-row {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 12px;
  align-items: start;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg);
}

.category-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  padding-top: 6px;
  flex-wrap: wrap;
}

.category-count {
  font-size: 12px;
  font-weight: 400;
  color: var(--color-text-muted);
}

.category-select {
  width: 100%;
}

.text-muted {
  color: var(--color-text-muted);
}







.tree-node {
  display: flex;
  align-items: center;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0;
}

.form-dialog :deep(.el-dialog) {
  height: 84vh !important;
  max-height: 84vh !important;
  display: flex;
  flex-direction: column;
  margin: 8vh auto !important;
}

.form-dialog :deep(.el-dialog__body) {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.form-dialog :deep(.el-dialog__footer) {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
}
</style>

<!-- 下拉框被 teleport 到 body，必须用非 scoped 样式 -->
<style>
.fixed-width-tree-dropdown {
  width: 400px !important;
  max-width: 90vw !important;
}
.fixed-width-tree-dropdown .el-tree {
  max-height: 300px;
  overflow-y: auto;
}

.form-dialog {
  height: 84vh !important;
  max-height: 84vh !important;
  display: flex;
  flex-direction: column;
  margin: 8vh auto !important;
}

.form-dialog .el-dialog__body {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.form-dialog .el-dialog__footer {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
}
</style>
