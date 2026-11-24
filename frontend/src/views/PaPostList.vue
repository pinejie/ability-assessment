<template>
  <div class="page-wrapper">
    <div class="page-toolbar">
      <div>
        <h1 class="page-title">岗位管理</h1>
        <p class="page-description">
          岗位 = 部门 + 岗位名称（项目自有，不是泛微职级）。每个岗位各配一套能力模型。
          部门的岗位互不共用；「全公司通用」岗位（会计、行政专员这类每个部门都有的）只有一套，所有部门共用
        </p>
      </div>
      <el-button type="primary" @click="handleAdd">新增岗位</el-button>
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
          <span v-if="incompleteOnPage > 0" class="warn-text">· 本页 {{ incompleteOnPage }} 个权重合计不等于 100%</span>
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
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="80" align="center" />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goToModel(row)">配能力模型</el-button>
            <el-button link type="primary" @click="handleEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="handleToggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-tip">
            还没有配置岗位。先在「岗位管理」建好部门+岗位，再去「岗位能力模型」配能力类别与能力项。
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

    <PaPostEditDialog
      v-model="dialogVisible"
      :post="editingPost"
      :tree-data="treeData"
      @saved="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PaPostEditDialog from '@/components/pa/PaPostEditDialog.vue'
import { pagePaPosts, updatePaPost, deletePaPost } from '@/api/paPost'
import type { PaPostVO, PaPostQuery } from '@/types/paPost'
import { postTitle } from '@/types/paPost'
import { useDepartmentTree, deptTreeProps } from '@/composables/useDepartmentTree'

const { treeData, load: loadDeptTree } = useDepartmentTree()

const router = useRouter()

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

// -------------------------------------------------- 新增/编辑

const dialogVisible = ref(false)
const editingPost = ref<PaPostVO | null>(null)

const handleAdd = () => {
  editingPost.value = null
  dialogVisible.value = true
}

const handleEdit = (row: PaPostVO) => {
  editingPost.value = row
  dialogVisible.value = true
}

/** 跳到该岗位的能力模型详情页（2026-11-22 拆页后是独立路由） */
const goToModel = (row: PaPostVO) => {
  router.push(`/pa-models/${row.id}`)
}

// -------------------------------------------------- 启用/停用

const handleToggleStatus = async (row: PaPostVO) => {
  const action = row.status === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}岗位「${postTitle(row)}」吗？` +
        (row.status === 1 ? '停用后该岗位不会再出现在发起测评的岗位选项里，历史报告不受影响。' : ''),
      '提示',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    // 更新接口是全量更新，departmentId 传 null 的语义是「改为通用」——
    // 通用岗位本来就该传 null，部门岗位传自己的部门，别在这里把它弄成通用
    await updatePaPost({
      id: row.id,
      departmentId: row.departmentId ?? null,
      postName: row.postName,
      postCode: row.postCode,
      description: row.description,
      status: row.status === 1 ? 0 : 1,
      sort: row.sort,
    })
    ElMessage.success(`${action}成功`)
    loadData()
  } catch (error) {
    if (error !== 'cancel') console.error(`${action}失败:`, error)
  }
}

// -------------------------------------------------- 删除

const handleDelete = async (row: PaPostVO) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除岗位「${postTitle(row)}」吗？其能力类别与能力项会一并删除。` +
        '已被人员映射或测评页引用的岗位不能删除，只能停用。',
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    await deletePaPost(row.id)
    ElMessage.success('删除成功')
    // 删掉本页最后一条时回退一页，避免停在空页
    if (postList.value.length === 1 && pagination.currentPage > 1) {
      pagination.currentPage -= 1
    }
    loadData()
  } catch (error) {
    if (error !== 'cancel') console.error('删除岗位失败:', error)
  }
}

onMounted(() => {
  loadDeptTree()
  loadData()
})
</script>

<style scoped>
.filters {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.toolbar-note {
  font-size: 13px;
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
  padding: 28px 16px;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.8;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 0;
}
</style>
