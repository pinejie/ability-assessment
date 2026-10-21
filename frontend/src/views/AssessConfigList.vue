<template>
  <div class="assess-config-page">
    <h1 class="page-title">能力测评配置</h1>
    <p class="page-description">配置能力类型权重、测评对象权重和等级标准，保存后只影响之后生成的测评结果</p>

    <!-- 1. 能力类型权重 -->
    <el-card class="config-block" v-loading="loadingAbility">
      <template #header>
        <div class="block-header">
          <span>1. 能力类型权重</span>
          <el-button type="primary" :disabled="!abilityCanSave" :loading="savingAbility" @click="saveAbility">保存</el-button>
        </div>
      </template>
      <el-table :data="abilityRows" border size="small" row-key="categoryId">
        <el-table-column prop="categoryName" label="能力类型" min-width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="!row.enabled" type="info">已停用</el-tag>
            <el-tag v-else type="success">启用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权重(%)" width="200">
          <template #default="{ row }">
            <el-input-number
              v-model="row.weight"
              :min="0"
              :max="100"
              :precision="2"
              :disabled="!row.enabled"
              controls-position="right"
              placeholder="必填"
              style="width: 160px"
            />
          </template>
        </el-table-column>
      </el-table>
      <div class="sum-line" :class="{ 'sum-error': !abilitySumOk }">
        启用项合计：{{ abilitySum }}%
        <span v-if="!abilitySumOk">（必须为 100%，且启用项不能为空）</span>
      </div>
    </el-card>

    <!-- 2. 测评对象权重 -->
    <el-card class="config-block" v-loading="loadingAssessor">
      <template #header>
        <div class="block-header">
          <span>2. 测评对象权重</span>
          <el-button type="primary" :disabled="!assessorCanSave" :loading="savingAssessor" @click="saveAssessor">保存</el-button>
        </div>
      </template>
      <el-table :data="assessorRows" border size="small" row-key="assessorCode">
        <el-table-column prop="assessorName" label="测评对象" min-width="180" />
        <el-table-column label="权重(%)" width="200">
          <template #default="{ row }">
            <el-input-number
              v-model="row.weight"
              :min="0"
              :max="100"
              :precision="2"
              controls-position="right"
              placeholder="必填"
              style="width: 160px"
            />
          </template>
        </el-table-column>
      </el-table>
      <div class="sum-line" :class="{ 'sum-error': !assessorSumOk }">
        合计：{{ assessorSum }}%
        <span v-if="!assessorSumOk">（必须为 100%）</span>
      </div>
    </el-card>

    <!-- 3. 等级标准 -->
    <el-card class="config-block" v-loading="loadingGrade">
      <template #header>
        <div class="block-header">
          <span>3. 等级标准（区间为左开右闭 (下限, 上限]）</span>
          <div>
            <el-button @click="insertGradeRow">新增区间</el-button>
            <el-button type="primary" :disabled="!gradeCanSave" :loading="savingGrade" @click="saveGrade">保存</el-button>
          </div>
        </div>
      </template>
      <el-table :data="gradeRows" border size="small">
        <el-table-column label="下限(%)" width="180">
          <template #default="{ row, $index }">
            <span v-if="$index === 0" class="infinity">负无穷（—）</span>
            <el-input-number v-else v-model="row.lowerBound" disabled :precision="2" controls-position="right" style="width: 140px" />
          </template>
        </el-table-column>
        <el-table-column label="上限(%)" width="180">
          <template #default="{ row, $index }">
            <span v-if="$index === gradeRows.length - 1" class="infinity">正无穷（—）</span>
            <el-input-number
              v-else
              v-model="row.upperBound"
              :precision="2"
              controls-position="right"
              style="width: 140px"
              @change="onUpperChange($index)"
            />
          </template>
        </el-table-column>
        <el-table-column label="等级名称" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.levelName" maxlength="20" placeholder="等级名称" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ $index }">
            <el-button link type="primary" @click="removeGradeRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="sum-line" :class="{ 'sum-error': !gradeOk }">
        {{ gradeHint }}
      </div>
    </el-card>

    <!-- 4. 等级分值 -->
    <el-card class="config-block" v-loading="loadingLevel">
      <template #header>
        <div class="block-header">
          <span>4. 等级分值（用于测评得分计算）</span>
          <el-button type="primary" :disabled="!levelCanSave" :loading="savingLevel" @click="saveLevel">保存</el-button>
        </div>
      </template>
      <el-table :data="levelRows" border size="small" row-key="levelNo">
        <el-table-column label="等级" width="160">
          <template #default="{ row }">{{ row.levelNo }} 级</template>
        </el-table-column>
        <el-table-column label="分值" width="200">
          <template #default="{ row }">
            <el-input-number v-model="row.score" :min="0" :max="999.99" :precision="2" controls-position="right" placeholder="必填" style="width: 160px" />
          </template>
        </el-table-column>
      </el-table>
      <div class="sum-line" :class="{ 'sum-error': !levelOk }">
        {{ levelOk ? '分值校验通过（随等级升高而递增）' : levelError }}
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAbilityWeights,
  saveAbilityWeights,
  getAssessorWeights,
  saveAssessorWeights,
  getGradeLevels,
  saveGradeLevels,
  getLevelScores,
  saveLevelScores,
} from '@/api/assessConfig'
import type { AssessorCode, LevelScoreVO } from '@/types/assessConfig'

