<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">岗位能力要求配置</h1>
        <p class="page-description">为各部门下的岗位配置需要的能力要素（类别+要素），用于人员能力要求自动带出</p>
      </div>
      <button class="btn-primary" @click="handleAdd">
        <span class="btn-icon">+</span>
        新增配置
      </button>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">配置总数</div>
        <div class="stat-value">{{ reqList.length }}</div>
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
          <el-table-column label="能力要素" min-width="250">
            <template #default="{ row }">
              <span v-if="row.items && row.items.length > 0">
                <span v-for="(item, idx) in row.items" :key="item.id">
                  {{ item.elementName }}<span v-if="idx < row.items.length - 1">、</span>
                </span>
              </span>
              <span v-else style="color: #999;">未配置</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <button class="action-btn edit" @click="handleEdit(row)">编辑</button>
              <button class="action-btn delete" @click="handleDelete(row)">删除</button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="700px"
      class="form-dialog"
      top="5vh"
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

        <el-form-item label="能力要素配置">
          <div class="element-configs">
            <div v-for="(config, index) in formData.items" :key="index" class="element-config-item">
              <div class="config-row">
                <div class="config-field">
                  <label>能力类别</label>
                  <el-select
                    v-model="config.categoryId"
                    placeholder="请选择类别"
                    @change="(_val: number) => handleCategoryChange(index)"
                  >
                    <el-option
                      v-for="cat in categoryList"
                      :key="cat.id"
                      :label="cat.categoryName"
                      :value="cat.id"
                    />
                  </el-select>
                </div>
                <div class="config-field">
                  <label>能力要素</label>
                  <el-select
                    v-model="config.elementId"
                    placeholder="请选择要素"
                    :disabled="!config.categoryId"
                    :loading="config.elementLoading"
                  >
                    <el-option
                      v-for="elem in config.elementOptions"
                      :key="elem.id"
                      :label="elem.elementName"
                      :value="elem.id"
                    />
                  </el-select>
                </div>
                <div class="config-field" style="flex: 0 0 auto;">
                  <label>&nbsp;</label>
                  <el-button type="danger" @click="removeItem(index)" :disabled="formData.items.length <= 1">
                    删除
                  </el-button>
                </div>
              </div>
            </div>
            <el-button type="primary" plain @click="addItem" class="add-config-btn">
              + 添加能力要素
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <button class="btn-secondary" @click="dialogVisible = false">取消</button>
          <button class="btn-primary" @click="handleSubmit">确定</button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { OfficeBuilding, Folder } from '@element-plus/icons-vue'
import {
  listPositionAbilityReqs,
  createPositionAbilityReq,
  updatePositionAbilityReq,
  deletePositionAbilityReq,
  listJobTitlesByDepartment,
} from '@/api/positionAbilityReq'
import { listAbilityCategories } from '@/api/abilityCategory'
import { listElementsByCategoryId } from '@/api/abilityElement'
import type { PositionAbilityReqVO } from '@/types/positionAbilityReq'
import type { AbilityCategoryVO } from '@/types/abilityCategory'
import type { AbilityElementVO } from '@/types/abilityElement'
import request from '@/utils/request'

interface TreeNode {
  id: number | string
  name: string
  type: 'company' | 'department'
  children?: TreeNode[]
  [key: string]: any
}

interface ItemConfig {
  categoryId: number | undefined
  elementId: number | undefined
  elementOptions: AbilityElementVO[]
  elementLoading: boolean
}

const reqList = ref<PositionAbilityReqVO[]>([])
const categoryList = ref<AbilityCategoryVO[]>([])

