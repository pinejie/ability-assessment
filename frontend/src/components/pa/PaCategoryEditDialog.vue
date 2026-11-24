<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="520px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
      <el-form-item label="类别名称" prop="categoryName">
        <el-input v-model="form.categoryName" maxlength="100" show-word-limit placeholder="如：领导力 / 管理力 / 质检专业" />
        <div class="form-tip">同一岗位内类别名不能重复；跨岗位同名互不影响</div>
      </el-form-item>
      <el-form-item label="类别说明">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="可空。写清这类能力考察什么，填测评时评价人看得更明白"
        />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
        <div class="form-tip">数字小的排前面</div>
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSubmit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createPaCategory, updatePaCategory } from '@/api/paModel'
import type { PaCategoryVO } from '@/types/paModel'

const props = defineProps<{
  modelValue: boolean
  postId: number
  /** 传 null 表示新增 */
  category: PaCategoryVO | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: []
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)

const emptyForm = () => ({
  categoryName: '',
  description: '',
  sort: 0,
  status: 1,
})

const form = reactive(emptyForm())

const title = ref('新增能力类别')

/** 每次打开都重置：编辑保存后再点新增，不能带着上一条的数据 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const target = props.category
    if (target) {
      Object.assign(form, {
        categoryName: target.categoryName,
        description: target.description ?? '',
        sort: target.sort,
        status: target.status,
      })
      title.value = '编辑能力类别'
    } else {
      Object.assign(form, emptyForm())
      title.value = '新增能力类别'
    }
    formRef.value?.clearValidate()
  },
)

const rules: FormRules = {
  categoryName: [{ required: true, message: '请输入类别名称', trigger: 'blur' }],
}

const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload = {
      categoryName: form.categoryName.trim(),
      description: form.description.trim() || undefined,
      sort: form.sort,
      status: form.status,
    }
    if (props.category) {
      await updatePaCategory(props.postId, { id: props.category.id, ...payload })
    } else {
      await createPaCategory(props.postId, payload)
    }
    ElMessage.success('保存成功')
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    console.error('保存能力类别失败:', error)
  } finally {
    saving.value = false
  }
}
</script>
