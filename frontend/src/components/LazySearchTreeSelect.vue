<template>
  <div class="lazy-search-tree-select">
    <el-popover
      v-model:visible="popoverVisible"
      placement="bottom-start"
      :width="popoverWidth"
      trigger="click"
    >
      <template #reference>
        <div class="select-trigger" :class="{ 'is-focus': popoverVisible }">
          <div class="select-input">
            <input
              ref="inputRef"
              v-model="searchKeyword"
              :placeholder="currentPlaceholder"
              class="input-inner"
              @input="handleInput"
              @focus="handleFocus"
            />
            <span v-if="showSelectedLabel" class="selected-label" :class="{ 'is-dimmed': isDimmed }">
              {{ selectedLabel }}
            </span>
          </div>
          <span class="suffix-icon">
            <el-icon><ArrowDown /></el-icon>
          </span>
        </div>
      </template>

      <div class="tree-container" @click.stop>
        <el-tree
          :key="treeKey"
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          node-key="id"
          :lazy="!isSearchMode"
          :load="loadNode"
          :highlight-current="true"
          :expand-on-click-node="false"
          :default-expand-all="isSearchMode"
          empty-text="暂无数据"
          @node-click="handleNodeClick"
        >
          <template #default="{ node, data }">
            <span class="tree-node">
              <slot name="node" :node="node" :data="data">
                <span>{{ data[labelKey] }}</span>
              </slot>
            </span>
          </template>
        </el-tree>
      </div>
    </el-popover>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'

interface TreeNode {
  id: number | string
  [key: string]: any
}

interface Props {
  modelValue?: number | string
  load?: (node: TreeNode | null) => Promise<TreeNode[]>
  search?: (keyword: string) => Promise<TreeNode[]>
  fieldNames?: {
    label?: string
    value?: string
    children?: string
    disabled?: string | ((data: TreeNode) => boolean)
  }
  placeholder?: string
  width?: number
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: undefined,
  load: undefined,
  search: undefined,
  fieldNames: () => ({
    label: 'label',
    value: 'value',
    children: 'children'
  }),
  placeholder: '请选择',
  width: 300
})

const emit = defineEmits<{
  'update:modelValue': [value: number | string | undefined]
  'change': [value: number | string | undefined, data: TreeNode]
}>()

// 状态
const popoverVisible = ref(false)
const searchKeyword = ref('')
const treeData = ref<TreeNode[]>([])
const inputRef = ref<HTMLInputElement>()
const treeRef = ref()
const isSearchMode = ref(false)
const treeKey = ref(0) // 用于强制 el-tree 重新渲染

// 字段名
const labelKey = computed(() => props.fieldNames.label || 'label')
const valueKey = computed(() => props.fieldNames.value || 'value')
const childrenKey = computed(() => props.fieldNames.children || 'children')

// 计算属性
const popoverWidth = computed(() => props.width)

const showSelectedLabel = computed(() => {
  return !!selectedLabel.value && (!popoverVisible.value || !searchKeyword.value)
})

const isDimmed = computed(() => popoverVisible.value)

const currentPlaceholder = computed(() => {
  if (isSearchMode.value) return '输入关键字搜索...'
  if (!selectedLabel.value) return props.placeholder
  if (popoverVisible.value && searchKeyword.value) return '输入关键字搜索...'
  return selectedLabel.value
})

const selectedLabel = computed(() => {
  if (!props.modelValue || !treeRef.value) return ''
  const node = treeRef.value.getNode(props.modelValue)
  return node ? node.data[labelKey.value] : ''
})

const treeProps = computed(() => ({
  label: labelKey.value,
  children: childrenKey.value,
  disabled: props.fieldNames.disabled,
  isLeaf: (data: TreeNode) => {
    if (typeof data.isLeaf === 'boolean') return data.isLeaf
    if (typeof data.hasChildren === 'boolean') return !data.hasChildren
    if (data.type === 'title') return true
    return false
  }
}))

// el-tree 的 lazy load 回调
const loadNode = async (node: any, resolve: (data: TreeNode[]) => void) => {
  if (!props.load) {
    resolve([])
    return
  }

  try {
    // node.level === 0 表示根节点
    const nodeData = node.level === 0 ? null : node.data
    const children = await props.load(nodeData)
    resolve(children)
  } catch (error) {
    console.error('加载节点失败:', error)
    resolve([])
  }
}

// 防抖处理输入（移除 watch，避免重复处理）
let searchTimer: any = null
const handleInput = () => {
  clearTimeout(searchTimer)
  const keyword = searchKeyword.value.trim()

  if (!keyword) {
    // 关键字为空，恢复浏览模式
    if (isSearchMode.value) {
      isSearchMode.value = false
      // 清空数据，el-tree 会通过 lazy load 重新加载
      treeData.value = []
      treeKey.value++ // 强制 el-tree 重新渲染
    }
    return
  }

  // 有关键字，执行搜索
  searchTimer = setTimeout(async () => {
    if (props.search) {
      try {
        const results = await props.search(keyword)
        treeData.value = results
        isSearchMode.value = true
        treeKey.value++ // 强制 el-tree 重新渲染
      } catch (error) {
        console.error('搜索失败:', error)
        treeData.value = []
        isSearchMode.value = true
        treeKey.value++
      }
    }
  }, 300)
}

// 节点点击
const handleNodeClick = (data: TreeNode) => {
  const disabled = props.fieldNames.disabled
  if (disabled) {
    const isDisabled = typeof disabled === 'function' ? disabled(data) : data[disabled]
    if (isDisabled) return
  }

  const value = data[valueKey.value]
  emit('update:modelValue', value)
  emit('change', value, data)

  popoverVisible.value = false
  searchKeyword.value = ''
}

// 聚焦
const handleFocus = () => {
  if (isSearchMode.value) return
  searchKeyword.value = ''
}
</script>

<style scoped>
.lazy-search-tree-select {
  width: 100%;
}

.select-trigger {
  display: flex;
  align-items: center;
  padding: 0 12px;
  height: 40px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s;
}

.select-trigger:hover {
  border-color: #c0c4cc;
}

.select-trigger.is-focus {
  border-color: #409eff;
}

.select-input {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  height: 100%;
  overflow: hidden;
}

.input-inner {
  width: 100%;
  height: 100%;
  border: none;
  outline: none;
  font-size: 14px;
  background: transparent;
  position: relative;
  z-index: 1;
}

.input-inner::placeholder {
  color: #c0c4cc;
}

.selected-label {
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  color: #606266;
  font-size: 14px;
  font-family: "Helvetica Neue", Helvetica, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "微软雅黑", Arial, sans-serif;
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  z-index: 2;
}

.selected-label.is-dimmed {
  color: #c0c4cc;
}

.suffix-icon {
  margin-left: 8px;
  color: #c0c4cc;
  font-size: 14px;
  flex-shrink: 0;
}

.tree-container {
  max-height: 300px;
  overflow-y: auto;
}

.tree-node {
  display: flex;
  align-items: center;
}
</style>
