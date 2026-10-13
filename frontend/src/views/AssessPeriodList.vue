<template>
  <div class="assess-period-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">年度设置</h1>
        <p class="page-description">
          年度只影响人员能力要求和测评汇总页面。测评配置全局共用，不按年设置。
          关闭后，该年度的人员能力要求和测评页面只读，泛微钩子不再写入。
        </p>
      </div>
      <el-button type="primary" @click="openCreate">新增年度</el-button>
    </div>

    <el-card>
      <el-table v-loading="loading" :data="periods" border>
        <el-table-column prop="year" label="年度" width="140" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag v-if="row.status === 0" type="success">进行中</el-tag>
            <el-tag v-else type="info">已关闭</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="warning" @click="toggle(row, 1)">关闭年度</el-button>
            <el-button v-else link type="primary" @click="toggle(row, 0)">重新开启</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增年度" width="420px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="年度">
          <el-input-number v-model="newYear" :min="2000" :max="2100" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changePeriodStatus, createPeriod, listPeriods } from '@/api/assessPeriod'
import type { PeriodItem } from '@/types/assessSheet'

const periods = ref<PeriodItem[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const newYear = ref(new Date().getFullYear())

const load = async () => {
  loading.value = true
  try {
    periods.value = await listPeriods()
  } catch {
    ElMessage.error('加载年度失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  newYear.value = new Date().getFullYear()
  dialogVisible.value = true
}

const submitCreate = async () => {
  saving.value = true
  try {
    await createPeriod(newYear.value)
    ElMessage.success('新增成功')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const toggle = async (row: PeriodItem, status: number) => {
  const closing = status === 1
  try {
    await ElMessageBox.confirm(
      closing
        ? `关闭${row.year}年度后，该年度的人员能力要求和测评页面只读，泛微钩子不再写入。确定关闭？`
        : `重新开启${row.year}年度？`,
      '提示',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await changePeriodStatus(row.id, status)
  ElMessage.success(closing ? '已关闭' : '已开启')
  await load()
}

onMounted(load)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-title {
  margin: 0 0 4px;
}
.page-description {
  color: #666;
  margin: 0;
}
</style>
