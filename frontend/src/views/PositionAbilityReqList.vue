<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">岗位能力要求配置</h1>
        <p class="page-description">为各岗位配置能力要素要求</p>
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
        <div class="stat-value">{{ reqList.length }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">涉及岗位</div>
        <div class="stat-value">{{ jobTitleCount }}</div>
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
          <el-table-column prop="jobTitleName" label="岗位" width="200">
            <template #default="{ row }">
              <span class="job-title-name">{{ row.jobTitleName }}</span>
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
      </div>
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      class="form-dialog"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        class="form-content"
      >
        <el-form-item label="岗位" prop="jobTitleId">
          <LazySearchTreeSelect
            ref="treeSelectRef"
            v-model="formData.jobTitleId"
            :load="loadJobTitleTree"
            :search="searchJobTitles"
            :field-names="{
              label: 'name',
              value: 'id',
              children: 'children',
              disabled: (data) => data.type !== 'title'
            }"
            placeholder="请选择或搜索岗位"
            :width="400"
          >
            <template #node="{ data }">
              <span class="tree-node">
                <el-icon v-if="data.type === 'group'" style="color: #8B5CF6; margin-right: 4px;">
                  <Collection />
                </el-icon>
                <el-icon v-else-if="data.type === 'activity'" style="color: #F59E0B; margin-right: 4px;">
                  <Briefcase />
                </el-icon>
                <el-icon v-else style="color: #10B981; margin-right: 4px;">
                  <User />
                </el-icon>
                <span>{{ data.name }}</span>
              </span>
            </template>
          </LazySearchTreeSelect>
          <div class="form-tip">从泛微系统岗位表中选择（只能选择岗位，支持搜索）</div>
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
import { Collection, Briefcase, User } from '@element-plus/icons-vue'
import { listPositionAbilityReqs, createPositionAbilityReq, updatePositionAbilityReq, deletePositionAbilityReq } from '@/api/positionAbilityReq'
import { listAbilityElements } from '@/api/abilityElement'
import type { PositionAbilityReqVO } from '@/types/positionAbilityReq'
import type { AbilityElementVO } from '@/types/abilityElement'
import request from '@/utils/request'
import LazySearchTreeSelect from '@/components/LazySearchTreeSelect.vue'

interface JobGroup {
  id: number
  jobgroupremark: string
  hasChildren?: boolean
}

interface TreeNode {
  id: number | string
  name: string
  type: 'group' | 'activity' | 'title'
  hasChildren?: boolean
  children?: TreeNode[]
  [key: string]: any
}

const reqList = ref<PositionAbilityReqVO[]>([])
const elementList = ref<AbilityElementVO[]>([])
const jobGroupList = ref<JobGroup[]>([])

// 搜索岗位（返回完整层级树）
const searchJobTitles = async (keyword: string): Promise<any[]> => {
  try {
    const searchResults = await request.get(`/search/job-titles?keyword=${encodeURIComponent(keyword)}`)

    // 构建 group -> activity -> title 层级结构
    const groupMap = new Map<number, TreeNode>()
    const activityMap = new Map<string, TreeNode>()
    const rootGroups: TreeNode[] = []

    searchResults.forEach((item: any) => {
      const groupId = item.jobgroupid
      const activityId = item.jobactivityid

      // 创建 group 节点
      if (!groupMap.has(groupId)) {
        const groupNode: TreeNode = {
          id: groupId,
          name: item.groupName,
          type: 'group',
          children: []
        }
        groupMap.set(groupId, groupNode)
        rootGroups.push(groupNode)
      }

      // 创建 activity 节点
      const activityKey = `${groupId}-${activityId}`
      if (!activityMap.has(activityKey)) {
        const activityNode: TreeNode = {
          id: activityId,
          name: item.activityName,
          type: 'activity',
          children: []
        }
        activityMap.set(activityKey, activityNode)
        const groupNode = groupMap.get(groupId)!
        groupNode.children = groupNode.children || []
        groupNode.children.push(activityNode)
      }

      // 创建 title 节点
      const titleNode: TreeNode = {
        id: item.id,
        name: item.jobtitlemark,
        type: 'title'
      }

      const activityNode = activityMap.get(activityKey)!
      activityNode.children = activityNode.children || []
      activityNode.children.push(titleNode)
    })

    return rootGroups
  } catch (error) {
    console.error('搜索岗位失败:', error)
    return []
  }
}

