<template>
  <div class="assess-period-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">
          年度设置
          <span v-if="dimensionName" class="dimension-tag">{{ dimensionName }}</span>
        </h1>
        <p class="page-description">
          年度决定测评任务与测评报告的归属，按维度各自独立；岗位能力模型不按年设置，改动对所有年度立即生效。
          同一维度同一时间只允许一个「进行中」年度：新增或激活年度时，原进行中年度将被自动关闭。
          关闭后该年度不再接受新的评价，已提交的评价与报告仍可正常查看。
        </p>
        <p v-if="!canWrite && !loading" class="page-hint">
          你当前对该维度只有查看权限。年度由该维度的管理员维护，需要增减或开关年度请联系他们。
        </p>
      </div>
      <el-button type="primary" :disabled="!canWrite" @click="openCreate">新增年度</el-button>
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
            <el-button
              v-if="row.status === 0"
              link
              type="warning"
              :disabled="!canWrite"
              @click="toggle(row, 1)"
            >
              关闭年度
            </el-button>
            <el-button v-else link type="primary" :disabled="!canWrite" @click="toggle(row, 0)">
              重新开启
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增年度" width="420px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="年度" :error="duplicateHint">
          <el-input-number v-model="newYear" :min="2000" :max="2100" :step="1" />
          <p class="year-taken">
            {{ takenYears.length ? `本维度已有：${takenYears.join('、')}，一年只能建一条` : '本维度还没有年度' }}
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!!duplicateHint" @click="submitCreate">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { canWritePeriods, changePeriodStatus, createPeriod, listPeriods } from '@/api/assessPeriod'
import { useDimensionStore } from '@/stores/dimension'
import type { PeriodItem } from '@/types/common'

const dimensionStore = useDimensionStore()

/** 页头显示当前维度：年度是按维度分的，不写清维度名时两套数据看起来一模一样 */
const dimensionName = computed(
  () => dimensionStore.dimensions.find(d => d.id === dimensionStore.currentId)?.dimensionName,
)

const periods = ref<PeriodItem[]>([])
const loading = ref(false)
/** 能否维护本维度年度（维度管理员 / 系统管理员）。后端权威判定，这里只管按钮 */
const canWrite = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const newYear = ref(new Date().getFullYear())

const load = async () => {
  loading.value = true
  try {
    const [list, writable] = await Promise.all([listPeriods(), canWritePeriods()])
    periods.value = list
    canWrite.value = writable
  } catch {
    ElMessage.error('加载年度失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  // 默认填「今年往后第一个没被占用的年份」。只写「今年」或「今年+1」都不稳：
  // 今年通常早建过，而今年+1 也可能已经占上（比如提前把明年建好了），
  // 弹窗一打开就是红的，等于让用户先看一条报错再自己找年份。
  const taken = new Set(periods.value.map(p => p.year))
  let candidate = new Date().getFullYear()
  while (taken.has(candidate) && candidate < 2100) {
    candidate += 1
  }
  newYear.value = candidate
  dialogVisible.value = true
}

/** 本维度已占用的年份（升序），弹窗里直接列出来，省得用户一个个试 */
const takenYears = computed(() => periods.value.map(p => p.year).sort((a, b) => a - b))

/**
 * 重复年度提示：弹窗内就地挡，不等服务端返回 500。
 * 服务端仍会再判一次（并发 / 多标签页 / 有人刚建过），这里只是把「点确定才知道」变成「输入时就看见」。
 */
const duplicateHint = computed(() =>
  periods.value.some(p => p.year === newYear.value)
    ? `${newYear.value} 年度已存在，请换一年`
    : '',
)

/** 当前维度下除 excludeId 外、处于「进行中」的年度——服务端会联动自动关闭它们 */
const activeOthers = (excludeId: number) =>
  periods.value.filter(p => p.status === 0 && p.id !== excludeId)

const submitCreate = async () => {
  // 双保险：按钮已 disabled，键盘回车 / 程序调用仍可能走到这里
  if (duplicateHint.value) {
    ElMessage.warning(duplicateHint.value)
    return
  }
  const willClose = activeOthers(-1)
  if (willClose.length) {
    try {
      await ElMessageBox.confirm(
        `新增年度默认为「进行中」，本维度当前进行中的 ${willClose.map(p => `${p.year}年度`).join('、')} 将被自动关闭。确定继续？`,
        '提示',
        { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
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
  const willClose = closing ? [] : activeOthers(row.id)
  const cascadeNote = willClose.length
    ? `本维度进行中的 ${willClose.map(p => `${p.year}年度`).join('、')} 将被自动关闭。`
    : ''
  try {
    await ElMessageBox.confirm(
      closing
        ? `关闭${row.year}年度后，该年度不再接受新的评价，已提交的评价与报告仍可查看。确定关闭？`
        : `重新开启${row.year}年度？${cascadeNote}`,
      '提示',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await changePeriodStatus(row.id, status)
  ElMessage.success(closing ? '已关闭' : willClose.length ? '已开启，原进行中年度已自动关闭' : '已开启')
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
  display: flex;
  align-items: center;
  gap: 8px;
}
.dimension-tag {
  font-size: 13px;
  font-weight: 400;
  color: #1d4f91;
  background: #ecf3fb;
  border: 1px solid #cfe0f4;
  border-radius: 10px;
  padding: 1px 10px;
}
.page-description {
  color: #666;
  margin: 0;
}
.page-hint {
  color: #b88230;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 6px 10px;
  margin: 8px 0 0;
  display: inline-block;
}
/* 已有年份清单：跟在输入框右侧会换行，单独占一行更像说明而不是报错 */
.year-taken {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
</style>
