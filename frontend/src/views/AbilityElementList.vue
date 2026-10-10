<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">能力要素管理</h1>
        <p class="page-description">管理各类别下的能力要素，配置5个等级要求</p>
      </div>
      <button class="btn-primary" @click="handleAdd">
        <span class="btn-icon">+</span>
        新增要素
      </button>
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
          <button class="btn-secondary" @click="loadData">查询</button>
          <button class="btn-text" @click="resetFilter">重置</button>
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
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <button class="action-btn config" @click="handleConfigLevels(row)">配置等级</button>
              <button class="action-btn edit" @click="handleEdit(row)">编辑</button>
              <button
                :class="['action-btn', row.status === 1 ? 'disable' : 'enable']"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '停用' : '启用' }}
              </button>
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
      width="520px"
      class="form-dialog"
      top="8vh"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="90px"
        class="form-content"
      >
        <el-form-item label="要素名称" prop="elementName">
          <el-input v-model="formData.elementName" placeholder="请输入要素名称" />
        </el-form-item>
        <el-form-item label="要素编码" prop="elementCode">
          <el-input v-model="formData.elementCode" placeholder="请输入要素编码（可选）" />
        </el-form-item>
        <el-form-item label="所属类别" prop="categoryId">
          <el-select v-model="formData.categoryId" placeholder="请选择类别" style="width: 100%">
            <el-option
              v-for="item in categoryList"
              :key="item.id"
              :label="item.categoryName"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入描述信息"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="formData.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="formData.sort" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <button class="btn-secondary" @click="dialogVisible = false">取消</button>
          <button class="btn-primary" @click="handleSubmit">确定</button>
        </div>
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
                :rows="2"
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
          <button class="btn-secondary" @click="levelDialogVisible = false">取消</button>
          <button class="btn-primary" @click="handleSubmitLevels">保存配置</button>
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
  padding: 8px 16px;
  background: #FFFFFF;
  color: #4E5969;
  border: 1px solid #E5E6EB;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: #F5F7FA;
  border-color: #C9CDD4;
}

.btn-text {
  padding: 8px 12px;
  background: none;
  color: #2563EB;
  border: none;
  font-size: 13px;
  cursor: pointer;
}

.btn-text:hover {
  color: #1D4ED8;
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
  color: #4E5969;
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
  color: #1D2129;
}

.element-code {
  font-family: 'SF Mono', Monaco, monospace;
  font-size: 12px;
  color: #86909C;
  background: #F2F3F5;
  padding: 2px 8px;
  border-radius: 4px;
}

.category-tag {
  display: inline-block;
  padding: 4px 10px;
  background: #EFF4FF;
  color: #2563EB;
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
  background: #E8FFEA;
  color: #00B42A;
}

.status-tag.disabled {
  background: #F2F3F5;
  color: #86909C;
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
  color: #FF7D00;
}

.action-btn.config:hover {
  background: #FFF7E8;
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

.action-btn.enable {
  color: #00B42A;
}

.action-btn.enable:hover {
  background: #E8FFEA;
}

.action-btn.disable {
  color: #FF7D00;
}

.action-btn.disable:hover {
  background: #FFF7E8;
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
  max-height: 600px;
  overflow-y: auto;
  padding: 10px;
}

.level-card {
  background: #F7F8FA;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #E5E6EB;
}

.level-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #E5E6EB;
}

.level-title {
  font-size: 16px;
  font-weight: 600;
  color: #1D2129;
  margin: 0;
}

.level-badge {
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.level-badge.level-1 {
  background: #F2F3F5;
  color: #4E5969;
}

.level-badge.level-2 {
  background: #E8F3FF;
  color: #2563EB;
}

.level-badge.level-3 {
  background: #FFF7E8;
  color: #FF7D00;
}

.level-badge.level-4 {
  background: #FFECE8;
  color: #F53F3F;
}

.level-badge.level-5 {
  background: #E8FFEA;
  color: #00B42A;
}

.level-form {
  margin-top: 12px;
}

.level-dialog :deep(.el-dialog__body) {
  padding: 20px;
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
  border-top: 1px solid #E5E6EB;
}
</style>