/**
 * 公司ID：项目目前没有"当前登录人所属公司"的工具方法，暂由前端传入。
 * 待确认后改为从登录信息获取。
 */
const COMPANY_ID = 1

interface AbilityRow {
  categoryId: number
  categoryName: string
  enabled: boolean
  weight: number | null
}

interface AssessorRow {
  assessorCode: AssessorCode
  assessorName: string
  weight: number | null
}

interface GradeRow {
  levelName: string
  lowerBound: number | null
  upperBound: number | null
}

const loadingAbility = ref(false)
const loadingAssessor = ref(false)
const loadingGrade = ref(false)
const savingAbility = ref(false)
const savingAssessor = ref(false)
const savingGrade = ref(false)
const loadingLevel = ref(false)
const savingLevel = ref(false)
const levelRows = ref<LevelScoreVO[]>([])

const abilityRows = ref<AbilityRow[]>([])
const assessorRows = ref<AssessorRow[]>([])
const gradeRows = ref<GradeRow[]>([])

// ==================== 能力类型权重 ====================

const abilityError = computed(() => {
  const enabled = abilityRows.value.filter(r => r.enabled)
  if (enabled.length === 0) return '至少需要一个启用的能力类型'
  if (enabled.some(r => r.weight === null)) return '启用的能力类型权重不能为空'
  const sum = enabled.reduce((s, r) => s + (r.weight ?? 0), 0)
  if (Math.abs(sum - 100) > 0.001) return '启用项权重合计必须为 100%'
  return ''
})
const abilitySum = computed(() =>
  abilityRows.value.filter(r => r.enabled).reduce((s, r) => s + (r.weight ?? 0), 0)
)
const abilitySumOk = computed(() => abilityError.value === '')
const abilityCanSave = computed(() => abilitySumOk.value && !savingAbility.value)

const loadAbility = async () => {
  loadingAbility.value = true
  try {
    const list = await getAbilityWeights(COMPANY_ID)
    abilityRows.value = list.map(v => ({
      categoryId: v.categoryId,
      categoryName: v.categoryName,
      enabled: v.enabled,
      weight: v.weight,
    }))
  } catch (e) {
    console.error('加载能力类型权重失败:', e)
  } finally {
    loadingAbility.value = false
  }
}

const saveAbility = async () => {
  savingAbility.value = true
  try {
    await saveAbilityWeights(
      COMPANY_ID,
      abilityRows.value.map(r => ({ categoryId: r.categoryId, weight: r.weight }))
    )
    ElMessage.success('能力类型权重已保存')
    await loadAbility()
  } catch (e) {
    console.error('保存能力类型权重失败:', e)
  } finally {
    savingAbility.value = false
  }
}

