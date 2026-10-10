<template>
  <div class="resource-autocomplete" ref="containerRef">
    <el-input
      v-model="searchKeyword"
      :placeholder="placeholder"
      @focus="handleFocus"
      @input="handleInput"
      @keydown="handleKeydown"
      clearable
    >
      <template #prefix>
        <el-icon><User /></el-icon>
      </template>
    </el-input>

    <transition name="el-zoom-in-top">
      <div v-show="dropdownVisible" class="dropdown-list" ref="dropdownRef">
        <!-- 加载中 -->
        <div v-if="loading" class="dropdown-item loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>加载中...</span>
        </div>

        <!-- 无数据 -->
        <div v-else-if="displayList.length === 0" class="dropdown-item empty">
          <span>暂无数据</span>
        </div>

        <!-- 列表项 -->
        <template v-else>
          <div
            v-for="(item, index) in displayList"
            :key="item.id"
            class="dropdown-item"
            :class="{ 'is-active': index === activeIndex }"
            @click="handleSelect(item)"
            @mouseenter="activeIndex = index"
          >
            <div class="item-content">
              <div class="item-name">{{ item.lastname }}</div>
              <div class="item-path">
                <span v-if="item.companyName">{{ item.companyName }}</span>
                <span v-if="item.companyName && item.departmentName"> / </span>
                <span v-if="item.departmentName">{{ item.departmentName }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { User, Loading } from '@element-plus/icons-vue'
import request from '@/utils/request'

interface Resource {
  id: number
  lastname: string
  departmentid: number
  departmentName: string
  companyName: string
  jobtitle?: number
}

interface RecentResource extends Resource {
  selectedAt: number
}

interface Props {
  modelValue?: number
  placeholder?: string
  maxRecent?: number
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: undefined,
  placeholder: '请选择或搜索人员',
  maxRecent: 10
})

const emit = defineEmits<{
  'update:modelValue': [value: number | undefined]
  'change': [value: number | undefined, data: Resource | undefined]
}>()

// 状态
const searchKeyword = ref('')
const dropdownVisible = ref(false)
const loading = ref(false)
const searchResults = ref<Resource[]>([])
const recentResources = ref<RecentResource[]>([])
const activeIndex = ref(-1)
const containerRef = ref<HTMLElement>()
const dropdownRef = ref<HTMLElement>()

// 搜索防抖
let searchTimer: any = null

// localStorage key
const STORAGE_KEY = 'recent_persons'

// 显示列表（优先显示搜索结果，否则显示最近记录）
const displayList = computed(() => {
  if (searchKeyword.value.trim()) {
    return searchResults.value
  }
  return recentResources.value
})

// 加载最近记录
const loadRecentResources = () => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) {
      recentResources.value = JSON.parse(stored)
    }
  } catch (e) {
    console.error('加载最近记录失败:', e)
    recentResources.value = []
  }
}

// 保存到最近记录
const saveToRecent = (resource: Resource) => {
  const now = Date.now()
  const existing = recentResources.value.filter(r => r.id !== resource.id)
  const newRecent: RecentResource = {
    ...resource,
    selectedAt: now
  }
  const updated = [newRecent, ...existing].slice(0, props.maxRecent)
  recentResources.value = updated

  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated))
  } catch (e) {
    console.error('保存最近记录失败:', e)
  }
}

// 搜索人员
const searchResources = async (keyword: string) => {
  if (!keyword.trim()) {
    searchResults.value = []
    return
  }

  loading.value = true
  try {
    const results = await request.get(`/search/resources?keyword=${encodeURIComponent(keyword)}&limit=10`)
    searchResults.value = results
  } catch (error) {
    console.error('搜索人员失败:', error)
    searchResults.value = []
  } finally {
    loading.value = false
  }
}

// 获取焦点
const handleFocus = () => {
  dropdownVisible.value = true
  activeIndex.value = -1
}

