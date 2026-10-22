import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listAuthorizedDimensions } from '@/api/assessDimension'
import { DIMENSION_STORAGE_KEY } from '@/utils/dimensionKey'
import type { AssessDimensionVO } from '@/types/assessDimension'

/**
 * 维度（类租户）全局状态：可用维度列表 + 当前选中维度。
 * 切换维度后写入 localStorage，请求自动携带 X-Dimension-Id，页面刷新生效。
 */
export const useDimensionStore = defineStore('dimension', () => {
  const dimensions = ref<AssessDimensionVO[]>([])
  const currentId = ref<number | null>(Number(localStorage.getItem(DIMENSION_STORAGE_KEY)) || null)
  const loading = ref(false)
  /** 首次 authorized 是否已返回（布局用它区分「加载中」与「确无可访问租户」） */
  const loaded = ref(false)

  const setCurrent = (id: number | null) => {
    currentId.value = id
    if (id) {
      localStorage.setItem(DIMENSION_STORAGE_KEY, String(id))
    } else {
      localStorage.removeItem(DIMENSION_STORAGE_KEY)
    }
  }

  const load = async () => {
    loading.value = true
    try {
      dimensions.value = await listAuthorizedDimensions()
      // 选中值失效（首次进入 / 维度被删或禁用 / 无任何可访问维度）时回落到第一个可用维度
      if (!dimensions.value.some(d => d.id === currentId.value)) {
        setCurrent(dimensions.value[0]?.id ?? null)
      }
    } finally {
      loading.value = false
      loaded.value = true
    }
  }

  return { dimensions, currentId, loading, loaded, setCurrent, load }
})
