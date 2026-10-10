<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">评分权重配置</h1>
        <p class="page-description">配置能力测评的评分权重，类别权重和要素权重之和必须等于100%</p>
      </div>
      <button class="btn-primary" @click="handleAdd">
        <span class="btn-icon">+</span>
        新增方案
      </button>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">方案总数</div>
        <div class="stat-value">{{ pagination.total }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">涉及分公司</div>
        <div class="stat-value">{{ companyCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">平均类别权重</div>
        <div class="stat-value">{{ avgCategoryWeight }}%</div>
      </div>
    </div>

    <!-- 数据表格 -->
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">权重方案列表</h2>
      </div>
      <div class="card-body">
        <el-table :data="weightList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="companyName" label="分公司" width="150">
            <template #default="{ row }">
              <span class="org-name">{{ row.companyName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="weightName" label="方案名称" min-width="180">
            <template #default="{ row }">
              <span class="weight-name">{{ row.weightName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="权重分配" min-width="300">
            <template #default="{ row }">
              <div class="weight-bar">
                <div class="weight-bar-category" :style="{ width: row.categoryWeight + '%' }">
                  <span>类别 {{ row.categoryWeight }}%</span>
                </div>
                <div class="weight-bar-element" :style="{ width: row.elementWeight + '%' }">
                  <span>要素 {{ row.elementWeight }}%</span>
                </div>
              </div>
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
      width="600px"
      class="form-dialog"
      top="8vh"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="110px"
        class="form-content"
      >
        <el-form-item label="分公司ID" prop="companyId">
          <el-input-number v-model="formData.companyId" :min="1" style="width: 100%" />
          <div class="form-tip">请输入泛微系统中的分公司ID</div>
        </el-form-item>
        <el-form-item label="方案名称" prop="weightName">
          <el-input v-model="formData.weightName" placeholder="请输入方案名称" />
        </el-form-item>
        <el-form-item label="权重分配" prop="categoryWeight">
          <div class="weight-input-section">
            <div class="weight-input-group">
              <label>类别权重</label>
              <el-slider
                v-model="formData.categoryWeight"
                :min="0"
                :max="100"
                :step="5"
                :format-tooltip="(val: number) => val + '%'"
                @change="updateElementWeight"
              />
              <span class="weight-value">{{ formData.categoryWeight }}%</span>
            </div>
            <div class="weight-input-group">
              <label>要素权重</label>
              <el-slider
                v-model="formData.elementWeight"
                :min="0"
                :max="100"
                :step="5"
                :format-tooltip="(val: number) => val + '%'"
                disabled
              />
              <span class="weight-value">{{ formData.elementWeight }}%</span>
            </div>
            <div class="weight-total" :class="{ valid: isWeightValid }">
              总计: {{ formData.categoryWeight + formData.elementWeight }}%
              <span v-if="!isWeightValid" class="weight-error">（必须等于100%）</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入方案说明（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <button class="btn-secondary" @click="dialogVisible = false">取消</button>
          <button class="btn-primary" @click="handleSubmit" :disabled="!isWeightValid">确定</button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { pageScoreWeights, createScoreWeight, updateScoreWeight, deleteScoreWeight } from '@/api/scoreWeight'
import type { ScoreWeightVO } from '@/types/scoreWeight'

const weightList = ref<ScoreWeightVO[]>([])

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

const companyCount = computed(() => {
  const companyIds = new Set(weightList.value.map(w => w.companyId))
  return companyIds.size
})

const avgCategoryWeight = computed(() => {
  const list = weightList.value || []
  if (list.length === 0) return 0
  const sum = list.reduce((acc, w) => acc + w.categoryWeight, 0)
  return Math.round(sum / list.length)
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  companyId: 1,
  weightName: '',
  categoryWeight: 30,
  elementWeight: 70,
  description: '',
})

const isWeightValid = computed(() => {
  return formData.categoryWeight + formData.elementWeight === 100
})

const formRules: FormRules = {
  companyId: [
    { required: true, message: '请输入分公司ID', trigger: 'blur' },
  ],
  weightName: [
    { required: true, message: '请输入方案名称', trigger: 'blur' },
    { max: 100, message: '方案名称长度不能超过100', trigger: 'blur' },
  ],
  categoryWeight: [
    { required: true, message: '请设置类别权重', trigger: 'change' },
  ],
}

const updateElementWeight = () => {
  formData.elementWeight = 100 - formData.categoryWeight
}

const loadData = async () => {
  try {
    const result = await pageScoreWeights(pagination.currentPage, pagination.pageSize)
    weightList.value = result.list || []
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

const handleAdd = () => {
  dialogTitle.value = '新增方案'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: ScoreWeightVO) => {
  dialogTitle.value = '编辑方案'
  Object.assign(formData, row)
  dialogVisible.value = true
}

const handleDelete = async (row: ScoreWeightVO) => {
  try {
    await ElMessageBox.confirm(`确定要删除方案"${row.weightName}"吗？删除后不可恢复。`, '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })

    await deleteScoreWeight(row.id)
    ElMessage.success('删除成功')
    await loadData()
  } catch (error) {
    console.error('删除失败:', error)
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!isWeightValid.value) {
      ElMessage.error('类别权重和要素权重之和必须等于100%')
      return
    }

    if (formData.id) {
      await updateScoreWeight(formData)
      ElMessage.success('更新成功')
    } else {
      await createScoreWeight(formData)
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
  formData.companyId = 1
  formData.weightName = ''
  formData.categoryWeight = 30
  formData.elementWeight = 70
  formData.description = ''
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

.btn-primary:disabled {
  background: #C9CDD4;
  cursor: not-allowed;
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

.org-name {
  font-weight: 500;
  color: #1D2129;
}

.weight-name {
  font-weight: 500;
  color: #1D2129;
}

/* 权重条 */
.weight-bar {
  display: flex;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  font-size: 12px;
  font-weight: 500;
}

.weight-bar-category {
  background: #2563EB;
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: width 0.3s ease;
}

.weight-bar-element {
  background: #10B981;
  color: #FFFFFF;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: width 0.3s ease;
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

/* 权重输入区 */
.weight-input-section {
  width: 100%;
}

.weight-input-group {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.weight-input-group label {
  width: 80px;
  font-size: 13px;
  color: #4E5969;
}

.weight-input-group .el-slider {
  flex: 1;
}

.weight-value {
  width: 50px;
  text-align: right;
  font-weight: 600;
  color: #1D2129;
}

.weight-total {
  padding: 12px;
  background: #F2F3F5;
  border-radius: 6px;
  text-align: center;
  font-size: 14px;
  font-weight: 500;
  color: #4E5969;
}

.weight-total.valid {
  background: #E8FFEA;
  color: #00B42A;
}

.weight-error {
  color: #F53F3F;
  margin-left: 8px;
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
