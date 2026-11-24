<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="900px"
    top="6vh"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
      <div class="form-section">
        <div class="form-section-title">基本信息</div>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="能力类别" prop="categoryId">
              <el-select v-model="form.categoryId" placeholder="选择所属类别" style="width: 100%">
                <el-option
                  v-for="c in categories"
                  :key="c.id"
                  :label="c.categoryName"
                  :value="c.id"
                  :disabled="c.status === 0"
                >
                  <span>{{ c.categoryName }}</span>
                  <span v-if="c.status === 0" class="option-muted">（已停用）</span>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="能力项编码">
              <el-input v-model="form.itemCode" maxlength="50" placeholder="如 L1 / M2，可空" />
              <div class="form-tip">同一岗位内不能重复</div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="能力项名称" prop="itemName">
          <el-input v-model="form.itemName" maxlength="100" show-word-limit placeholder="如：知人善用" />
        </el-form-item>

        <el-form-item label="能力项定义">
          <el-input
            v-model="form.definition"
            type="textarea"
            :rows="2"
            placeholder="可空。说明这项能力具体考察什么（测评页会显示）"
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="权重(%)" prop="weight">
              <el-input-number
                v-model="form.weight"
                :min="0"
                :max="99999"
                :precision="2"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
              <div class="form-tip">填百分数：8 表示 8%</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="排序">
              <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio :value="1">启用</el-radio>
                <el-radio :value="0">停用</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="评级主体" prop="rater">
          <el-checkbox v-model="rater.leader">直接上级</el-checkbox>
          <el-checkbox v-model="rater.r360">360 测评人</el-checkbox>
          <div class="form-tip">
            至少选一个。谁被勾上，发起测评时就由谁给这项打分（两个都勾 = 两边各打一次，取平均）
          </div>
        </el-form-item>

        <el-form-item label="评价主体描述">
          <el-input
            v-model="form.raterText"
            maxlength="100"
            show-word-limit
            placeholder="例：内部客户360＋直接上级"
          />
          <div class="form-tip">
            只做说明用，不参与算分。导入时自动带上 Excel 那一列的原文；
            同样是 360，「内部客户」和「跨部门」的区别就靠这行看出来
          </div>
        </el-form-item>
      </div>

      <div class="form-section">
        <div class="section-head">
          <div>
            <div class="form-section-title">等级（{{ form.levels.length }} 档）</div>
            <div class="form-tip section-desc">
              评价人在这几档里选一档。分值参与算分，描述是评价人判断的依据 ——
              只给「精进/能手」四个字，评价人无从判断该打几分
            </div>
          </div>
          <el-button size="small" @click="addLevel">添加等级</el-button>
        </div>

        <el-table :data="form.levels" size="small" border>
          <el-table-column label="名称" width="140">
            <template #default="{ row }">
              <el-input v-model="row.levelName" maxlength="50" placeholder="入门/精进" />
            </template>
          </el-table-column>
          <el-table-column label="分值" width="120">
            <template #default="{ row }">
              <el-input-number
                v-model="row.score"
                :min="0"
                :max="9999"
                :precision="2"
                :step="1"
                controls-position="right"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="排序" width="90">
            <template #default="{ row }">
              <el-input-number v-model="row.sort" :min="0" :max="9999" controls-position="right" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="行为描述">
            <template #default="{ row }">
              <el-input
                v-model="row.levelDesc"
                type="textarea"
                :rows="2"
                placeholder="这个等级的人具体是什么表现"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeLevel($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createPaItem, updatePaItem } from '@/api/paModel'
import type { PaCategoryVO, PaItemLevelSaveDTO, PaItemVO } from '@/types/paModel'