// ==================== 测评对象权重 ====================

const assessorError = computed(() => {
  if (assessorRows.value.some(r => r.weight === null)) return '测评对象权重不能为空'
  const sum = assessorRows.value.reduce((s, r) => s + (r.weight ?? 0), 0)
  if (Math.abs(sum - 100) > 0.001) return '合计必须为 100%'
  return ''
})
const assessorSum = computed(() => assessorRows.value.reduce((s, r) => s + (r.weight ?? 0), 0))
const assessorSumOk = computed(() => assessorError.value === '')
const assessorCanSave = computed(() => assessorSumOk.value && !savingAssessor.value)

const loadAssessor = async () => {
  loadingAssessor.value = true
  try {
    const list = await getAssessorWeights(COMPANY_ID)
    assessorRows.value = list.map(v => ({
      assessorCode: v.assessorCode,
      assessorName: v.assessorName,
      weight: v.weight,
    }))
  } catch (e) {
    console.error('加载测评对象权重失败:', e)
  } finally {
    loadingAssessor.value = false
  }
}

const saveAssessor = async () => {
  savingAssessor.value = true
  try {
    await saveAssessorWeights(
      COMPANY_ID,
      assessorRows.value.map(r => ({ assessorCode: r.assessorCode, weight: r.weight }))
    )
    ElMessage.success('测评对象权重已保存')
    await loadAssessor()
  } catch (e) {
    console.error('保存测评对象权重失败:', e)
  } finally {
    savingAssessor.value = false
  }
}

// ==================== 等级标准 ====================

/** 校验等级标准，返回第一个错误信息，无错误返回空串 */
const gradeError = computed(() => {
  const rows = gradeRows.value
  if (rows.length < 2) return '等级标准至少需要两行（负无穷～上限，下限～正无穷），点「新增区间」初始化'
  const last = rows.length - 1
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]
    if (!r.levelName.trim()) return `第${i + 1}行等级名称不能为空`
    if (i > 0 && r.lowerBound === null) return `第${i + 1}行下限不能为空`
    if (i < last && r.upperBound === null) return `第${i + 1}行上限不能为空`
    if (r.lowerBound !== null && r.upperBound !== null && r.lowerBound >= r.upperBound) {
      return `第${i + 1}行（${r.levelName}）下限必须小于上限`
    }
    if (i > 0 && rows[i - 1].upperBound !== r.lowerBound) {
      return `第${i}行与第${i + 1}行区间不衔接，存在空缺或重叠`
    }
  }
  return ''
})
const gradeOk = computed(() => gradeError.value === '')
const gradeCanSave = computed(() => gradeOk.value && !savingGrade.value)
const gradeHint = computed(() => (gradeOk.value ? '区间校验通过' : gradeError.value))

/** 修改第 index 行上限时，同步下一行下限，保证区间首尾衔接 */
const onUpperChange = (index: number) => {
  const next = gradeRows.value[index + 1]
  if (next) next.lowerBound = gradeRows.value[index].upperBound
}

/** 删除一行：首行删除则下一行变首行；末行删除则上一行变末行；中间行删除则并入下一行 */
const removeGradeRow = (index: number) => {
  const rows = gradeRows.value
  if (rows.length === 0) return
  if (rows.length === 1) {
    // 允许删空：保存校验会拦（至少两行），「新增区间」可一键重建骨架
    rows.splice(index, 1)
    return
  }
  if (index === 0) {
    rows[1].lowerBound = null
  } else if (index === rows.length - 1) {
    rows[index - 1].upperBound = null
  } else {
    rows[index + 1].lowerBound = rows[index - 1].upperBound
  }
  rows.splice(index, 1)
}

/**
 * 新增区间：按钮始终可点，行为按当前行数自适应——
 * 空表初始化首尾两行骨架；单行补足为骨架（单行上限被正无穷占位永远填不合法）；
 * 两行及以上插入到末行之前，末行保持为正无穷。填写是否合法由保存校验兜底。
 */
