<template>
  <div class="page-wrapper">
    <div class="page-toolbar">
      <div>
        <el-button link type="primary" class="back-link" @click="goBack">← 返回岗位能力模型</el-button>
        <h1 class="page-title">
          <template v-if="post">
            <el-tag v-if="post.generic" size="small" type="warning" effect="plain" class="title-tag">全公司通用</el-tag>
            {{ post.departmentName ? `${post.departmentName} · ${post.postName}` : post.postName }}
            <el-tag v-if="post.status === 0" size="small" type="info" class="title-tag">已停用</el-tag>
          </template>
          <template v-else-if="postMissing">岗位不存在</template>
          <template v-else>加载中…</template>
        </h1>
        <p class="page-description">
          能力类别 → 能力项（含等级）→ 分值区间。改了这里只影响新建的测评，历史报告按快照不动
        </p>
      </div>
    </div>

    <!-- 岗位取不到：多半是从旧书签/别人的链接进来的，别让页面空着 -->
    <el-empty v-if="postMissing" description="这个岗位不存在或已被删除，回列表重新选一个">
      <el-button type="primary" @click="goBack">返回列表</el-button>
    </el-empty>

    <template v-else-if="post">
      <!-- 顶部常驻徽标：权重合计 ≠100% 变黄 -->
      <div class="badge-bar">
        <div class="badge">
          <span class="badge-label">权重合计</span>
          <el-tag :type="weightOk ? 'success' : 'warning'" size="large">
            {{ formatWeight(model?.weightTotal) }}
          </el-tag>
          <span v-if="!weightOk" class="badge-warn">
            建议调整为 100%。不阻断保存与发起 —— 总分用加权平均算，合计不是 100% 不影响结果
          </span>
        </div>
        <div class="badge">
          <span class="badge-label">启用能力项</span>
          <el-tag type="info" size="large">{{ model?.itemCount ?? 0 }} 项</el-tag>
        </div>
        <div v-if="(model?.unconfiguredItemCount ?? 0) > 0" class="badge">
          <span class="badge-label">等级未配好</span>
          <el-tag type="warning" size="large">{{ model?.unconfiguredItemCount }} 项</el-tag>
          <span class="badge-warn">每项至少 2 档，否则测评人不知道能选什么</span>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="page-card">
        <!-- ---------------------------------------------------- 能力类别 + 能力项 -->
        <el-tab-pane label="能力类别与能力项" name="items">
          <div class="model-layout">
            <!-- 左树 -->
            <div class="tree-panel">
              <div class="panel-head">
                <span class="panel-title">能力类别</span>
                <el-button link type="primary" @click="handleAddCategory">新增</el-button>
              </div>
              <div v-loading="loading" class="tree-list">
                <div class="tree-item" :class="{ active: !selectedCategoryId }" @click="selectCategory(null)">
                  <span class="tree-name">全部</span>
                  <span class="tree-count">{{ model?.items.length ?? 0 }}</span>
                </div>
                <div
                  v-for="c in model?.categories ?? []"
                  :key="c.id"
                  class="tree-item"
                  :class="{ active: selectedCategoryId === c.id, disabled: c.status === 0 }"
                  @click="selectCategory(c.id)"
                >
                  <span class="tree-name" :title="c.categoryName">
                    {{ c.categoryName }}
                    <el-tag v-if="c.status === 0" size="small" type="info">停用</el-tag>
                  </span>
                  <span class="tree-count">{{ c.itemCount }}</span>
                </div>
                <div v-if="(model?.categories.length ?? 0) === 0" class="tree-empty">
                  还没有能力类别。先建类别，再往里加能力项
                </div>
              </div>
              <div v-if="selectedCategory" class="tree-actions">
                <el-button link type="primary" @click="handleEditCategory(selectedCategory)">编辑类别</el-button>
                <el-button link type="danger" @click="handleDeleteCategory(selectedCategory)">删除类别</el-button>
              </div>
            </div>

            <!-- 右表 -->
            <div class="table-panel">
              <div class="panel-head">
                <span class="panel-title">
                  {{ selectedCategory ? `${selectedCategory.categoryName} · 能力项` : '全部能力项' }}
                  <span class="panel-count">{{ visibleItems.length }}</span>
                </span>
                <el-button type="primary" size="small" :disabled="(model?.categories.length ?? 0) === 0" @click="handleAddItem">
                  新增能力项
                </el-button>
              </div>

              <el-table :data="visibleItems" size="small" border>
                <el-table-column prop="itemCode" label="编码" width="90">
                  <template #default="{ row }">
                    <span :class="{ 'text-muted': !row.itemCode }">{{ row.itemCode || '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="itemName" label="能力项" min-width="150" show-overflow-tooltip />
                <el-table-column v-if="!selectedCategory" prop="categoryName" label="类别" width="120" show-overflow-tooltip />
                <el-table-column label="权重" width="90" align="right">
                  <template #default="{ row }">{{ Number(row.weight).toFixed(2) }}%</template>
                </el-table-column>
                <el-table-column label="评级主体" width="190">
                  <template #default="{ row }">
                    <el-tag v-if="row.raterLeader === 1" size="small" class="rater-tag">直接上级</el-tag>
                    <el-tag v-if="row.rater360 === 1" size="small" type="success" class="rater-tag">360</el-tag>
                    <!-- 原文照抄：同样是 360，写「内部客户」和写「跨部门」不是一回事，
                         两个标签显示不出来。没有原文（手工新增、老数据）就不占位 -->
                    <div v-if="row.raterText" class="rater-text">{{ row.raterText }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="等级" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.levelCount >= 2 ? 'info' : 'warning'" size="small">
                      {{ row.levelCount }} 档
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                      {{ row.status === 1 ? '启用' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="handleEditItem(row)">编辑</el-button>
                    <el-button link type="danger" @click="handleDeleteItem(row)">删除</el-button>
                  </template>
                </el-table-column>
                <template #empty>
                  <div class="table-empty">
                    这个{{ selectedCategory ? '类别' : '岗位' }}还没有能力项。
                    能力项是真正被打分的对象，权重和等级都配在它上面
                  </div>
                </template>
              </el-table>
            </div>
          </div>
        </el-tab-pane>

        <!-- ---------------------------------------------------- 分值区间 -->
        <el-tab-pane label="分值区间" name="bands">
          <div class="band-head">
            <div class="form-tip band-desc">
              总得分落在哪一段，报告上就显示哪个等级说明。区间是<b>左闭右开</b>：
              <code>[3,4)</code> 表示 3 ≤ 得分 &lt; 4，分界点上正好 3 分的归这一段。
              下界/上界留空表示 −∞ / +∞ —— 最高一档上界必须留空（首档下界可留空也可写 1），
              相邻两段必须首尾相接，否则会有得分命中不到任何区间
            </div>
            <div class="band-actions">
              <el-button size="small" @click="handleAddBand">新增一段</el-button>
              <el-button type="primary" size="small" :loading="savingBands" @click="handleSaveBands">保存区间</el-button>
            </div>
          </div>

          <el-table :data="bandRows" size="small" border>
            <el-table-column label="区间" width="180">
              <template #default="{ row }">
                <code class="band-range">{{ rangeText(row) }}</code>
              </template>
            </el-table-column>
            <el-table-column label="下界（含，留空 = −∞）" width="220">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.lowerBound"
                  :precision="2"
                  :step="1"
                  controls-position="right"
                  placeholder="−∞"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="上界（不含，留空 = +∞）" width="220">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.upperBound"
                  :precision="2"
                  :step="1"
                  controls-position="right"
                  placeholder="+∞"
                  style="width: 100%"
                />
              </template>
            </el-table-column>
            <el-table-column label="等级说明" min-width="160">
              <template #default="{ row }">
                <el-input v-model="row.bandName" maxlength="50" placeholder="如：精进/能手" />
              </template>
            </el-table-column>
            <el-table-column label="排序" width="100">
              <template #default="{ row }">
                <el-input-number v-model="row.sort" :min="0" :max="9999" controls-position="right" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="bandRows.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="table-empty">
                还没有配分值区间。不配也能出分（总分照算），只是报告上的等级说明会空着
              </div>
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <PaCategoryEditDialog
      v-model="categoryDialogVisible"
      :post-id="postId"
      :category="editingCategory"
      @saved="loadModel"
    />
    <PaItemEditDialog
      v-model="itemDialogVisible"
      :post-id="postId"
      :item="editingItem"
      :categories="model?.categories ?? []"
      :default-category-id="selectedCategoryId ?? undefined"
      @saved="loadModel"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PaCategoryEditDialog from '@/components/pa/PaCategoryEditDialog.vue'
import PaItemEditDialog from '@/components/pa/PaItemEditDialog.vue'
import { deletePaCategory, deletePaItem, getPaModel, savePaBands } from '@/api/paModel'
import { getPaPost } from '@/api/paPost'
import type { PaBandVO, PaCategoryVO, PaItemVO, PaModelVO } from '@/types/paModel'
import type { PaPostVO } from '@/types/paPost'

const route = useRoute()
const router = useRouter()

/** 当前编辑的岗位 ID：来自路由参数 /pa-models/:postId。非法值由 onMounted 打回列表 */
const postId = ref(0)

const post = ref<PaPostVO | null>(null)
const postMissing = ref(false)
const model = ref<PaModelVO | null>(null)
const loading = ref(false)
const activeTab = ref('items')
const selectedCategoryId = ref<number | null>(null)

const weightOk = computed(() => Math.round(Number(model.value?.weightTotal ?? 0)) === 100)

const selectedCategory = computed(
  () => model.value?.categories.find((c) => c.id === selectedCategoryId.value) ?? null,
)

const visibleItems = computed(() => {
  const all = model.value?.items ?? []
  return selectedCategoryId.value == null
    ? all
    : all.filter((i) => i.categoryId === selectedCategoryId.value)
})

const formatWeight = (w: number | null | undefined): string =>
  w == null ? '—' : `${Number(w).toFixed(2)}%`

const goBack = () => {
  router.push('/pa-models')
}

// -------------------------------------------------- 读数据

const loadPost = async (): Promise<boolean> => {
  try {
    // 单独取岗位详情而不是从列表页带过来：刷新、书签、别人分享的链接进来时
    // 都没有列表页的状态，只有后端这一份才是权威的部门名与岗位名。
    post.value = await getPaPost(postId.value)
    return true
  } catch (error) {
    console.error('加载岗位详情失败:', error)
    postMissing.value = true
    return false
  }
}

const loadModel = async () => {
  loading.value = true
  try {
    model.value = await getPaModel(postId.value)
    // 选中的类别被删掉了就回到「全部」
    if (
      selectedCategoryId.value != null &&
      !model.value.categories.some((c) => c.id === selectedCategoryId.value)
    ) {
      selectedCategoryId.value = null
    }
    syncBandRows()
  } catch (error) {
    console.error('加载岗位能力模型失败:', error)
    model.value = null
  } finally {
    loading.value = false
  }
}

const selectCategory = (id: number | null) => {
  selectedCategoryId.value = id
}

// -------------------------------------------------- 能力类别

const categoryDialogVisible = ref(false)
const editingCategory = ref<PaCategoryVO | null>(null)

const handleAddCategory = () => {
  editingCategory.value = null
  categoryDialogVisible.value = true
}

const handleEditCategory = (category: PaCategoryVO) => {
  editingCategory.value = category
  categoryDialogVisible.value = true
}

const handleDeleteCategory = async (category: PaCategoryVO) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除能力类别「${category.categoryName}」吗？类别下有能项时不能删除。`,
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    await deletePaCategory(postId.value, category.id)
    ElMessage.success('删除成功')
    loadModel()
  } catch (error) {
    if (error !== 'cancel') console.error('删除能力类别失败:', error)
  }
}

// -------------------------------------------------- 能力项

const itemDialogVisible = ref(false)
const editingItem = ref<PaItemVO | null>(null)

const handleAddItem = () => {
  editingItem.value = null
  itemDialogVisible.value = true
}

const handleEditItem = (item: PaItemVO) => {
  editingItem.value = item
  itemDialogVisible.value = true
}

const handleDeleteItem = async (item: PaItemVO) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除能力项「${item.itemName}」吗？它的等级会一并删除。` +
        '已被测评数据引用的能力项不能删除，只能停用。',
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    await deletePaItem(postId.value, item.id)
    ElMessage.success('删除成功')
    loadModel()
  } catch (error) {
    if (error !== 'cancel') console.error('删除能力项失败:', error)
  }
}

// -------------------------------------------------- 分值区间

const bandRows = ref<PaBandVO[]>([])
const savingBands = ref(false)

/** 接口数据 → 编辑行。深拷贝：编辑中途不该污染 model，取消保存要能还原 */
const syncBandRows = () => {
  bandRows.value = (model.value?.bands ?? []).map((b) => ({
    id: b.id,
    bandName: b.bandName,
    lowerBound: b.lowerBound == null ? null : Number(b.lowerBound),
    upperBound: b.upperBound == null ? null : Number(b.upperBound),
    sort: b.sort,
  }))
}

const rangeText = (row: PaBandVO): string => {
  const lower = row.lowerBound == null ? '' : String(row.lowerBound)
  const upper = row.upperBound == null ? '' : String(row.upperBound)
  return `[${lower}, ${upper})`
}

/** 新增一段时把下界接在上一段上界上：这是唯一合法的写法，省得用户自己算 */
const handleAddBand = () => {
  const last = bandRows.value[bandRows.value.length - 1]
  bandRows.value.push({
    bandName: '',
    lowerBound: last?.upperBound ?? null,
    upperBound: null,
    sort: last ? Number(last.sort) + 1 : 0,
  })
}

const handleSaveBands = async () => {
  savingBands.value = true
  try {
    await savePaBands(
      postId.value,
      bandRows.value.map((b) => ({
        bandName: b.bandName.trim(),
        lowerBound: b.lowerBound == null ? null : Number(b.lowerBound),
        upperBound: b.upperBound == null ? null : Number(b.upperBound),
        sort: b.sort ?? 0,
      })),
    )
    ElMessage.success('保存成功')
    loadModel()
  } catch (error) {
    console.error('保存分值区间失败:', error)
  } finally {
    savingBands.value = false
  }
}

// -------------------------------------------------- 初始化

onMounted(async () => {
  const raw = route.params.postId
  const parsed = Number(Array.isArray(raw) ? raw[0] : raw)
  // 非数字（手改地址栏 /pa-models/abc）直接回列表，别停在一个永远加载不出来的页面
  if (!Number.isInteger(parsed) || parsed <= 0) {
    router.replace('/pa-models')
    return
  }
  postId.value = parsed
  if (!(await loadPost())) return
  await loadModel()
})
</script>

<style scoped>
.back-link {
  margin-bottom: 8px;
  padding: 0;
  font-size: 13px;
}

.title-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.badge-bar {
  display: flex;
  gap: 24px;
  align-items: flex-start;
  flex-wrap: wrap;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 12px 16px;
  margin-bottom: 12px;
}

.badge {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.badge-label {
  font-size: var(--font-size-small);
  color: var(--color-text-muted);
}

.badge-warn {
  font-size: 12px;
  color: var(--color-warning);
  max-width: 420px;
  line-height: 1.5;
}

.model-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.tree-panel {
  width: 240px;
  flex: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.table-panel {
  flex: 1;
  min-width: 0;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border);
}

.table-panel .panel-head {
  padding: 0 0 10px;
  border-bottom: none;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
}

.panel-count {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--color-text-muted);
}

.tree-list {
  padding: 6px;
  max-height: 560px;
  overflow-y: auto;
}

.tree-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--radius);
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
}

.tree-item:hover {
  background: var(--color-bg);
}

.tree-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.tree-item.disabled .tree-name {
  color: var(--color-text-muted);
}

.tree-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-count {
  flex: none;
  font-size: 12px;
  color: var(--color-text-muted);
}

.tree-empty {
  padding: 16px 10px;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.7;
}

.tree-actions {
  display: flex;
  gap: 4px;
  padding: 8px 10px;
  border-top: 1px solid var(--color-border);
}

.rater-tag {
  margin-right: 4px;
}

/* 原文比标签低一级：标签是给机器看的开关，这行是给人看的业务写法 */
.rater-text {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.3;
  color: var(--color-text-muted);
  word-break: break-all;
}

.text-muted {
  color: var(--color-text-muted);
}

.table-empty {
  padding: 24px 16px;
  color: var(--color-text-muted);
  font-size: 13px;
  line-height: 1.8;
}

.band-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.band-desc {
  max-width: 720px;
  line-height: 1.7;
}

.band-actions {
  display: flex;
  gap: 8px;
}

.band-range {
  font-family: var(--el-font-family-mono, monospace);
  background: var(--color-bg);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
