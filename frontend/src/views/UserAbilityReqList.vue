<template>
  <div class="page-wrapper">
    <div class="page-toolbar">
      <div>
        <h1 class="page-title">人员能力要求配置</h1>
        <p class="page-description">为人员配置能力要素要求等级，一主多从结构</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增配置</el-button>
    </div>

    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">配置总数</div>
        <div class="stat-value">{{ pagination.total }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">能力要素总条数</div>
        <div class="stat-value">{{ totalItemCount }}</div>
      </div>
    </div>

    <div class="page-card">
      <div class="page-toolbar">
        <h2 class="card-title">配置列表</h2>
      </div>

      <el-table :data="reqList" stripe>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-content">
              <div class="expand-title">能力要素明细（{{ row.items?.length || 0 }} 条）</div>
              <el-table :data="row.items" size="small" border>
                <el-table-column prop="categoryName" label="能力类别" width="130" />
                <el-table-column prop="elementName" label="能力要素" min-width="150" />
                <el-table-column label="要求等级" width="120">
                  <template #default="{ row: item }">
                    {{ item.levelName || '未设置' }}
                  </template>
                </el-table-column>
                <el-table-column label="分数" width="90">
                  <template #default="{ row: item }">
                    {{ item.score ?? '-' }}
                  </template>
                </el-table-column>
                <el-table-column label="等级要求" min-width="200" show-overflow-tooltip>
                  <template #default="{ row: item }">
                    {{ item.levelRequirement || '-' }}
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="resourceLastName" label="人员" width="150" />
        <el-table-column label="能力要素数" width="120" align="center">
          <template #default="{ row }">
            {{ row.items?.length || 0 }} 条
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
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

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="860px"
      top="6vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-position="top">
        <div class="form-section">
          <h3 class="form-section-title">基本信息</h3>
          <el-form-item label="人员" prop="resourceId">
            <ResourceAutocomplete
              v-model="formData.resourceId"
              placeholder="请选择或搜索人员"
              @change="handleResourceChange"
            />
            <div class="form-tip">从泛微系统人员表中选择，支持姓名和拼音搜索</div>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="请输入描述信息" />
          </el-form-item>
        </div>

        <div class="form-section">
          <h3 class="form-section-title">能力要素配置</h3>
          <div v-if="formData.items.length === 0" class="empty-tip">
            请先选择人员，系统会根据人员所在部门和岗位自动带出能力要素
          </div>
          <div v-for="(item, index) in formData.items" :key="index" class="element-row">
            <div class="element-meta">
              <div class="element-meta-item">
                <span class="element-meta-label">能力类别</span>
                <span>{{ item.categoryName || '-' }}</span>
              </div>
              <div class="element-meta-item">
                <span class="element-meta-label">能力要素</span>
                <span>{{ item.elementName || '-' }}</span>
              </div>
            </div>
            <div class="element-control">
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
              <div class="element-score">分数 {{ item.score ?? '-' }}</div>
            </div>
            <div v-if="getLevelRequirement(item.levelId)" class="level-requirement">
              等级要求：{{ getLevelRequirement(item.levelId) }}
            </div>
          </div>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
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
import type { ResourceItem } from '@/types/org'

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
const handleResourceChange = async (_value: number | undefined, data: ResourceItem | undefined) => {
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
.expand-content {
  padding: 8px 16px 12px;
}

.expand-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.empty-tip {
  color: var(--color-text-muted);
  font-size: 13px;
  padding: 12px 0;
}

.element-row {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 12px 14px;
  margin-bottom: 10px;
  background: var(--color-bg);
}

.element-meta {
  display: flex;
  gap: 24px;
  margin-bottom: 8px;
}

.element-meta-item {
  font-size: 14px;
  color: var(--color-text);
}

.element-meta-label {
  color: var(--color-text-muted);
  margin-right: 6px;
}

.element-control {
  display: flex;
  align-items: center;
  gap: 16px;
}

.element-control :deep(.el-select) {
  width: 220px;
}

.element-score {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.level-requirement {
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}
</style>