// 输入处理（防抖）
const handleInput = () => {
  clearTimeout(searchTimer)
  const keyword = searchKeyword.value.trim()

  if (!keyword) {
    searchResults.value = []
    return
  }

  searchTimer = setTimeout(() => {
    searchResources(keyword)
  }, 300)
}

// 键盘导航
const handleKeydown = (e: KeyboardEvent) => {
  if (!dropdownVisible.value) return

  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      activeIndex.value = Math.min(activeIndex.value + 1, displayList.value.length - 1)
      scrollToActiveItem()
      break
    case 'ArrowUp':
      e.preventDefault()
      activeIndex.value = Math.max(activeIndex.value - 1, 0)
      scrollToActiveItem()
      break
    case 'Enter':
      e.preventDefault()
      if (activeIndex.value >= 0 && activeIndex.value < displayList.value.length) {
        handleSelect(displayList.value[activeIndex.value])
      }
      break
    case 'Escape':
      dropdownVisible.value = false
      break
  }
}

// 滚动到激活项
const scrollToActiveItem = () => {
  if (!dropdownRef.value || activeIndex.value < 0) return
  const items = dropdownRef.value.querySelectorAll('.dropdown-item')
  const activeItem = items[activeIndex.value] as HTMLElement
  if (activeItem) {
    activeItem.scrollIntoView({ block: 'nearest' })
  }
}

// 选择人员
const handleSelect = async (resource: Resource) => {
  searchKeyword.value = resource.lastname
  dropdownVisible.value = false

  // 检查数据完整性（从 localStorage 加载的旧数据可能缺少 jobtitle 字段）
  let selectedResource = resource
  if (!resource.jobtitle && resource.jobtitle !== 0) {
    // 重新搜索获取完整数据
    try {
      const results = await request.get(`/search/resources?keyword=${encodeURIComponent(resource.lastname)}&limit=10`)
      const fullResource = results.find((r: Resource) => r.id === resource.id)
      if (fullResource) {
        selectedResource = fullResource
      }
    } catch (error) {
      console.error('重新搜索人员信息失败:', error)
    }
  }

  saveToRecent(selectedResource)
  emit('update:modelValue', selectedResource.id)
  emit('change', selectedResource.id, selectedResource)
}

// 点击外部关闭
const handleClickOutside = (e: MouseEvent) => {
  if (containerRef.value && !containerRef.value.contains(e.target as Node)) {
    dropdownVisible.value = false
  }
}

// 监听 modelValue 变化，更新显示
watch(
  () => props.modelValue,
  async (newVal) => {
    if (!newVal) {
      searchKeyword.value = ''
      return
    }
    // 尝试从最近记录中找到对应的人员
    const recent = recentResources.value.find(r => r.id === newVal)
    if (recent) {
      searchKeyword.value = recent.lastname
    } else {
      // 如果不在最近记录中，通过 API 查询
      try {
        const results = await request.get(`/resources/${newVal}`)
        if (results && results.lastname) {
          searchKeyword.value = results.lastname
        }
      } catch (error) {
        console.error('查询人员信息失败:', error)
      }
    }
  },
  { immediate: true }
)

onMounted(() => {
  loadRecentResources()
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  clearTimeout(searchTimer)
})
</script>

<style scoped>
.resource-autocomplete {
  position: relative;
  width: 100%;
}

.dropdown-list {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  max-height: 300px;
  overflow-y: auto;
  z-index: 2000;
}

.dropdown-item {
  padding: 10px 12px;
  cursor: pointer;
  transition: background-color 0.2s;
  border-bottom: 1px solid #f0f0f0;
}

.dropdown-item:last-child {
  border-bottom: none;
}

.dropdown-item:hover,
.dropdown-item.is-active {
  background-color: #f5f7fa;
}

.dropdown-item.loading,
.dropdown-item.empty {
  color: #909399;
  font-size: 14px;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.item-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.item-name {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.item-path {
  font-size: 12px;
  color: #909399;
}
</style>