const props = defineProps<{
  modelValue: boolean
  postId: number
  /** 传 null 表示新增 */
  item: PaItemVO | null
  categories: PaCategoryVO[]
  /** 新增时默认落在左树选中的类别上 */
  defaultCategoryId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

interface LevelRow extends PaItemLevelSaveDTO {
  /** 只用于 el-table 的稳定 key */
  key: number
}

let levelKeySeed = 0
const nextKey = () => ++levelKeySeed

const emptyForm = () => ({
  categoryId: props.defaultCategoryId ?? undefined,
  itemCode: '',
  itemName: '',
  definition: '',
  raterText: '',
  weight: 0,
  sort: 0,
  status: 1,
  levels: [] as LevelRow[],
})

const form = reactive(emptyForm())
const rater = reactive({ leader: true, r360: false })

const title = computed(() => (props.item ? '编辑能力项' : '新增能力项'))

/** 默认两档：一档没区分度，两档是最低可用配置 */
const defaultLevels = (): LevelRow[] => [
  { key: nextKey(), levelName: '', score: 1, levelDesc: '', sort: 0 },
  { key: nextKey(), levelName: '', score: 4, levelDesc: '', sort: 1 },
]

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const target = props.item
    Object.assign(form, emptyForm())
    if (target) {
      Object.assign(form, {
        categoryId: target.categoryId,
        itemCode: target.itemCode ?? '',
        itemName: target.itemName,
        definition: target.definition ?? '',
        raterText: target.raterText ?? '',
        weight: Number(target.weight),
        sort: target.sort,
        status: target.status,
        levels: target.levels.map((l) => ({
          key: nextKey(),
          id: l.id,
          levelName: l.levelName,
          score: Number(l.score),
          levelDesc: l.levelDesc ?? '',
          sort: l.sort,
        })),
      })
      rater.leader = target.raterLeader === 1
      rater.r360 = target.rater360 === 1
    } else {
      form.levels = defaultLevels()
      rater.leader = true
      rater.r360 = false
    }
    formRef.value?.clearValidate()
  },
)

const rules: FormRules = {
  categoryId: [{ required: true, message: '请选择能力类别', trigger: 'change' }],
  itemName: [{ required: true, message: '请输入能力项名称', trigger: 'blur' }],
  weight: [{ required: true, message: '请输入权重', trigger: 'blur' }],
}

const addLevel = () => {
  // 默认排在最后，分值取「上一档 +1」方便连续填
  const last = form.levels[form.levels.length - 1]
  form.levels.push({
    key: nextKey(),
    levelName: '',
    score: last ? Number(last.score) + 1 : 1,
    levelDesc: '',
    sort: last ? Number(last.sort) + 1 : 0,
  })
}

const removeLevel = (index: number) => {
  form.levels.splice(index, 1)
}

/** 前端先拦一遍最常见的三类错，省一次来回（后端仍会全量校验） */
const validateLocally = (): string | null => {
  if (!rater.leader && !rater.r360) {
    return '评级主体至少选一个：没有主体的能力项没人能评'
  }
  if (form.levels.length < 2) {
    return '每个能力项至少配置 2 个等级'
  }
  for (const [i, l] of form.levels.entries()) {
    if (!l.levelName.trim()) {
      return `第 ${i + 1} 档的等级名称不能为空`
    }
    if (l.score == null) {
      return `等级「${l.levelName}」的分值不能为空`
    }
  }
  const sorts = new Set<number>()
  for (const l of form.levels) {
    const s = l.sort ?? 0
    if (sorts.has(s)) {
      return `等级排序号 ${s} 重复，同一能力项内必须唯一`
    }
    sorts.add(s)
  }
  const scores = new Set<number>()
  for (const l of form.levels) {
    if (scores.has(Number(l.score))) {
      return `等级分值 ${l.score} 重复，同一能力项内必须唯一`
    }
    scores.add(Number(l.score))
  }
  return null
}

const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const localError = validateLocally()
  if (localError) {
    ElMessage.warning(localError)
    return
  }

  saving.value = true
  try {
    const payload = {
      categoryId: form.categoryId as number,
      itemCode: form.itemCode.trim() || undefined,
      itemName: form.itemName.trim(),
      definition: form.definition.trim() || undefined,
      // 描述走「空串 = 清空」：用户把原文删了就该在页面上消失，不能被 || undefined 吞成「不改」
      raterText: form.raterText.trim(),
      weight: form.weight,
      raterLeader: rater.leader ? 1 : 0,
      rater360: rater.r360 ? 1 : 0,
      sort: form.sort,
      status: form.status,
      levels: form.levels.map((l) => ({
        id: l.id,
        levelName: l.levelName.trim(),
        score: l.score,
        levelDesc: l.levelDesc?.trim() || undefined,
        sort: l.sort,
      })),
    }
    if (props.item) {
      await updatePaItem(props.postId, { id: props.item.id, ...payload })
    } else {
      await createPaItem(props.postId, payload)
    }
    ElMessage.success('保存成功')
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    console.error('保存能力项失败:', error)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}

.section-desc {
  max-width: 640px;
  line-height: 1.6;
}

.option-muted {
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>
