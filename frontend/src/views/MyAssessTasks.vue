<template>
  <div class="my-assess-page">
    <div class="page-header">
      <h1 class="page-title">我的评价</h1>
      <p class="page-description">
        这里列出需要您参与的能力测评。只显示进行中与已完成的任务，已撤回或已取消的不再显示。
      </p>
    </div>

    <el-card v-loading="loading">
      <el-table v-if="items.length" :data="items" border>
        <el-table-column prop="year" label="年度" width="90" />
        <el-table-column prop="targetName" label="被测人" width="120" />
        <el-table-column prop="deptName" label="部门" width="160" />
        <el-table-column prop="dimensionName" label="测评维度" width="140" />
        <el-table-column prop="raterTypeName" label="我的角色" width="110" />
        <el-table-column label="状态" width="180">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row)" size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">
              {{ row.raterStatus === 1 ? '查看' : '进入评价' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading" description="暂无待办评价">
        <p class="empty-hint">如有测评任务，人力资源部会通过系统消息通知您。</p>
      </el-empty>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listMyAssessTasks } from '@/api/assessTask'
import type { MyAssessTaskItem } from '@/types/assessTask'

const router = useRouter()
const items = ref<MyAssessTaskItem[]>([])
const loading = ref(false)

const statusTagType = (row: MyAssessTaskItem): 'primary' | 'success' | 'warning' | 'info' => {
  if (row.taskStatus === 2) {
    return 'success'
  }
  return row.raterStatus === 1 ? 'warning' : 'primary'
}

const open = (row: MyAssessTaskItem) => {
  // 用 token 落地：填写页会再校验「登录人 == 评价人」，转发链接给别人打不开
  router.push(`/assess/fill/${row.accessToken}`)
}

const load = async () => {
  loading.value = true
  try {
    items.value = await listMyAssessTasks()
  } catch {
    ElMessage.error('加载评价列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.my-assess-page {
  padding: 0;
}

.empty-hint {
  font-size: 13px;
  color: #86909c;
}
</style>
