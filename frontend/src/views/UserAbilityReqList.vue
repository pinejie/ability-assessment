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
          <ResourceAutocomplete
            v-model="formData.resourceId"
            placeholder="请选择或搜索人员"
            @change="handleResourceChange"
          />
          <div class="form-tip">从泛微系统人员表中选择（只能选择人员，支持姓名和拼音搜索）</div>
        </el-form-item>

        <el-divider />

        <el-form-item label="能力要素配置">
          <div class="element-configs">
            <div v-if="formData.elementConfigs.length === 0 || !formData.elementConfigs[0].elementId" class="empty-tip">
              请先选择人员，系统会根据人员所在部门和岗位自动带出能力要素
            </div>
            <div v-for="(config, index) in formData.elementConfigs" :key="index" class="element-config-item">
              <div class="config-row">
                <div class="config-field">
                  <label>能力类别</label>
                  <div class="readonly-field">{{ config.categoryName || '-' }}</div>
                </div>
                <div class="config-field">
                  <label>能力要素</label>
                  <div class="readonly-field">{{ config.elementName || '-' }}</div>
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
                  <div class="readonly-field score-field">{{ config.score || '-' }}</div>
                </div>
              </div>
              <div v-if="config.levelRequirement" class="level-requirement">
                <strong>等级要求：</strong>{{ config.levelRequirement }}
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
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { listUserAbilityReqs, createUserAbilityReq, updateUserAbilityReq, deleteUserAbilityReq } from '@/api/userAbilityReq'
import { listAbilityElements } from '@/api/abilityElement'
import { listAbilityElementLevelsByElementId } from '@/api/abilityElementLevel'
import { getPositionAbilityReqByDeptAndJobTitle } from '@/api/positionAbilityReq'
import type { UserAbilityReqVO } from '@/types/userAbilityReq'
import type { AbilityElementVO } from '@/types/abilityElement'
import type { AbilityElementLevelVO } from '@/types/abilityElementLevel'
import type { PositionAbilityReqVO } from '@/types/positionAbilityReq'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'

interface ElementConfig {
  categoryId: number | undefined
  categoryName: string
  elementId: number | undefined
  elementName: string
  levelId: number | undefined
  score: number
  levelRequirement: string
  description?: string
}

const reqList = ref<UserAbilityReqVO[]>([])
const elementList = ref<AbilityElementVO[]>([])
const elementLevelMap = ref<Map<number, AbilityElementLevelVO[]>>(new Map())

// 人员选择变化处理
const handleResourceChange = async (_value: number | undefined, data: any) => {
  if (!data) {
    // 人员被清空
    formData.elementConfigs = [{
      categoryId: undefined,
      categoryName: '',
      elementId: undefined,
      elementName: '',
      levelId: undefined,
      score: 0,
      levelRequirement: '',
      description: '',
    }]
    return
  }

  // 根据人员的部门和岗位获取岗位能力配置
  const departmentId = data.departmentid
  const jobTitleId = data.jobtitle

  // 检查字段是否存在且大于 0（0 表示未配置）
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
      formData.elementConfigs = [{
        categoryId: undefined,
        categoryName: '',
        elementId: undefined,
        elementName: '',
        levelId: undefined,
        score: 0,
        levelRequirement: '',
        description: '',
      }]
      return
    }

    // 根据岗位配置填充元素列表
    formData.elementConfigs = positionReq.items.map(item => ({
      categoryId: item.categoryId,
      categoryName: item.categoryName || '',
      elementId: item.elementId,
      elementName: item.elementName || '',
      levelId: undefined,
      score: 0,
      levelRequirement: '',
      description: '',
    }))

    // 预加载每个要素的等级数据
    for (const config of formData.elementConfigs) {
      if (config.elementId) {
        await loadElementLevels(config.elementId)
      }
    }

    ElMessage.success(`已自动带出 ${formData.elementConfigs.length} 个能力要素`)
  } catch (error) {
    console.error('获取岗位能力配置失败:', error)
    ElMessage.error('获取岗位能力配置失败')
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
    categoryId: undefined,
    categoryName: '',
    elementId: undefined,
    elementName: '',
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

const handleAdd = () => {
  dialogTitle.value = '新增配置'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (row: UserAbilityReqVO) => {
  dialogTitle.value = '编辑配置'
  formData.id = row.id
  formData.resourceId = row.resourceId

  // 编辑模式：需要从 elementId 反查 categoryId 和 categoryName
  // 这里简化处理，从 elementList 中查找
  let categoryId: number | undefined = undefined
  let categoryName = ''
  const element = elementList.value.find(e => e.id === row.elementId)
  if (element) {
    categoryId = element.categoryId
    // 需要从 elementCategoryMap 中获取 categoryName，这里先简化
    categoryName = ''
  }

  formData.elementConfigs = [{
    categoryId,
    categoryName,
    elementId: row.elementId,
    elementName: row.elementName || '',
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

    if (formData.elementConfigs.length === 0 || !formData.elementConfigs[0].elementId) {
      ElMessage.error('请先选择人员以自动带出能力要素')
      return
    }

    // 验证所有能力要素配置
    for (const config of formData.elementConfigs) {
      if (!config.elementId || !config.levelId) {
        ElMessage.error('请为所有能力要素选择要求等级')
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
    categoryId: undefined,
    categoryName: '',
    elementId: undefined,
    elementName: '',
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

.empty-tip {
  padding: 40px 20px;
  text-align: center;
  color: #86909C;
  font-size: 14px;
  background: #F7F8FA;
  border-radius: 8px;
}

.element-config-item {
  padding: 16px;
  background: #F7F8FA;
  border-radius: 8px;
  margin-bottom: 12px;
}

.config-row {
  display: grid;
  grid-template-columns: 1.5fr 2fr 1.5fr 1fr;
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

.readonly-field {
  padding: 8px 12px;
  background: #FFFFFF;
  border: 1px solid #E5E6EB;
  border-radius: 4px;
  font-size: 14px;
  color: #1D2129;
  min-height: 32px;
  display: flex;
  align-items: center;
}

.score-field {
  font-weight: 600;
  color: #F53F3F;
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
