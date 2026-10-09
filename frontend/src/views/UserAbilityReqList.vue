<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">人员能力要求配置</h1>
        <p class="page-description">为人员配置能力要素要求等级，支持一对多配置</p>
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
        <div class="stat-label">涉及人员</div>
        <div class="stat-value">{{ resourceCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">能力要素</div>
        <div class="stat-value">{{ elementCount }}</div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">配置列表</h2>
      </div>
      <div class="card-body">
        <el-table :data="reqList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="resourceLastName" label="人员" width="150">
            <template #default="{ row }">
              <span class="resource-name">{{ row.resourceLastName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="elementName" label="能力要素" min-width="200">
            <template #default="{ row }">
              <span class="element-name">{{ row.elementName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="levelName" label="要求等级" width="120">
            <template #default="{ row }">
              <span class="level-tag">
                {{ row.levelName }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="score" label="分数" width="100">
            <template #default="{ row }">
              <span class="score">{{ row.score }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="levelRequirement" label="等级要求" min-width="250" show-overflow-tooltip />
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
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        class="form-content"
      >
        <el-form-item label="人员" prop="resourceId">
          <el-tree-select
            v-model="formData.resourceId"
            :key="resourceTreeKey"
            :data="resourceSearchMode ? resourceSearchTree : []"
            :load="loadResourceTree"
            :props="resourceTreeProps"
            placeholder="请选择或搜索人员"
            style="width: 100%"
            :lazy="!resourceSearchMode"
            check-strictly
            filterable
            :filter-node-method="handleResourceFilter"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <el-icon v-if="data.type === 'company'" style="color: #2563EB; margin-right: 4px;">
                  <OfficeBuilding />
                </el-icon>
                <el-icon v-else-if="data.type === 'department'" style="color: #10B981; margin-right: 4px;">
                  <Folder />
                </el-icon>
                <el-icon v-else style="color: #8B5CF6; margin-right: 4px;">
                  <User />
                </el-icon>
                <span>{{ data.name }}</span>
              </span>
            </template>
          </el-tree-select>
          <div class="form-tip">从泛微系统人员表中选择（只能选择人员，支持搜索）</div>
        </el-form-item>

        <el-divider />

        <el-form-item label="能力要素配置">
          <div class="element-configs">
            <div v-for="(config, index) in formData.elementConfigs" :key="index" class="element-config-item">
              <div class="config-row">
                <div class="config-field">
                  <label>能力要素</label>
                  <el-select
                    v-model="config.elementId"
                    placeholder="请选择能力要素"
                    @change="(val: number) => handleElementChange(val, index)"
                  >
                    <el-option
                      v-for="item in elementList"
                      :key="item.id"
                      :label="item.elementName"
                      :value="item.id"
                    />
                  </el-select>
                </div>
                <div class="config-field">
                  <label>要求等级</label>
                  <el-select
                    v-model="config.levelId"
                    placeholder="请选择等级"
                    @change="(val: number) => handleLevelChange(val, index)"
                  >
                    <el-option
                      v-for="level in getElementLevels(config.elementId)"
                      :key="level.id"
                      :label="level.levelName"
                      :value="level.id"
                    />
                  </el-select>
                </div>
                <div class="config-field">
                  <label>分数</label>
                  <el-input-number
                    v-model="config.score"
                    :min="0"
                    :max="100"
                    :precision="2"
                    readonly
                  />
                </div>
                <div class="config-field">
                  <label>&nbsp;</label>
                  <el-button type="danger" @click="removeElementConfig(index)" :disabled="formData.elementConfigs.length <= 1">
                    删除
                  </el-button>
                </div>
              </div>
              <div v-if="config.levelRequirement" class="level-requirement">
                <strong>等级要求：</strong>{{ config.levelRequirement }}
              </div>
            </div>
            <el-button type="primary" plain @click="addElementConfig" class="add-config-btn">
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
import { listUserAbilityReqs, createUserAbilityReq, updateUserAbilityReq, deleteUserAbilityReq } from '@/api/userAbilityReq'
import { listAbilityElements } from '@/api/abilityElement'
import { listAbilityElementLevelsByElementId } from '@/api/abilityElementLevel'
import type { UserAbilityReqVO } from '@/types/userAbilityReq'
import type { AbilityElementVO } from '@/types/abilityElement'
import type { AbilityElementLevelVO } from '@/types/abilityElementLevel'
import request from '@/utils/request'

import { OfficeBuilding, Folder, User } from '@element-plus/icons-vue'

interface SubCompany {
  id: number
  subcompanyname: string
  supsubcomid: number
}

interface Department {
  id: number
  departmentmark: string
  supdepid: number
  subcompanyid1: number
}

interface Resource {
  id: number
  lastname: string
  departmentid: number
}

interface TreeNode {
  id: number
  name: string
  type: 'company' | 'department' | 'resource'
  children?: TreeNode[]
}

interface ElementConfig {
  elementId: number | undefined
  levelId: number | undefined
  score: number
  levelRequirement: string
  description?: string
}

const reqList = ref<UserAbilityReqVO[]>([])
const elementList = ref<AbilityElementVO[]>([])
const elementLevelMap = ref<Map<number, AbilityElementLevelVO[]>>(new Map())

// 搜索相关
const resourceSearchMode = ref(false)
const resourceSearchTree = ref<TreeNode[]>([])
const resourceTreeKey = ref(0) // 用于强制重新渲染

// 搜索防抖定时器
let resourceSearchTimer: any = null

// filter-node-method：每次输入都会调用，用于触发远程搜索
const handleResourceFilter = (value: string) => {
  clearTimeout(resourceSearchTimer)

  if (!value || value.trim() === '') {
    // 清空搜索，恢复懒加载模式
    if (resourceSearchMode.value) {
      resourceSearchMode.value = false
      resourceSearchTree.value = []
      resourceTreeKey.value++ // 强制重新渲染，恢复懒加载
    }
    return true
  }

  // 进入搜索模式
  resourceSearchTimer = setTimeout(async () => {
    try {
      // 只需要一个请求，后端已返回完整的公司层级路径
      const searchResults = await request.get(`/search/resources?keyword=${encodeURIComponent(value)}`)

      // 使用 companyPath 构建树结构
      const companyMap = new Map<number, TreeNode>()
      const deptMap = new Map<string, TreeNode>() // key: companyId-departmentId
      const rootCompanyIds = new Set<number>() // 跟踪根公司，避免重复

      // 遍历搜索结果，根据 companyPath 构建公司层级
      searchResults.forEach((item: any) => {
        const companyPath: any[] = item.companyPath || []
        const deptId = item.departmentid

        // 构建公司层级链
        let parentNode: TreeNode | undefined = undefined
        let lastCompanyId: number | null = null
        companyPath.forEach((company: any) => {
          if (!companyMap.has(company.id)) {
            const node: TreeNode = {
              id: company.id,
              name: company.subcompanyname,
              type: 'company',
              children: []
            }
            companyMap.set(company.id, node)

            // 如果是第一个公司（根公司），记录其ID
            if (!parentNode) {
              rootCompanyIds.add(company.id)
            } else {
              // 否则挂到父公司下
              parentNode.children = parentNode.children || []
              parentNode.children.push(node)
            }
          }
          parentNode = companyMap.get(company.id)
          lastCompanyId = company.id
        })

        // 创建部门节点（挂到最后一个公司下）
        if (parentNode !== undefined && lastCompanyId !== null) {
          const lastCompany = parentNode as TreeNode
          const deptKey = `${lastCompanyId}-${deptId}`
          if (!deptMap.has(deptKey)) {
            const deptNode: TreeNode = {
              id: deptId,
              name: item.departmentName,
              type: 'department',
              children: []
            }
            deptMap.set(deptKey, deptNode)
            lastCompany.children = lastCompany.children || []
            lastCompany.children.push(deptNode)
          }

          // 创建人员节点（挂到部门下）
          const resourceNode: TreeNode = {
            id: item.id,
            name: item.lastname,
            type: 'resource'
          }
          const deptNode = deptMap.get(deptKey)!
          deptNode.children = deptNode.children || []
          deptNode.children.push(resourceNode)
        }
      })

      // 构建根公司列表（从 companyMap 中获取，避免重复）
      const rootCompanies: TreeNode[] = []
      rootCompanyIds.forEach(id => {
        const company = companyMap.get(id)
        if (company) {
          rootCompanies.push(company)
        }
      })

      // 去重：多个人员可能属于同一个部门/公司，需要合并
      const mergeDuplicates = (nodes: TreeNode[]): TreeNode[] => {
        const seen = new Map<string, TreeNode>()
        const result: TreeNode[] = []

        nodes.forEach(node => {
          const key = `${node.type}-${node.id}`
          if (seen.has(key)) {
            // 合并 children
            const existing = seen.get(key)!
            if (node.children) {
              existing.children = existing.children || []
              existing.children.push(...node.children)
            }
          } else {
            seen.set(key, node)
            result.push(node)
          }
        })

        // 递归处理 children
        result.forEach(node => {
          if (node.children && node.children.length > 0) {
            node.children = mergeDuplicates(node.children)
          }
        })

        return result
      }

      resourceSearchTree.value = mergeDuplicates(rootCompanies)

      // 切换到搜索模式
      if (!resourceSearchMode.value) {
        resourceSearchMode.value = true
        resourceTreeKey.value++ // 强制重新渲染
      }
    } catch (error) {
      console.error('搜索人员失败:', error)
      resourceSearchTree.value = []
    }
  }, 300)

  // 搜索模式下显示所有节点（已经通过后端过滤）
  return true
}

// 人员树形选择器配置
const resourceTreeProps = {
  label: 'name',
  value: 'id',
  isLeaf: (data: TreeNode) => data.type === 'resource',
  disabled: (data: TreeNode) => data.type !== 'resource'
}

// 懒加载人员树
const loadResourceTree = async (node: any, resolve: (nodes: TreeNode[]) => void) => {
  if (node.level === 0) {
    // 加载第一层：只加载根公司
    try {
      const response = await request.get('/sub-companies/root')
      const nodes: TreeNode[] = response.map((company: SubCompany) => ({
        id: company.id,
        name: company.subcompanyname,
        type: 'company' as const
      }))
      resolve(nodes)
    } catch (error) {
      console.error('加载根公司列表失败:', error)
      resolve([])
    }
  } else if (node.data.type === 'company') {
    // 展开公司：加载子公司 + 该公司下的根部门
    try {
      const [childCompanies, rootDepts] = await Promise.all([
        request.get(`/sub-companies/children?parentId=${node.data.id}`),
        request.get(`/departments/root?subcompanyId=${node.data.id}`)
      ])

      const nodes: TreeNode[] = []

      // 添加子公司
      childCompanies.forEach((company: SubCompany) => {
        nodes.push({
          id: company.id,
          name: company.subcompanyname,
          type: 'company' as const
        })
      })

      // 添加根部门
      rootDepts.forEach((dept: Department) => {
        nodes.push({
          id: dept.id,
          name: dept.departmentmark,
          type: 'department' as const
        })
      })

      resolve(nodes)
    } catch (error) {
      console.error('加载公司子节点失败:', error)
      resolve([])
    }
  } else if (node.data.type === 'department') {
    // 展开部门：加载子部门 + 人员
    try {
      const [childDepts, resources] = await Promise.all([
        request.get(`/departments/children?parentId=${node.data.id}`),
        request.get(`/resources/by-department?departmentId=${node.data.id}`)
      ])

      const nodes: TreeNode[] = []

      // 添加子部门
      childDepts.forEach((dept: Department) => {
        nodes.push({
          id: dept.id,
          name: dept.departmentmark,
          type: 'department' as const
        })
      })

      // 添加人员
      resources.forEach((resource: Resource) => {
        nodes.push({
          id: resource.id,
          name: resource.lastname,
          type: 'resource' as const
        })
      })

      resolve(nodes)
    } catch (error) {
      console.error('加载子部门或人员失败:', error)
      resolve([])
    }
  } else {
    resolve([])
  }
}

const resourceCount = computed(() => {
  const resourceIds = new Set(reqList.value.map(r => r.resourceId))
  return resourceIds.size
})

const elementCount = computed(() => {
  const elementIds = new Set(reqList.value.map(r => r.elementId))
  return elementIds.size
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  resourceId: undefined as number | undefined,
  elementConfigs: [{
    elementId: undefined,
    levelId: undefined,
    score: 0,
    levelRequirement: '',
    description: '',
  }] as ElementConfig[],
})

const formRules: FormRules = {
  resourceId: [
    { required: true, message: '请选择人员', trigger: 'change' },
  ],
}

const loadData = async () => {
  try {
    reqList.value = await listUserAbilityReqs()
  } catch (error) {
    console.error('加载数据失败:', error)
  }
}

const loadElementList = async () => {
  try {
    elementList.value = await listAbilityElements()
  } catch (error) {
    console.error('加载能力要素列表失败:', error)
  }
}

const loadElementLevels = async (elementId: number) => {
  if (elementLevelMap.value.has(elementId)) {
    return
  }
  try {
    const levels = await listAbilityElementLevelsByElementId(elementId)
    elementLevelMap.value.set(elementId, levels)
  } catch (error) {
    console.error('加载等级配置失败:', error)
  }
}

const getElementLevels = (elementId: number | undefined): AbilityElementLevelVO[] => {
  if (!elementId) return []
  return elementLevelMap.value.get(elementId) || []
}

const handleElementChange = async (elementId: number, index: number) => {
  if (elementId) {
    await loadElementLevels(elementId)
    formData.elementConfigs[index].levelId = undefined
    formData.elementConfigs[index].score = 0
    formData.elementConfigs[index].levelRequirement = ''
  }
}

const handleLevelChange = (levelId: number, index: number) => {
  const elementId = formData.elementConfigs[index].elementId
  if (elementId && levelId) {
    const levels = getElementLevels(elementId)
    const level = levels.find(l => l.id === levelId)
    if (level) {
      formData.elementConfigs[index].score = level.score
      formData.elementConfigs[index].levelRequirement = level.levelRequirement || ''
    }
  }
}

const addElementConfig = () => {
  formData.elementConfigs.push({
    elementId: undefined,
    levelId: undefined,
    score: 0,
    levelRequirement: '',
    description: '',
  })
}

const removeElementConfig = (index: number) => {
  formData.elementConfigs.splice(index, 1)
}

const handleAdd = () => {
  dialogTitle.value = '新增配置'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: UserAbilityReqVO) => {
  dialogTitle.value = '编辑配置'
  formData.id = row.id
  formData.resourceId = row.resourceId
  formData.elementConfigs = [{
    elementId: row.elementId,
    levelId: row.levelId,
    score: row.score,
    levelRequirement: row.levelRequirement || '',
    description: row.description || '',
  }]

  if (row.elementId) {
    await loadElementLevels(row.elementId)
  }

  dialogVisible.value = true
}

const handleDelete = async (row: UserAbilityReqVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该配置吗？删除后不可恢复。', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await deleteUserAbilityReq(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.resourceId) {
      ElMessage.error('请选择人员')
      return
    }

    // 验证所有能力要素配置
    for (const config of formData.elementConfigs) {
      if (!config.elementId || !config.levelId) {
        ElMessage.error('请完整填写所有能力要素配置')
        return
      }
    }

    if (formData.id) {
      // 编辑模式：只更新第一条记录
      const config = formData.elementConfigs[0]
      await updateUserAbilityReq({
        id: formData.id,
        resourceId: formData.resourceId,
        elementId: config.elementId,
        levelId: config.levelId,
        score: config.score,
        description: config.description,
      })
      ElMessage.success('更新成功')
    } else {
      // 新增模式：一对多创建
      await createUserAbilityReq({
        resourceId: formData.resourceId,
        elementConfigs: formData.elementConfigs.map(config => ({
          elementId: config.elementId!,
          levelId: config.levelId!,
          score: config.score,
          description: config.description,
        })),
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
  formData.resourceId = undefined
  formData.elementConfigs = [{
    elementId: undefined,
    levelId: undefined,
    score: 0,
    levelRequirement: '',
    description: '',
  }]
  formRef.value?.resetFields()
}

onMounted(() => {
  loadData()
  loadElementList()
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

.resource-name {
  font-weight: 500;
  color: #1D2129;
}

.element-name {
  color: #1D2129;
}

.level-tag {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #EFF4FF;
  color: #2563EB;
}

.score {
  font-weight: 600;
  color: #F53F3F;
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
  max-height: 500px;
  overflow-y: auto;
  padding-right: 8px;
}

.element-config-item {
  padding: 16px;
  background: #F7F8FA;
  border-radius: 8px;
  margin-bottom: 12px;
}

.config-row {
  display: grid;
  grid-template-columns: 2fr 1.5fr 1fr auto;
  gap: 12px;
  align-items: end;
}

.config-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-field label {
  font-size: 13px;
  color: #4E5969;
  font-weight: 500;
}

.level-requirement {
  margin-top: 12px;
  padding: 10px;
  background: #FFF7E8;
  border-radius: 4px;
  font-size: 13px;
  color: #FF7D00;
}

.level-requirement strong {
  color: #FF7D00;
}

.add-config-btn {
  width: 100%;
  margin-top: 12px;
}

/* 滚动条样式 */
.form-content::-webkit-scrollbar,
.element-configs::-webkit-scrollbar {
  width: 6px;
}

.form-content::-webkit-scrollbar-thumb,
.element-configs::-webkit-scrollbar-thumb {
  background: #C9CDD4;
  border-radius: 3px;
}

.form-content::-webkit-scrollbar-thumb:hover,
.element-configs::-webkit-scrollbar-thumb:hover {
  background: #86909C;
}

/* 对话框样式 */
.form-dialog :deep(.el-dialog__body) {
  padding: 20px;
  max-height: calc(90vh - 150px);
  overflow-y: auto;
}
</style>
