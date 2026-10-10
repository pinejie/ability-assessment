<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">部门能力要求配置</h1>
        <p class="page-description">为各部门配置能力要素要求</p>
      </div>
      <button class="btn-primary" @click="handleAdd">
        <span class="btn-icon">+</span>
        新增要求
      </button>
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
        <div class="stat-label">能力要素</div>
        <div class="stat-value">{{ elementCount }}</div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">要求列表</h2>
      </div>
      <div class="card-body">
        <el-table :data="reqList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="departmentName" label="部门" width="200">
            <template #default="{ row }">
              <span class="dept-name">{{ row.departmentName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="elementName" label="能力要素" min-width="250">
            <template #default="{ row }">
              <span class="element-name">{{ row.elementName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="300" show-overflow-tooltip />
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <button class="action-btn edit" @click="handleEdit(row)">编辑</button>
              <button class="action-btn delete" @click="handleDelete(row)">删除</button>
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
      width="560px"
      class="form-dialog"
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
            ref="treeSelectRef"
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
        <el-form-item label="能力要素" prop="elementId">
          <el-select v-model="formData.elementId" placeholder="请选择能力要素" style="width: 100%">
            <el-option
              v-for="item in elementList"
              :key="item.id"
              :label="item.elementName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入补充说明（可选）"
          />
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
import { pageOrgAbilityReqs, createOrgAbilityReq, updateOrgAbilityReq, deleteOrgAbilityReq } from '@/api/orgAbilityReq'
import { listAbilityElements } from '@/api/abilityElement'
import type { OrgAbilityReqVO } from '@/types/orgAbilityReq'
import type { AbilityElementVO } from '@/types/abilityElement'
import request from '@/utils/request'

interface TreeNode {
  id: number | string
  name: string
  type: 'company' | 'department'
  children?: TreeNode[]
}

const reqList = ref<OrgAbilityReqVO[]>([])
const elementList = ref<AbilityElementVO[]>([])

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

// 部门树数据（一次性加载）
const deptTreeData = ref<TreeNode[]>([])

// 树形选择器配置
const deptTreeProps = {
  label: 'name',
  value: 'id',
  children: 'children',
  disabled: (data: TreeNode) => data.type === 'company'
}

// 一次性加载部门树
const loadDeptTree = async () => {
  try {
    const [companyResult, deptResult] = await Promise.all([
      request.get('/sub-companies?pageNum=1&pageSize=100'),
      request.get('/departments?pageNum=1&pageSize=1000')
    ])
    const companies = companyResult.data?.list || companyResult.list || []
    const departments = deptResult.list || []

    // 1. 构建公司节点映射
    const companyMap = new Map<number, TreeNode>()
    companies.forEach((c: any) => {
      companyMap.set(c.id, {
        id: c.id,
        name: c.subcompanyname,
        type: 'company',
        children: []
      })
    })

    // 2. 构建公司父子关系
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

    // 3. 构建部门节点映射
    const deptMap = new Map<number, TreeNode>()
    departments.forEach((d: any) => {
      deptMap.set(d.id, {
        id: d.id,
        name: d.departmentmark,
        type: 'department',
        children: []
      })
    })

    // 4. 构建部门父子关系，并把根部门挂到公司下
    departments.forEach((d: any) => {
      const node = deptMap.get(d.id)!
      if (d.supdepid && deptMap.has(d.supdepid)) {
        const parent = deptMap.get(d.supdepid)!
        parent.children = parent.children || []
        parent.children.push(node)
      } else {
        // 根部门，挂到所属公司下
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

const departmentCount = computed(() => {
  const deptIds = new Set(reqList.value.map(r => r.departmentId))
  return deptIds.size
})

const elementCount = computed(() => {
  const elementIds = new Set(reqList.value.map(r => r.elementId))
  return elementIds.size
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()
const treeSelectRef = ref<any>()

const formData = reactive({
  id: 0,
  departmentId: undefined as number | undefined,
  elementId: undefined as number | undefined,
  description: '',
})

// 编辑模式下，打开下拉框时自动滚动到已选中的部门节点
// 因为 popper 在点击时才渲染，所以监听 visible-change 事件
const handleDeptTreeDropdownVisibleChange = (isVisible: boolean) => {
  if (!isVisible || !formData.departmentId) return

  // popper 已打开，DOM 已经渲染，轮询等待元素有实际尺寸
  const tryScroll = (retries: number) => {
    if (retries <= 0) return

    const treeSelect = treeSelectRef.value
    if (!treeSelect) return
    const tree = treeSelect.treeRef
    if (!tree) return

    const node = tree.getNode(formData.departmentId!)
    if (!node) return

    const nodeElement = tree.$el?.querySelector(`.el-tree-node[data-key="${node.key}"]`) as HTMLElement
    if (!nodeElement) return

    const scrollContainer = tree.$el as HTMLElement
    if (!scrollContainer) return

    // 检查元素是否已渲染（有实际尺寸）
    const rect = nodeElement.getBoundingClientRect()
    if (rect.height === 0) {
      // 还没渲染完，200ms 后重试
      setTimeout(() => tryScroll(retries - 1), 200)
      return
    }

    // 元素已渲染，执行滚动
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

  // 最多重试 5 次（1秒内）
  setTimeout(() => tryScroll(5), 100)
}

const formRules: FormRules = {
  departmentId: [
    { required: true, message: '请选择部门', trigger: 'change' },
  ],
  elementId: [
    { required: true, message: '请选择能力要素', trigger: 'change' },
  ],
}

const loadData = async () => {
  try {
    const result = await pageOrgAbilityReqs(pagination.currentPage, pagination.pageSize)
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

const loadElementList = async () => {
  try {
    elementList.value = await listAbilityElements()
  } catch (error) {
    console.error('加载能力要素列表失败:', error)
  }
}

const handleAdd = () => {
  dialogTitle.value = '新增要求'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: OrgAbilityReqVO) => {
  dialogTitle.value = '编辑要求'
  Object.assign(formData, row)
  dialogVisible.value = true
}

const handleDelete = async (row: OrgAbilityReqVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该要求吗？删除后不可恢复。', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await deleteOrgAbilityReq(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.departmentId || !formData.elementId) {
      ElMessage.error('请填写完整信息')
      return
    }

    if (formData.id) {
      await updateOrgAbilityReq({
        id: formData.id,
        departmentId: formData.departmentId,
        elementId: formData.elementId,
        description: formData.description,
      })
      ElMessage.success('更新成功')
    } else {
      await createOrgAbilityReq({
        departmentId: formData.departmentId,
        elementId: formData.elementId,
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
  formData.elementId = undefined
  formData.description = ''
  formRef.value?.resetFields()
}

onMounted(() => {
  loadData()
  loadElementList()
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
  grid-template-columns: repeat(3, 1fr);
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

.dept-name {
  font-weight: 500;
  color: #1D2129;
}

.element-name {
  color: #1D2129;
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

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0;
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
