<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑岗位' : '新增岗位'"
    width="560px"
    top="8vh"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form ref="formRef" :model="formData" :rules="rules" label-width="90px">
      <el-form-item label="岗位范围">
        <el-radio-group v-model="formData.generic" @change="onGenericChange">
          <el-radio :value="false">指定部门</el-radio>
          <el-radio :value="true">全公司通用</el-radio>
        </el-radio-group>
        <div class="form-tip">
          会计、行政专员这种每个部门都有、干的活又一样的岗位选「全公司通用」：只维护一套能力模型，
          所有部门的人都能用它发起测评。部门的岗位不勾选，仍按部门各配一套。
        </div>
      </el-form-item>
      <el-form-item v-if="!formData.generic" label="部门" prop="departmentId">
        <el-tree-select
          v-model="formData.departmentId"
          :data="treeData"
          :props="deptTreeProps"
          node-key="id"
          check-strictly
          filterable
          placeholder="请选择部门"
          style="width: 100%"
        />
        <div class="form-tip">只能选到部门，公司节点本身不能挂岗位</div>
      </el-form-item>
      <el-form-item label="岗位名称" prop="postName">
        <el-input v-model="formData.postName" maxlength="100" placeholder="如：人事专员" />
      </el-form-item>
      <el-form-item label="岗位编码" prop="postCode">
        <el-input v-model="formData.postCode" maxlength="50" placeholder="可空，用于导入时对齐" />
      </el-form-item>
      <el-form-item label="岗位描述" prop="description">
        <el-input
          v-model="formData.description"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="可空"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="排序" prop="sort">
        <el-input-number v-model="formData.sort" :min="0" :max="9999" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createPaPost, updatePaPost } from '@/api/paPost'
import type { PaPostVO } from '@/types/paPost'
import { deptTreeProps, type DeptTreeNode } from '@/composables/useDepartmentTree'

const props = defineProps<{
  modelValue: boolean
  /** 传 null 表示新增 */
  post: PaPostVO | null
  treeData: DeptTreeNode[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const isEdit = computed(() => !!props.post?.id)

const formRef = ref<FormInstance>()
const submitting = ref(false)

const emptyForm = () => ({
  id: 0,
  /** true = 全公司通用岗位，此时 departmentId 不参与提交 */
  generic: false,
  departmentId: undefined as number | undefined,
  postName: '',
  postCode: '',
  description: '',
  status: 1,
  sort: 0,
})

const formData = reactive(emptyForm())

const rules: FormRules = {
  // 通用岗位没有部门，必填校验跟着 radio 走，否则勾了「全公司通用」还是过不了校验
  departmentId: [
    {
      validator: (_r, value, cb) => {
        if (formData.generic || value) return cb()
        cb(new Error('请选择部门'))
      },
      trigger: 'change',
    },
  ],
  postName: [
    { required: true, message: '请输入岗位名称', trigger: 'blur' },
    { max: 100, message: '岗位名称不能超过 100 字', trigger: 'blur' },
  ],
}

/** 切到通用就把已选部门清掉 —— 留着会在提交时被误当成「指定部门」 */
const onGenericChange = () => {
  if (formData.generic) {
    formData.departmentId = undefined
  }
  formRef.value?.clearValidate('departmentId')
}

/** 每次打开都按传入的岗位重填，避免上一次编辑的残留 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    const p = props.post
    Object.assign(formData, emptyForm())
    if (p) {
      formData.id = p.id
      // generic 从 departmentId 判：老接口没有这个字段时也成立
      formData.generic = p.generic ?? p.departmentId == null
      formData.departmentId = p.departmentId ?? undefined
      formData.postName = p.postName
      formData.postCode = p.postCode || ''
      formData.description = p.description || ''
      formData.status = p.status
      formData.sort = p.sort
    }
  },
  { immediate: true },
)

const handleSubmit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      // 通用岗位显式传 null：更新接口里 null 的语义是「改为通用」，
      // 传 undefined 会被序列化时丢掉，后端读到的也是 null，两者等价但写明更好读
      departmentId: formData.generic ? null : formData.departmentId!,
      postName: formData.postName.trim(),
      postCode: formData.postCode.trim() || undefined,
      description: formData.description.trim() || undefined,
      status: formData.status,
      sort: formData.sort,
    }
    if (formData.id) {
      await updatePaPost({ id: formData.id, ...payload })
      ElMessage.success('更新成功')
    } else {
      await createPaPost(payload)
      ElMessage.success('创建成功')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (error) {
    console.error('保存岗位失败:', error)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.form-tip {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
  margin-top: 2px;
}
</style>