const insertGradeRow = () => {
  const rows = gradeRows.value
  if (rows.length === 0) {
    gradeRows.value = [
      { levelName: '', lowerBound: null, upperBound: null },
      { levelName: '', lowerBound: null, upperBound: null },
    ]
    return
  }
  if (rows.length === 1) {
    rows.push({ levelName: '', lowerBound: null, upperBound: null })
    return
  }
  const last = rows[rows.length - 1]
  rows.splice(rows.length - 1, 0, { levelName: '', lowerBound: last.lowerBound, upperBound: null })
}

const loadGrade = async () => {
  loadingGrade.value = true
  try {
    const list = await getGradeLevels(COMPANY_ID)
    gradeRows.value = list.map(v => ({
      levelName: v.levelName,
      lowerBound: v.lowerBound,
      upperBound: v.upperBound,
    }))
  } catch (e) {
    console.error('加载等级标准失败:', e)
  } finally {
    loadingGrade.value = false
  }
}

const saveGrade = async () => {
  savingGrade.value = true
  try {
    await saveGradeLevels(
      COMPANY_ID,
      gradeRows.value.map(r => ({
        levelName: r.levelName.trim(),
        lowerBound: r.lowerBound,
        upperBound: r.upperBound,
      }))
    )
    ElMessage.success('等级标准已保存')
    await loadGrade()
  } catch (e) {
    console.error('保存等级标准失败:', e)
  } finally {
    savingGrade.value = false
  }
}


// ==================== 等级分值 ====================

const levelError = computed(() => {
  const rows = levelRows.value
  // 后端固定返回 1-5 级骨架（空维度为 5 行 null）；行数不对只可能是接口异常/旧版本
  if (rows.length !== 5) return '等级分值应固定为 1-5 级共 5 行，请刷新页面重试'
  // 未填：后端缺字段/用户清空输入（null 与 undefined 都落在这）
  if (rows.some(r => !Number.isFinite(r.score as number))) return '请填写 1-5 级分值'
  if (rows.some(r => (r.score as number) <= 0)) return '分值必须大于 0'
  for (let i = 1; i < rows.length; i++) {
    if ((rows[i].score as number) <= (rows[i - 1].score as number)) {
      return `${rows[i].levelNo} 级分值必须大于 ${rows[i - 1].levelNo} 级`
    }
  }
  return ''
})
const levelOk = computed(() => levelError.value === '')
const levelCanSave = computed(() => levelOk.value && !savingLevel.value)

const loadLevel = async () => {
  loadingLevel.value = true
  try {
    const list = await getLevelScores(COMPANY_ID)
    // 未配置的级后端返回缺 score（JSON 省略 null），归一为 null，避免 undefined 绕过校验
    levelRows.value = list.map(v => ({ levelNo: v.levelNo, score: v.score ?? null }))
  } catch (e) {
    console.error('加载等级分值失败:', e)
  } finally {
    loadingLevel.value = false
  }
}

const saveLevel = async () => {
  savingLevel.value = true
  try {
    await saveLevelScores(
      COMPANY_ID,
      levelRows.value.map(r => ({ levelNo: r.levelNo, score: r.score }))
    )
    ElMessage.success('等级分值已保存')
    await loadLevel()
  } catch (e) {
    console.error('保存等级分值失败:', e)
  } finally {
    savingLevel.value = false
  }
}

onMounted(() => {
  loadAbility()
  loadAssessor()
  loadGrade()
  loadLevel()
})
</script>

<style scoped>
.assess-config-page {
  padding: 16px;
}

.page-title {
  font-size: 20px;
  margin: 0 0 4px;
}

.page-description {
  color: #909399;
  margin: 0 0 16px;
  font-size: 13px;
}

.config-block {
  margin-bottom: 16px;
}

.block-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.sum-line {
  margin-top: 10px;
  font-size: 13px;
  color: #606266;
}

.sum-error {
  color: #f56c6c;
}

.infinity {
  color: #909399;
}
</style>
