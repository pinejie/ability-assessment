<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">维度管理</h1>
        <p class="page-description">维度即租户：类别、要素、要求、测评数据按维度隔离；顶栏切换维度</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增维度</el-button>
    </div>

    <!-- 表格 -->
    <div class="card">
      <div class="card-body">
        <el-table :data="dimensionList" class="data-table">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="dimensionName" label="维度名称" min-width="150" />
          <el-table-column prop="dimensionCode" label="编码" width="120">
            <template #default="{ row }">
              <span v-if="row.dimensionCode">{{ row.dimensionCode }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <span class="status-tag" :class="row.status === 1 ? 'active' : 'disabled'">
                {{ row.status === 1 ? '启用' : '禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="使用授权" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.authCount === 0" type="warning" effect="plain">未配置（仅管理员）</el-tag>
              <el-tag v-else type="info" effect="plain">{{ row.authCount }} 个对象</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sort" label="排序" width="80" />
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="handleConfigAuth(row)">授权配置</el-button>
              <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="维度名称" prop="dimensionName">
            <el-input v-model="formData.dimensionName" placeholder="如：销售条线" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="维度编码" prop="dimensionCode">
            <el-input v-model="formData.dimensionCode" placeholder="可选，如 DIM-SALES" maxlength="50" />
          </el-form-item>
        </div>
        <el-form-item label="描述" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="3" placeholder="请输入描述信息" />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="formData.status">
              <el-radio-button :value="1">启用</el-radio-button>
              <el-radio-button :value="0">禁用</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="排序" prop="sort">
            <el-input-number v-model="formData.sort" :min="0" :max="999" controls-position="right" class="full-width" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 授权配置对话框 -->
    <el-dialog
      v-model="authDialogVisible"
      :title="`授权配置 - ${currentDimension?.dimensionName || ''}`"
      width="680px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="未配置任何授权对象时，该维度仅管理员可见；分部授权只提供一级分部（勾选即覆盖该一级分部全部下级分支的人员），人员 ID 授权为精确命中。"
        style="margin-bottom: 16px"
      />

      <div class="auth-section">
        <h3 class="form-section-title">按分部授权</h3>
        <el-select
          v-model="authSubcompanyIds"
          multiple
          filterable
          clearable
          placeholder="选择一级分部（勾选后覆盖该分部全部下级分支人员）"
          style="width: 100%"
        >
          <el-option v-for="c in subCompanies" :key="c.id" :label="c.subcompanyname" :value="c.id" />
        </el-select>
      </div>

      <div class="auth-section">
        <h3 class="form-section-title">按人员授权</h3>
        <ResourceAutocomplete
          v-model="personPickerId"
          placeholder="搜索并选择人员后自动添加"
          @change="handleAddPerson"
        />
        <div v-if="authPersons.length" class="person-tags">
          <el-tag
            v-for="person in authPersons"
            :key="person.authId"
            closable
            type="primary"
            effect="plain"
            @close="removePerson(person.authId)"
          >
            {{ person.authName || `ID:${person.authId}` }}
          </el-tag>
        </div>
        <p v-else class="text-muted">暂未授权任何人员</p>
      </div>

      <template #footer>
        <el-button @click="authDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="authSaving" @click="handleSaveAuths">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'
import { listLevel1SubCompanies, type Level1SubCompany } from '@/api/ecology'
import {
  listAllDimensions,
  createDimension,
  updateDimension,
  deleteDimension,
  listDimensionAuths,
  saveDimensionAuths,
} from '@/api/assessDimension'
import {
  AUTH_TYPE_SUBCOMPANY,
  AUTH_TYPE_RESOURCE,
  type AssessDimensionVO,
  type AssessDimensionAuthVO,
} from '@/types/assessDimension'
import { useDimensionStore } from '@/stores/dimension'

const dimensionList = ref<AssessDimensionVO[]>([])
/** 一级分部全量列表（2026-10-22 决策：授权只面向一级分部；服务端按人员分部上级链命中，勾一级覆盖整分支） */
const subCompanies = ref<Level1SubCompany[]>([])
const dimensionStore = useDimensionStore()

const loadData = async () => {
  try {
    dimensionList.value = await listAllDimensions()
  } catch (error) {
    console.error('加载维度列表失败:', error)
  }
}

const loadSubCompanies = async () => {
  try {
    subCompanies.value = await listLevel1SubCompanies()
  } catch (error) {
    console.error('加载一级分部列表失败:', error)
  }
}

onMounted(() => {
  loadData()
  loadSubCompanies()
})

// ==================== 新增 / 编辑 ====================

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()

const formData = reactive({
  id: 0,
  dimensionName: '',
  dimensionCode: '',
  description: '',
  status: 1,
  sort: 0,
})

const formRules: FormRules = {
  dimensionName: [
    { required: true, message: '请输入维度名称', trigger: 'blur' },
    { max: 100, message: '维度名称长度不能超过100', trigger: 'blur' },
  ],
}

const handleAdd = () => {
  dialogTitle.value = '新增维度'
  Object.assign(formData, {
    id: 0,
    dimensionName: '',
    dimensionCode: '',
    description: '',
    status: 1,
    sort: 0,
  })
  dialogVisible.value = true
}

const handleEdit = (row: AssessDimensionVO) => {
  dialogTitle.value = '编辑维度'
  Object.assign(formData, {
    id: row.id,
    dimensionName: row.dimensionName,
    dimensionCode: row.dimensionCode || '',
    description: row.description || '',
    status: row.status,
    sort: row.sort,
  })
  dialogVisible.value = true
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (formData.id) {
      await updateDimension({
        id: formData.id,
        dimensionName: formData.dimensionName,
        dimensionCode: formData.dimensionCode || undefined,
        description: formData.description || undefined,
        status: formData.status,
        sort: formData.sort,
      })
      ElMessage.success('更新成功')
    } else {
      await createDimension({
        dimensionName: formData.dimensionName,
        dimensionCode: formData.dimensionCode || undefined,
        description: formData.description || undefined,
        status: formData.status,
        sort: formData.sort,
      })
      ElMessage.success('创建成功')
    }

    dialogVisible.value = false
    await loadData()
    await refreshSwitcher()
  } catch (error) {
    console.error('提交失败:', error)
  }
}

const handleDelete = async (row: AssessDimensionVO) => {
  try {
    await ElMessageBox.confirm(
      `确定删除维度「${row.dimensionName}」吗？删除后该维度下不允许存在业务数据。`,
      '删除确认',
      { type: 'warning' }
    )
    await deleteDimension(row.id)
    ElMessage.success('删除成功')
    await loadData()
    await refreshSwitcher()
  } catch (error) {
    // 用户取消或接口报错（已全局提示）
    console.error('删除失败:', error)
  }
}

/** 维度列表变化后同步顶栏切换器（当前维度可能被删/禁用） */
const refreshSwitcher = async () => {
  const before = dimensionStore.currentId
  await dimensionStore.load()
  if (dimensionStore.currentId !== before) {
    window.location.reload()
  }
}

// ==================== 授权配置 ====================

const authDialogVisible = ref(false)
const authSaving = ref(false)
const currentDimension = ref<AssessDimensionVO | null>(null)
const authSubcompanyIds = ref<number[]>([])
const authPersons = ref<AssessDimensionAuthVO[]>([])
const personPickerId = ref<number | undefined>(undefined)

const handleConfigAuth = async (row: AssessDimensionVO) => {
  currentDimension.value = row
  personPickerId.value = undefined
  try {
    const auths = await listDimensionAuths(row.id)
    authSubcompanyIds.value = auths.filter(a => a.authType === AUTH_TYPE_SUBCOMPANY).map(a => a.authId)
    authPersons.value = auths.filter(a => a.authType === AUTH_TYPE_RESOURCE)
    authDialogVisible.value = true
  } catch (error) {
    console.error('加载授权失败:', error)
  }
}

const handleAddPerson = (value: number | undefined, data: { lastname: string } | undefined) => {
  if (!value) return
  if (authPersons.value.some(p => p.authId === value)) {
    ElMessage.warning('该人员已在授权列表中')
  } else {
    authPersons.value.push({ id: 0, authType: AUTH_TYPE_RESOURCE, authId: value, authName: data?.lastname })
  }
  // 清空选择器，便于连续添加
  personPickerId.value = undefined
}

const removePerson = (authId: number) => {
  authPersons.value = authPersons.value.filter(p => p.authId !== authId)
}

const handleSaveAuths = async () => {
  if (!currentDimension.value) return
  authSaving.value = true
  try {
    const items = [
      ...authSubcompanyIds.value.map(id => ({ authType: AUTH_TYPE_SUBCOMPANY, authId: id })),
      ...authPersons.value.map(p => ({ authType: AUTH_TYPE_RESOURCE, authId: p.authId })),
    ]
    await saveDimensionAuths(currentDimension.value.id, items)
    ElMessage.success('授权保存成功')
    authDialogVisible.value = false
    await loadData()
  } catch (error) {
    console.error('保存授权失败:', error)
  } finally {
    authSaving.value = false
  }
}
</script>

<style scoped>
.page-container {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #1D2129;
}

.page-description {
  margin-top: 4px;
  font-size: 13px;
  color: #86909C;
}

.card {
  background: #FFFFFF;
  border: 1px solid #E5E6EB;
  border-radius: 10px;
}

.card-body {
  padding: 16px 20px;
}

.status-tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 10px;
}

.status-tag.active {
  background: #E8F7EE;
  color: #00A85A;
}

.status-tag.disabled {
  background: #FFF3E8;
  color: #FF7D00;
}

.text-muted {
  color: #C9CDD4;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1D2129;
  margin-bottom: 10px;
}

.auth-section {
  margin-bottom: 20px;
}

.person-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.full-width {
  width: 100%;
}
</style>