// 懒加载岗位树（新接口：返回 Promise）
const loadJobTitleTree = async (node: any): Promise<TreeNode[]> => {
  if (node === null) {
    // 加载根节点：岗位类别
    try {
      const response = await request.get('/job-groups')
      jobGroupList.value = response
      return response.map((group: any) => {
        const hasChildren = group.hasChildren === true || group.hasChildren === 'true'
        return {
          id: group.id,
          name: group.jobgroupremark,
          type: 'group' as const,
          hasChildren,
          // 只有明确有子节点时才设置占位数据
          children: hasChildren ? [{ __placeholder: true }] : undefined
        }
      })
    } catch (error) {
      console.error('加载岗位类别失败:', error)
      return []
    }
  } else if (node.type === 'group') {
    // 加载第二层：职务
    try {
      const response = await request.get(`/job-activities/by-group?groupId=${node.id}`)
      return response.map((activity: any) => {
        const hasChildren = activity.hasChildren === true || activity.hasChildren === 'true'
        return {
          id: activity.id,
          name: activity.jobactivitymark,
          type: 'activity' as const,
          hasChildren,
          children: hasChildren ? [{ __placeholder: true }] : undefined
        }
      })
    } catch (error) {
      console.error('加载职务列表失败:', error)
      return []
    }
  } else if (node.type === 'activity') {
    // 加载第三层：岗位
    try {
      const response = await request.get(`/job-titles/by-activity?activityId=${node.id}`)
      return response.map((title: any) => {
        const hasChildren = title.hasChildren === true || title.hasChildren === 'true'
        return {
          id: title.id,
          name: title.jobtitlemark,
          type: 'title' as const,
          hasChildren,
          // 岗位通常是叶子节点，但保留扩展性
          children: hasChildren ? [{ __placeholder: true }] : undefined
        }
      })
    } catch (error) {
      console.error('加载岗位列表失败:', error)
      return []
    }
  }
  return []
}

const jobTitleCount = computed(() => {
  const jobTitleIds = new Set(reqList.value.map(r => r.jobTitleId))
  return jobTitleIds.size
})

const elementCount = computed(() => {
  const elementIds = new Set(reqList.value.map(r => r.elementId))
  return elementIds.size
})

const dialogVisible = ref(false)
const dialogTitle = ref('')
const formRef = ref<FormInstance>()
const treeSelectRef = ref()

const formData = reactive({
  id: 0,
  jobTitleId: undefined as number | undefined,
  elementId: undefined as number | undefined,
  description: '',
})

const formRules: FormRules = {
  jobTitleId: [
    { required: true, message: '请选择岗位', trigger: 'change' },
  ],
  elementId: [
    { required: true, message: '请选择能力要素', trigger: 'change' },
  ],
}

const loadData = async () => {
  try {
    reqList.value = await listPositionAbilityReqs()
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

const handleAdd = () => {
  dialogTitle.value = '新增要求'
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (row: PositionAbilityReqVO) => {
  dialogTitle.value = '编辑要求'
  Object.assign(formData, row)
  dialogVisible.value = true
}

const handleDelete = async (row: PositionAbilityReqVO) => {
  try {
    await ElMessageBox.confirm('确定要删除该要求吗？删除后不可恢复。', '提示', {
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

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()

    if (!formData.jobTitleId || !formData.elementId) {
      ElMessage.error('请填写完整信息')
      return
    }

    if (formData.id) {
      await updatePositionAbilityReq({
        id: formData.id,
        jobTitleId: formData.jobTitleId,
        elementId: formData.elementId,
        description: formData.description,
      })
      ElMessage.success('更新成功')
    } else {
      await createPositionAbilityReq({
        jobTitleId: formData.jobTitleId,
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
  formData.jobTitleId = undefined
  formData.elementId = undefined
  formData.description = ''
  formRef.value?.resetFields()
  treeSelectRef.value?.reset()  // 重置岗位浏览框
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

.job-title-name {
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
</style>
