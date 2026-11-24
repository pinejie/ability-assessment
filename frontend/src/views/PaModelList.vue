<template>
  <div class="page-wrapper">
    <div class="page-toolbar">
      <div>
        <h1 class="page-title">岗位能力模型</h1>
        <p class="page-description">
          每个岗位各配一套：能力类别 → 能力项（含等级）→ 分值区间。
          点「配置」进详情页维护。部门的岗位互不共用；「全公司通用」岗位只有一套，所有部门共用。
          改了只影响新建的测评，历史报告按快照不动
        </p>      </div>
    </div>

    <div class="page-card">
      <div class="page-toolbar">
        <div class="filters">
          <el-tree-select
            v-model="query.departmentId"
            :data="treeData"
            :props="deptTreeProps"
            node-key="id"
            check-strictly
            filterable
            clearable
            placeholder="按部门筛选"
            style="width: 220px"
            @change="handleSearch"
          />
          <el-input
            v-model="query.keyword"
            placeholder="岗位名称 / 编码 / 部门名"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-select v-model="query.status" clearable placeholder="状态" style="width: 110px" @change="handleSearch">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
          <!-- 部门下拉选不到「没有部门」这种值，通用岗位只能靠这个开关单独筛 -->
          <el-select v-model="query.generic" clearable placeholder="岗位范围" style="width: 130px" @change="handleSearch">
            <el-option label="部门岗位" :value="false" />
            <el-option label="全公司通用" :value="true" />
          </el-select>
          <el-button type="primary" plain @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
        <div class="toolbar-note">
          共 {{ pagination.total }} 个岗位
          <span v-if="incompleteOnPage > 0" class="warn-text">
            · 本页 {{ incompleteOnPage }} 个权重合计不等于 100%
          </span>
          <span v-if="unconfiguredOnPage > 0" class="warn-text">
            · 本页 {{ unconfiguredOnPage }} 个有等级未配好的能力项
          </span>
        </div>
      </div>

      <el-table v-loading="loading" :data="postList" stripe>
        <el-table-column label="部门" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.generic" size="small" type="warning" effect="plain">全公司通用</el-tag>
            <span v-else>{{ row.departmentName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="postName" label="岗位名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="postCode" label="编码" width="120">
          <template #default="{ row }">
            <span :class="{ 'text-muted': !row.postCode }">{{ row.postCode || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="能力类别" width="100" align="center">
          <template #default="{ row }">{{ row.categoryCount }} 个</template>
        </el-table-column>
        <el-table-column label="能力项" width="100" align="center">
          <template #default="{ row }">
            <span :class="{ 'empty-hint': row.itemCount === 0 }">
              {{ row.itemCount === 0 ? '未配置' : `${row.itemCount} 项` }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="权重合计" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.itemCount === 0" type="info" size="small">—</el-tag>
            <el-tag v-else :type="isWeightOk(row) ? 'success' : 'warning'" size="small">
              {{ formatWeight(row.weightTotal) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="等级" width="110" align="center">
          <template #default="{ row }">
            <!-- 没配能力项的岗位谈不上等级，先由「能力项」列表达「未配置」 -->
            <el-tag v-if="row.itemCount === 0" type="info" size="small">—</el-tag>
            <el-tag v-else-if="row.unconfiguredItemCount > 0" type="warning" size="small">
              {{ row.unconfiguredItemCount }} 项待配
            </el-tag>
            <el-tag v-else type="success" size="small">已配齐</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">配置</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-tip">
            还没有岗位。先去「岗位管理」建好部门+岗位，回来就能在这里配能力类别与能力项。
          </div>
        </template>
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
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { pagePaPosts } from '@/api/paPost'
import type { PaPostQuery, PaPostVO } from '@/types/paPost'
import { useDepartmentTree, deptTreeProps } from '@/composables/useDepartmentTree'

const route = useRoute()
const router = useRouter()

const { treeData, load: loadDeptTree } = useDepartmentTree()

const postList = ref<PaPostVO[]>([])
const loading = ref(false)

const query = reactive<Omit<PaPostQuery, 'pageNum' | 'pageSize'>>({
  departmentId: undefined,
  keyword: '',
  status: undefined,
  generic: undefined,
})

const pagination = reactive({
  currentPage: 1,
  pageSize: 10,
  total: 0,
})

/** 权重合计是否等于 100%：比较整数，避免 BigDecimal 转 number 的浮点误差 */
const isWeightOk = (row: PaPostVO): boolean => Math.round(Number(row.weightTotal)) === 100

/** 本页权重不达 100% 的岗位数（只统计已配能力项的；未配置的不算「配错」） */
const incompleteOnPage = computed(
  () => postList.value.filter((r) => r.itemCount > 0 && !isWeightOk(r)).length,
)

/** 本页有等级没配好的岗位数：这种岗位发起后评价人点不出档位，比权重不齐更该提前拦 */
const unconfiguredOnPage = computed(
  () => postList.value.filter((r) => r.itemCount > 0 && (r.unconfiguredItemCount ?? 0) > 0).length,
)

const formatWeight = (w: number | null | undefined): string =>
  w == null ? '—' : `${Number(w).toFixed(2)}%`

const loadData = async () => {
  loading.value = true
  try {
    const result = await pagePaPosts({
      pageNum: pagination.currentPage,
      pageSize: pagination.pageSize,
      departmentId: query.departmentId,
      keyword: query.keyword?.trim() || undefined,
      status: query.status,
      generic: query.generic,
    })
    postList.value = result.list || []
    pagination.total = result.total
  } catch (error) {
    console.error('加载岗位列表失败:', error)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.currentPage = 1
  loadData()
}

const handleReset = () => {
  query.departmentId = undefined
  query.keyword = ''
  query.status = undefined
  query.generic = undefined
  handleSearch()
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

const goDetail = (row: PaPostVO) => {
  router.push(`/pa-models/${row.id}`)
}

// -------------------------------------------------- 初始化

onMounted(async () => {
  loadDeptTree()

  // 旧地址兼容：拆页前是 /pa-models?postId=13，书签和「岗位管理」页里点过的
  // 链接都还长这样。这里换成新路径，别让老链接落到一个空列表上。
  const legacy = Number(route.query.postId)
  if (legacy) {
    router.replace(`/pa-models/${legacy}`)
    return
  }

  // 唯一一个岗位时不替用户跳详情：列表页本身也能看出「有没有配」，跳走反而绕
  await loadData()
})
</script>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.toolbar-note {
  font-size: var(--font-size-small);
  color: var(--color-text-muted);
}

.warn-text {
  color: var(--color-warning);
}

.text-muted {
  color: var(--color-text-muted);
}

.empty-hint {
  color: var(--color-warning);
}

.empty-tip {
  padding: 24px 16px;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.8;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
