<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">能力要素管理</h1>
        <p class="page-description">管理各类别下的能力要素，配置5个等级要求</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增要素</el-button>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">总要素数</div>
        <div class="stat-value">{{ elementList.length }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">已启用</div>
        <div class="stat-value">{{ activeCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">类别覆盖</div>
        <div class="stat-value">{{ categoryCoverage }}</div>
      </div>
    </div>

    <!-- 筛选区 + 表格 -->
    <div class="card">
      <div class="card-header">
        <div class="filter-section">
          <label class="filter-label">所属类别</label>
          <el-select
            v-model="filterForm.categoryId"
            placeholder="全部类别"
            clearable
            class="filter-select"
          >
            <el-option
              v-for="item in categoryList"
              :key="item.id"
              :label="item.categoryName"
              :value="item.id"
            />
          </el-select>
          <el-button @click="loadData">查询</el-button>
          <el-button link @click="resetFilter">重置</el-button>
        </div>
      </div>
      <div class="card-body">
        <el-table :data="elementList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="elementName" label="要素名称" min-width="180">
            <template #default="{ row }">
              <span class="element-name">{{ row.elementName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="elementCode" label="编码" width="120">
            <template #default="{ row }">
              <span v-if="row.elementCode" class="element-code">{{ row.elementCode }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="categoryName" label="所属类别" width="150">
            <template #default="{ row }">
              <span class="category-tag">{{ row.categoryName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="250" show-overflow-tooltip />
          <el-table-column label="流程评价" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="row.assessInFlow === 0 ? 'disabled' : 'active'">
                {{ row.assessInFlow === 0 ? '免评' : '需评' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="客户评价" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="row.allowClientAssess === 0 ? 'disabled' : 'active'">
                {{ row.allowClientAssess === 0 ? '免客户' : '需客户' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="handleConfigLevels(row)">配置等级</el-button>
              <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
              <el-button link :type="row.status === 1 ? 'warning' : 'success'"
                @click="handleToggleStatus(row)"
              >{{ row.status === 1 ? '停用' : '启用' }}</el-button>
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
      width="680px"
      top="8vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-position="top"
      >
        <div class="form-section">
          <h3 class="form-section-title">基本信息</h3>
          <div class="form-grid">
            <el-form-item label="要素名称" prop="elementName">
              <el-input v-model="formData.elementName" placeholder="请输入要素名称" maxlength="100" show-word-limit />
            </el-form-item>
            <el-form-item label="要素编码" prop="elementCode">
              <el-input v-model="formData.elementCode" placeholder="可选，如 ELE-001" />
            </el-form-item>
          </div>
          <el-form-item label="描述" prop="description">
            <el-input
              v-model="formData.description"
              type="textarea"
              :rows="3"
              placeholder="请输入描述信息"
            />
          </el-form-item>
        </div>

        <div class="form-section">
          <h3 class="form-section-title">归属与设置</h3>
          <div class="form-grid">
            <el-form-item label="所属类别" prop="categoryId">
              <el-select v-model="formData.categoryId" placeholder="请选择类别" class="full-width">
                <el-option
                  v-for="item in categoryList"
                  :key="item.id"
                  :label="item.categoryName"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="排序" prop="sort">
              <el-input-number v-model="formData.sort" :min="0" :max="999" controls-position="right" class="full-width" />
            </el-form-item>
          </div>
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="formData.status">
              <el-radio-button :value="1">启用</el-radio-button>
              <el-radio-button :value="0">禁用</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="需评价">
            <el-switch
              v-model="formData.assessInFlow"
              :active-value="1"
              :inactive-value="0"
              active-text="需要"
              inactive-text="不需要"
            />
            <div class="form-hint">不需要时，该要素不进入测评流程页面（要求配置仍保留），权重由同类别其余需评价要素分摊</div>
          </el-form-item>
          <el-form-item label="需客户评价">
            <el-switch
              v-model="formData.allowClientAssess"
              :disabled="formData.assessInFlow === 0"
              :active-value="1"
              :inactive-value="0"
              active-text="需要"
              inactive-text="不需要"
            />
            <div class="form-hint">
              {{ formData.assessInFlow === 0 ? '要素不需评价，客户开关自动为不需要' : '不需要时，客户对该要素的评分按 0 计入加权总分' }}
            </div>
          </el-form-item>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 等级配置对话框 -->
    <el-dialog
      v-model="levelDialogVisible"
      :title="`配置等级 - ${currentElement?.elementName || ''}`"
      width="800px"
      class="level-dialog"
    >
      <div class="level-config-container">
        <div v-for="levelNum in 5" :key="levelNum" class="level-card">
          <div class="level-header">
            <h3 class="level-title">等级{{ levelNum }}</h3>
            <span class="level-badge" :class="'level-' + levelNum">{{ getLevelName(levelNum) }}</span>
          </div>
          <el-form
            :model="levelForms[levelNum - 1]"
            label-width="80px"
            class="level-form"
          >
            <el-form-item label="等级名称">
              <el-input v-model="levelForms[levelNum - 1].levelName" placeholder="如：知识级" />
            </el-form-item>
            <el-form-item label="等级要求">
              <el-input
                v-model="levelForms[levelNum - 1].levelRequirement"
                type="textarea"
                :rows="4"
                placeholder="请输入该等级的要求描述"
              />
            </el-form-item>
            <el-form-item label="分数">
              <el-input-number
                v-model="levelForms[levelNum - 1].score"
                :min="0"
                :max="100"
                :precision="2"
                style="width: 100%"
              />
            </el-form-item>
          </el-form>
        </div>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="levelDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="handleSubmitLevels">保存配置</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { pageAbilityElements, pageElementsByCategoryId, createAbilityElement, updateAbilityElement, deleteAbilityElement } from '@/api/abilityElement'
import { listAbilityCategories } from '@/api/abilityCategory'
import { listAbilityElementLevelsByElementId, createAbilityElementLevel, updateAbilityElementLevel } from '@/api/abilityElementLevel'
import type { AbilityElementVO } from '@/types/abilityElement'
import type { AbilityCategoryVO } from '@/types/abilityCategory'

const elementList = ref<AbilityElementVO[]>([])
const categoryList = ref<AbilityCategoryVO[]>([])

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

const filterForm = reactive({
  categoryId: undefined as number | undefined,
})

const activeCount = computed(() => pagination.total)
const categoryCoverage = computed(() => {
  const categoryIds = new Set(elementList.value.map(e => e.categoryId))
  return categoryIds.size
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  elementName: '',
  elementCode: '',
  categoryId: undefined as number | undefined,
  description: '',
  assessInFlow: 1,
  allowClientAssess: 1,
  status: 1,
  sort: 0,
})

const formRules: FormRules = {
  elementName: [
    { required: true, message: '请输入要素名称', trigger: 'blur' },
    { max: 100, message: '要素名称长度不能超过100', trigger: 'blur' },
  ],
  categoryId: [
    { required: true, message: '请选择所属类别', trigger: 'change' },
  ],
}

// 等级配置相关
const levelDialogVisible = ref(false)
const currentElement = ref<AbilityElementVO | null>(null)

interface LevelForm {
  id?: number
  level: number
  levelName: string
  levelRequirement: string
  score: number
}

const levelForms = reactive<LevelForm[]>([
  { level: 1, levelName: '知识级', levelRequirement: '', score: 60 },
  { level: 2, levelName: '规范级', levelRequirement: '', score: 70 },
  { level: 3, levelName: '技巧级', levelRequirement: '', score: 80 },
  { level: 4, levelName: '技能级', levelRequirement: '', score: 90 },
  { level: 5, levelName: '变通级', levelRequirement: '', score: 100 },
])

const getLevelName = (level: number): string => {
  const names: Record<number, string> = {
    1: '知识级',
    2: '规范级',
    3: '技巧级',
    4: '技能级',
    5: '变通级',
  }
  return names[level] || ''
}

const loadData = async () => {
  try {
    let result
    if (filterForm.categoryId) {
      result = await pageElementsByCategoryId(filterForm.categoryId, pagination.currentPage, pagination.pageSize)
    } else {
      result = await pageAbilityElements(pagination.currentPage, pagination.pageSize)
    }
    elementList.value = result.list || []
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

const loadCategoryList = async () => {
  try {
    categoryList.value = await listAbilityCategories()
  } catch (error) {
    console.error('加载类别列表失败:', error)
  }
}

const resetFilter = () => {
  filterForm.categoryId = undefined
  pagination.currentPage = 1
  loadData()
}

const handleAdd = () => {
  dialogTitle.value = '新增要素'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: AbilityElementVO) => {
  dialogTitle.value = '编辑要素'
  Object.assign(formData, row)
  dialogVisible.value = true
}

const handleDelete = async (row: AbilityElementVO) => {
  try {
    await ElMessageBox.confirm(`确定要删除要素"${row.elementName}"吗？删除后不可恢复。`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await deleteAbilityElement(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleToggleStatus = async (row: AbilityElementVO) => {
  const action = row.status === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确定要${action}要素"${row.elementName}"吗？`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await updateAbilityElement({
      id: row.id,
      status: row.status === 1 ? 0 : 1
    })
    ElMessage.success(`${action}成功`)
    await loadData()
  } catch (error) {
    if (error !== 'cancel') {
      console.error(`${action}失败:`, error)
    }
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.categoryId) {
      ElMessage.error('请选择所属类别')
      return
    }

    if (formData.id) {
      await updateAbilityElement({
        ...formData,
        categoryId: formData.categoryId,
      })
      ElMessage.success('更新成功')
    } else {
      await createAbilityElement({
        ...formData,
        categoryId: formData.categoryId,
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
  formData.elementName = ''
  formData.elementCode = ''
  formData.categoryId = undefined
  formData.description = ''
  formData.assessInFlow = 1
  formData.allowClientAssess = 1
  formData.status = 1
  formData.sort = 0
  formRef.value?.resetFields()
}

// 等级配置相关方法
const handleConfigLevels = async (row: AbilityElementVO) => {
  currentElement.value = row

  // 加载已有的等级配置
  try {
    const levels = await listAbilityElementLevelsByElementId(row.id)

    // 重置表单
    for (let i = 0; i < 5; i++) {
      levelForms[i] = {
        level: i + 1,
        levelName: getLevelName(i + 1),
        levelRequirement: '',
        score: 60 + i * 10,
      }
    }

    // 填充已有配置
    levels.forEach(level => {
      const index = level.level - 1
      if (index >= 0 && index < 5) {
        levelForms[index] = {
          id: level.id,
          level: level.level,
          levelName: level.levelName,
          levelRequirement: level.levelRequirement || '',
          score: level.score,
        }
      }
    })

    levelDialogVisible.value = true
  } catch (error) {
    console.error('加载等级配置失败:', error)
    ElMessage.error('加载等级配置失败')
  }
}

const handleSubmitLevels = async () => {
  if (!currentElement.value) return

  try {
    for (const form of levelForms) {
      if (form.id) {
        // 更新已有配置
        await updateAbilityElementLevel({
          id: form.id,
          levelName: form.levelName,
          levelRequirement: form.levelRequirement,
          score: form.score,
        })
      } else {
        // 创建新配置
        await createAbilityElementLevel({
          elementId: currentElement.value.id,
          level: form.level,
          levelName: form.levelName,
          levelRequirement: form.levelRequirement,
          score: form.score,
        })
      }
    }

    ElMessage.success('等级配置保存成功')
    levelDialogVisible.value = false
  } catch (error) {
    console.error('保存等级配置失败:', error)
    ElMessage.error('保存等级配置失败')
  }
}

onMounted(() => {
  loadData()
  loadCategoryList()
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
  padding: 8px 16px;
  background: var(--color-card);
  color: var(--color-text-secondary);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: var(--color-bg);
  border-color: #C9CDD4;
}

.btn-text {
  padding: 8px 12px;
  background: none;
  color: var(--color-primary);
  border: none;
  font-size: 13px;
  cursor: pointer;
}

.btn-text:hover {
  color: var(--color-primary-dark);
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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

.card-body {
  padding: 0;
}

/* 筛选区 */
.filter-section {
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-label {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.filter-select {
  width: 200px;
}

/* 表格 */
.data-table {
  width: 100%;
}

.element-name {
  font-weight: 500;
  color: var(--color-text);
}

.element-code {
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  color: var(--color-text-muted);
  background: var(--color-bg);
  padding: 2px 8px;
  border-radius: 4px;
}

.category-tag {
  display: inline-block;
  padding: 4px 10px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.text-muted {
  color: #C9CDD4;
}

.status-tag {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.status-tag.active {
  background: var(--color-success-light);
  color: var(--color-success);
}

.status-tag.disabled {
  background: var(--color-bg);
  color: var(--color-text-muted);
}

.form-hint {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
  margin-top: 4px;
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

.action-btn.config {
  color: var(--color-warning);
}

.action-btn.config:hover {
  background: var(--color-warning-light);
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

.action-btn.enable {
  color: var(--color-success);
}

.action-btn.enable:hover {
  background: var(--color-success-light);
}

.action-btn.disable {
  color: var(--color-warning);
}

.action-btn.disable:hover {
  background: var(--color-warning-light);
}

/* 对话框 */
.form-content {
  padding: 20px 0;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

/* 等级配置 */
.level-config-container {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  padding: 10px;
}

.level-card {
  background: var(--color-bg);
  border-radius: 8px;
  padding: 16px;
  border: 1px solid var(--color-border);
}

.level-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border);
}

.level-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  margin: 0;
}

.level-badge {
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.level-badge.level-1 {
  background: var(--color-bg);
  color: var(--color-text-secondary);
}

.level-badge.level-2 {
  background: #E8F3FF;
  color: var(--color-primary);
}

.level-badge.level-3 {
  background: var(--color-warning-light);
  color: var(--color-warning);
}

.level-badge.level-4 {
  background: var(--color-danger-light);
  color: var(--color-danger);
}

.level-badge.level-5 {
  background: var(--color-success-light);
  color: var(--color-success);
}

.level-form {
  margin-top: 12px;
}

.level-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.level-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.level-dialog :deep(.el-dialog) {
  height: 84vh !important;
  max-height: 84vh !important;
  display: flex;
  flex-direction: column;
  margin: 8vh auto !important;
}

.level-dialog :deep(.el-dialog__body) {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.level-dialog :deep(.el-dialog__footer) {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
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
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.full-width {
  width: 100%;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<!-- 弹窗被 teleport 到 body，必须用非 scoped 样式 -->
<style>
.level-dialog {
  height: 84vh !important;
  max-height: 84vh !important;
  display: flex;
  flex-direction: column;
  margin: 8vh auto !important;
}

.level-dialog .el-dialog__body {
  padding: 20px;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.level-dialog .el-dialog__footer {
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
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