// 部门树数据
const deptTreeData = ref<TreeNode[]>([])
const deptTreeSelectRef = ref<any>()

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
    const [companies, departments] = await Promise.all([
      request.get('/sub-companies'),
      request.get('/departments')
    ])

    const companyMap = new Map<number, TreeNode>()
    companies.forEach((c: any) => {
      companyMap.set(c.id, {
        id: c.id,
        name: c.subcompanyname,
        type: 'company',
        children: []
      })
    })

    const rootCompanies: TreeNode[] = []
    companies.forEach((c: any) => {
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
    departments.forEach((d: any) => {
      deptMap.set(d.id, {
        id: d.id,
        name: d.departmentmark,
        type: 'department',
        children: []
      })
    })

    departments.forEach((d: any) => {
      const node = deptMap.get(d.id)!
      if (d.supdepid && deptMap.has(d.supdepid)) {
        const parent = deptMap.get(d.supdepid)!
        parent.children = parent.children || []
        parent.children.push(node)
      } else {
        const company = companyMap.get(d.subcompanyid1)
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
  return reqList.value.reduce((sum, r) => sum + (r.items?.length || 0), 0)
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  departmentId: undefined as number | undefined,
  jobTitleId: undefined as number | undefined,
  description: '',
  items: [{
    categoryId: undefined,
    elementId: undefined,
    elementOptions: [],
    elementLoading: false,
  }] as ItemConfig[],
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
    reqList.value = await listPositionAbilityReqs()
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

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
  
  // 加载明细项
  formData.items = []
  if (row.items && row.items.length > 0) {
    for (const item of row.items) {
      const config: ItemConfig = {
        categoryId: item.categoryId,
        elementId: item.elementId,
        elementOptions: [],
        elementLoading: false,
      }
      // 加载该类别下的要素选项
      if (item.categoryId) {
        config.elementLoading = true
        try {
          config.elementOptions = await loadElementsByCategory(item.categoryId)
        } finally {
          config.elementLoading = false
        }
      }
      formData.items.push(config)
    }
  } else {
    // 如果没有明细，添加一个空行
    formData.items.push({
      categoryId: undefined,
      elementId: undefined,
      elementOptions: [],
      elementLoading: false,
    })
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

const handleCategoryChange = async (index: number) => {
  const config = formData.items[index]
  config.elementId = undefined
  config.elementOptions = []
  if (config.categoryId) {
    config.elementLoading = true
    try {
      config.elementOptions = await loadElementsByCategory(config.categoryId)
    } finally {
      config.elementLoading = false
    }
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.departmentId || !formData.jobTitleId) {
      ElMessage.error('请选择部门和岗位')
      return
    }

    // 验证能力要素配置
    const items: { categoryId: number; elementId: number }[] = []
    for (const config of formData.items) {
      if (!config.categoryId || !config.elementId) {
        ElMessage.error('请完整填写能力要素配置（类别和要素）')
        return
      }
      items.push({
        categoryId: config.categoryId,
        elementId: config.elementId,
      })
    }

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
  formData.items = [{
    categoryId: undefined,
    elementId: undefined,
    elementOptions: [],
    elementLoading: false,
  }]
  jobTitleOptions.value = []
  formRef.value?.resetFields()
}

const addItem = () => {
  formData.items.push({
    categoryId: undefined,
    elementId: undefined,
    elementOptions: [],
    elementLoading: false,
  })
}

const removeItem = (index: number) => {
  formData.items.splice(index, 1)
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
  font-size: 24px;
  font-weight: 600;
  color: #1D2129;
  margin-bottom: 8px;
}

.page-description {
  font-size: 14px;
  color: #86909C;
}

/* 按钮 */
.btn-primary {
  display: inline-flex;
  align-items: center;
  padding: 10px 20px;
  background: #2563EB;
  color: #FFFFFF;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-primary:hover {
  background: #1D4ED8;
}

.btn-icon {
  margin-right: 6px;
  font-size: 16px;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  padding: 10px 20px;
  background: #FFFFFF;
  color: #4E5969;
  border: 1px solid #E5E6EB;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: #F5F7FA;
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
  background: #FFFFFF;
  border-radius: 8px;
  padding: 20px;
  border: 1px solid #E5E6EB;
}

.stat-label {
  font-size: 13px;
  color: #86909C;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #1D2129;
}

/* 卡片 */
.card {
  background: #FFFFFF;
  border-radius: 8px;
  border: 1px solid #E5E6EB;
}

.card-header {
  padding: 16px 20px;
  border-bottom: 1px solid #E5E6EB;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1D2129;
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
  color: #1D2129;
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
  background: #F7F8FA;
  border-radius: 6px;
}

.element-name {
  color: #4E5969;
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
  color: #2563EB;
}

.action-btn.edit:hover {
  background: #EFF4FF;
}

.action-btn.delete {
  color: #F53F3F;
}

.action-btn.delete:hover {
  background: #FFECE8;
}

/* 表单 */
.form-content {
  padding: 20px 0;
}

.form-tip {
  font-size: 12px;
  color: #86909C;
  margin-top: 4px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

/* 能力要素配置 */
.element-configs {
  width: 100%;
}

.element-config-item {
  margin-bottom: 12px;
  padding: 16px;
  background: #F7F8FA;
  border-radius: 6px;
}

.config-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.config-field {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.config-field label {
  font-size: 13px;
  color: #4E5969;
  font-weight: 500;
}

.add-config-btn {
  width: 100%;
  margin-top: 8px;
}

.tree-node {
  display: flex;
  align-items: center;
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
</style>
