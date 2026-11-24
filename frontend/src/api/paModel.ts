import request from '@/utils/request'
import type {
  PaBandVO,
  PaCategorySaveDTO,
  PaCategoryVO,
  PaItemSaveDTO,
  PaModelVO,
} from '@/types/paModel'

/** 读整个岗位能力模型（类别 + 能力项 + 等级 + 区间 + 权重合计） */
export const getPaModel = (postId: number): Promise<PaModelVO> => {
  return request.get(`/pa/posts/${postId}/model`)
}

// ------------------------------------------------ 能力类别

export const createPaCategory = (postId: number, data: PaCategorySaveDTO): Promise<number> => {
  return request.post(`/pa/posts/${postId}/categories`, data)
}

export const updatePaCategory = (
  postId: number,
  data: PaCategorySaveDTO & { id: number },
): Promise<void> => {
  return request.put(`/pa/posts/${postId}/categories`, data)
}

/** 类别下有能项时后端拒绝，提示先移走或停用 */
export const deletePaCategory = (postId: number, categoryId: number): Promise<void> => {
  return request.delete(`/pa/posts/${postId}/categories/${categoryId}`)
}

// ------------------------------------------------ 能力项（含等级）

export const createPaItem = (postId: number, data: PaItemSaveDTO): Promise<number> => {
  return request.post(`/pa/posts/${postId}/items`, data)
}

/** levels 传数组表示整批覆盖等级 */
export const updatePaItem = (postId: number, data: PaItemSaveDTO): Promise<void> => {
  return request.put(`/pa/posts/${postId}/items`, data)
}

/** 被测评引用时后端拒绝，提示改为停用 */
export const deletePaItem = (postId: number, itemId: number): Promise<void> => {
  return request.delete(`/pa/posts/${postId}/items/${itemId}`)
}

// ------------------------------------------------ 分值区间

export const listPaBands = (postId: number): Promise<PaBandVO[]> => {
  return request.get(`/pa/posts/${postId}/bands`)
}

/** 整批保存：重叠/断档/首尾不开口都会被后端拒绝 */
export const savePaBands = (postId: number, bands: PaBandVO[]): Promise<void> => {
  return request.put(`/pa/posts/${postId}/bands`, bands)
}

export type { PaCategoryVO, PaModelVO }
