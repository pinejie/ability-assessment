<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <div class="page-title-section">
        <h1 class="page-title">管理员配置</h1>
        <p class="page-description">本页仅超级管理员可见；添加的管理员可管理租户并查看全部租户数据，但不能管理管理员名单</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增管理员</el-button>
    </div>

    <!-- 表格 -->
    <div class="card">
      <div class="card-body">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="来源为「配置」的超管由后端配置（assess.bootstrap-admins）引导，不可在此移除；撤销其他管理员即时生效。"
          style="margin-bottom: 16px"
        />
        <el-table :data="adminList" class="data-table">
          <el-table-column prop="loginid" label="OA 登录名" min-width="140" />
          <el-table-column label="姓名" min-width="120">
            <template #default="{ row }">
              <span v-if="row.lastname">{{ row.lastname }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.bootstrap" type="warning" effect="plain">配置</el-tag>
              <el-tag v-else type="info" effect="plain">界面管理</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.remark">{{ row.remark }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" width="170">
            <template #default="{ row }">
              <span v-if="row.createTime">{{ formatTime(row.createTime) }}</span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" :disabled="!canDelete(row)" @click="handleDelete(row)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="新增管理员"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="选择人员（泛微在职）">
          <ResourceAutocomplete v-model="personId" placeholder="搜索姓名选择人员" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="remark" type="textarea" :rows="2" maxlength="200" placeholder="可选，如：负责销售条线租户" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!personId" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResourceAutocomplete from '@/components/ResourceAutocomplete.vue'
import { addAdmin, listAdmins, removeAdmin } from '@/api/admin'
import type { AssessAdminVO } from '@/types/admin'
import { useUserStore } from '@/stores/user'

const adminList = ref<AssessAdminVO[]>([])
const userStore = useUserStore()

const loadData = async () => {
  try {
    adminList.value = await listAdmins()
  } catch (error) {
    console.error('加载管理员列表失败:', error)
  }
}

onMounted(loadData)

const formatTime = (value: string): string => value.replace('T', ' ').slice(0, 19)

/** 删除门槛：本人、配置超管、未落表条目（无 id）不可删；权威校验仍以后端为准 */
const canDelete = (row: AssessAdminVO): boolean =>
  !row.bootstrap &&
  row.id !== null &&
  row.loginid !== userStore.user?.loginid

// ==================== 新增 ====================

const dialogVisible = ref(false)
const saving = ref(false)
const personId = ref<number | undefined>(undefined)
const remark = ref('')

const handleAdd = () => {
  personId.value = undefined
  remark.value = ''
  dialogVisible.value = true
}

const handleSubmit = async () => {
  if (!personId.value) {
    ElMessage.warning('请选择人员')
    return
  }
  saving.value = true
  try {
    await addAdmin({ resourceId: personId.value, remark: remark.value || undefined })
    ElMessage.success('添加成功')
    dialogVisible.value = false
    await loadData()
  } catch (error) {
    console.error('添加管理员失败:', error)
  } finally {
    saving.value = false
  }
}

// ==================== 删除 ====================

const handleDelete = async (row: AssessAdminVO) => {
  if (row.id === null) return
  try {
    await ElMessageBox.confirm(
      `确定移除管理员「${row.lastname || row.loginid}」吗？撤销后立即生效。`,
      '移除确认',
      { type: 'warning' },
    )
    await removeAdmin(row.id)
    ElMessage.success('已移除')
    await loadData()
  } catch (error) {
    // 用户取消或接口报错（已全局提示）
    console.error('移除管理员失败:', error)
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

.text-muted {
  color: #C9CDD4;
}
</style>
