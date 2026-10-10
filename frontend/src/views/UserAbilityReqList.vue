<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">人员能力要求配置</h1>
        <p class="page-description">为人员配置能力要素要求等级，一主多从结构</p>
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
        <div class="stat-value">{{ pagination.total }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">能力要素总条数</div>
        <div class="stat-value">{{ totalItemCount }}</div>
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
                <h4>能力要素明细（{{ row.items?.length || 0 }}条）</h4>
                <el-table :data="row.items" size="small" border>
                  <el-table-column prop="categoryName" label="能力类别" width="120" />
                  <el-table-column prop="elementName" label="能力要素" min-width="150" />
                  <el-table-column prop="levelName" label="要求等级" width="120">
                    <template #default="{ row: item }">
                      {{ item.levelName || '未设置' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="score" label="分数" width="100">
                    <template #default="{ row: item }">
                      {{ item.score ?? '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="levelRequirement" label="等级要求" min-width="200" show-overflow-tooltip>
                    <template #default="{ row: item }">
                      {{ item.levelRequirement || '-' }}
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="resourceLastName" label="人员" width="150">
            <template #default="{ row }">
              <span class="resource-name">{{ row.resourceLastName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="能力要素数" width="120" align="center">
            <template #default="{ row }">
              <span class="item-count">{{ row.items?.length || 0 }} 条</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
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
      width="900px"
      class="form-dialog"
      top="8vh"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        class="form-content"
      >
        <el-form-item label="人员" prop="resourceId">
          <ResourceAutocomplete
            v-model="formData.resourceId"
            placeholder="请选择或搜索人员"
            @change="handleResourceChange"
          />
          <div class="form-tip">从泛微系统人员表中选择（支持姓名和拼音搜索）</div>
        </el-form-item>

        <el-form-item label="描述">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            placeholder="请输入描述信息"
          />
        </el-form-item>

        <el-divider />

        <el-form-item label="能力要素配置">
          <div class="element-configs">
            <div v-if="formData.items.length === 0" class="empty-tip">
              请先选择人员，系统会根据人员所在部门和岗位自动带出能力要素
            </div>
            <div v-for="(item, index) in formData.items" :key="index" class="element-config-item">
              <div class="config-row">
                <div class="config-field">
                  <label>能力类别</label>
                  <div class="readonly-field">{{ item.categoryName || '-' }}</div>
                </div>
                <div class="config-field">
                  <label>能力要素</label>
                  <div class="readonly-field">{{ item.elementName || '-' }}</div>
                </div>
                <div class="config-field">
                  <label>要求等级</label>
                  <el-select
                    v-model="item.levelId"
                    placeholder="请选择等级"
                    clearable
                    @change="(val: number | undefined) => handleLevelChange(val, index)"
                  >
                    <el-option
                      v-for="level in getElementLevels(item.elementId)"
                      :key="level.id"
                      :label="level.levelName"
                      :value="level.id"
                    />
                  </el-select>
                </div>
                <div class="config-field">
                  <label>分数</label>
                  <div class="readonly-field score-field">{{ item.score ?? '-' }}</div>
                </div>
              </div>
              <div v-if="getLevelRequirement(item.levelId)" class="level-requirement">
                <strong>等级要求：</strong>{{ getLevelRequirement(item.levelId) }}
              </div>
            </div>
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
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { pageUserAbilityReqs, createUserAbilityReq, updateUserAbilityReq, deleteUserAbilityReq } from '@/api/userAbilityReq'
import { listAbilityElementLevelsByElementId } from '@/api/abilityElementLevel'
import { getPositionAbilityReqByDeptAndJobTitle } from '@/api/positionAbilityReq'
import type { UserAbilityReqVO } from '@/types/userAbilityReq'
import type { AbilityElementLevelVO } from '@/types/abilityElementLevel'
import type { PositionAbilityReqVO } from '@/types/positionAbilityReq'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'

interface ItemConfig {
  categoryId: number
  categoryName: string
  elementId: number
  elementName: string
  levelId: number | undefined
  score: number | null
}

const reqList = ref<UserAbilityReqVO[]>([])
const elementLevelMap = ref<Map<number, AbilityElementLevelVO[]>>(new Map())

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

const totalItemCount = computed(() => {
  return (reqList.value || []).reduce((sum, req) => sum + (req.items?.length || 0), 0)
})

// 人员选择变化处理
const handleResourceChange = async (_value: number | undefined, data: any) => {
  if (!data) {
    formData.items = []
    return
  }

  const departmentId = data.departmentid
  const jobTitleId = data.jobtitle

  if (!departmentId || departmentId <= 0) {
    ElMessage.warning('该人员未配置部门，无法自动带出能力要素')
    return
  }

  if (!jobTitleId || jobTitleId <= 0) {
    ElMessage.warning('该人员未配置岗位，无法自动带出能力要素')
    return
  }

  try {
    const positionReq: PositionAbilityReqVO | null = await getPositionAbilityReqByDeptAndJobTitle(departmentId, jobTitleId)

    if (!positionReq || !positionReq.items || positionReq.items.length === 0) {
      ElMessage.warning('该岗位未配置能力要求')
      formData.items = []
      return
    }

    formData.items = positionReq.items.map(item => ({
      categoryId: item.categoryId,
      categoryName: item.categoryName || '',
      elementId: item.elementId,
      elementName: item.elementName || '',
      levelId: undefined,
      score: null,
    }))

    for (const item of formData.items) {
      if (item.elementId) {
        await loadElementLevels(item.elementId)
      }
    }

    ElMessage.success(`已自动带出 ${formData.items.length} 个能力要素`)
  } catch (error) {
    console.error('获取岗位能力配置失败:', error)
    ElMessage.error('获取岗位能力配置失败')
  }
}

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  resourceId: undefined as number | undefined,
  description: '',
  items: [] as ItemConfig[],
})

const formRules: FormRules = {
  resourceId: [
    { required: true, message: '请选择人员', trigger: 'change' },
  ],
}

const loadData = async () => {
  try {
    const result = await pageUserAbilityReqs(pagination.currentPage, pagination.pageSize)
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

const getElementLevels = (elementId: number): AbilityElementLevelVO[] => {
  if (!elementId) return []
  return elementLevelMap.value.get(elementId) || []
}

const handleLevelChange = (levelId: number | undefined, index: number) => {
  const item = formData.items[index]
  if (levelId) {
    const levels = getElementLevels(item.elementId)
    const level = levels.find(l => l.id === levelId)
    if (level) {
      formData.items[index].score = level.score
    }
  } else {
    formData.items[index].score = null
  }
}

const getLevelRequirement = (levelId: number | undefined): string => {
  if (!levelId) return ''
  for (const levels of elementLevelMap.value.values()) {
    const level = levels.find(l => l.id === levelId)
    if (level?.levelRequirement) {
      return level.levelRequirement
    }
  }
  return ''
}

const handleAdd = async () => {
  dialogTitle.value = '新增配置'
  await resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: UserAbilityReqVO) => {
  dialogTitle.value = '编辑配置'
  formData.id = row.id
  formData.resourceId = row.resourceId
  formData.description = row.description || ''

  formData.items = (row.items || []).map(item => ({
    categoryId: item.categoryId,
    categoryName: item.categoryName || '',
    elementId: item.elementId,
    elementName: item.elementName || '',
    levelId: item.levelId || undefined,
    score: item.score,
  }))

  for (const item of formData.items) {
    if (item.elementId) {
      await loadElementLevels(item.elementId)
    }
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

    if (formData.items.length === 0) {
      ElMessage.error('请先选择人员以自动带出能力要素')
      return
    }

    for (const item of formData.items) {
      if (!item.elementId) {
        ElMessage.error('能力要素不能为空')
        return
      }
    }

    if (formData.id) {
      await updateUserAbilityReq({
        id: formData.id,
        resourceId: formData.resourceId,
        description: formData.description,
        items: formData.items.map(item => ({
          categoryId: item.categoryId,
          elementId: item.elementId,
          levelId: item.levelId,
          score: item.score,
        })),
      })
      ElMessage.success('更新成功')
    } else {
      await createUserAbilityReq({
        resourceId: formData.resourceId,
        description: formData.description,
        items: formData.items.map(item => ({
          categoryId: item.categoryId,
          elementId: item.elementId,
          levelId: item.levelId,
          score: item.score,
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

const resetForm = async () => {
  formData.id = 0
  formData.resourceId = undefined
  formData.description = ''
  formData.items = []
  await nextTick()
  formRef.value?.resetFields()
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.page-container {
  max-width: 1400px;
  margin: 0 auto;
}

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

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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

.data-table {
  width: 100%;
}

.resource-name {
  font-weight: 500;
  color: #1D2129;
}

.item-count {
  color: #2563EB;
  font-weight: 500;
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

.expand-content {
  padding: 16px 20px;
}

.expand-content h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  font-weight: 600;
  color: #1D2129;
}

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

.element-configs {
  width: 100%;
  max-height: 500px;
  overflow-y: auto;
  padding-right: 8px;
}

.empty-tip {
  padding: 40px 20px;
  text-align: center;
  color: #86909C;
  font-size: 14px;
  background: #F7F8FA;
  border-radius: 8px;
}

.element-config-item {
  padding: 8px;
  background: #F7F8FA;
  border-radius: 6px;
  margin-bottom: 6px;
}

.config-row {
  display: grid;
  grid-template-columns: 1.5fr 2fr 1.5fr 1fr;
  gap: 6px;
  align-items: center;
}

.config-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.config-field label {
  font-size: 11px;
  color: #86909C;
  font-weight: 400;
}

.readonly-field {
  padding: 4px 8px;
  background: #FFFFFF;
  border: 1px solid #E5E6EB;
  border-radius: 3px;
  font-size: 12px;
  color: #1D2129;
  min-height: 24px;
  display: flex;
  align-items: center;
}

.score-field {
  font-weight: 600;
  color: #F53F3F;
}

.level-requirement {
  margin-top: 4px;
  padding: 4px 6px;
  background: transparent;
  border-radius: 3px;
  font-size: 11px;
  color: #86909C;
  line-height: 1.3;
}

.level-requirement strong {
  color: #FF7D00;
}

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
</style>

<!-- 非 scoped 样式，用于穿透 el-dialog -->
<style>
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

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0;
}
</style>
