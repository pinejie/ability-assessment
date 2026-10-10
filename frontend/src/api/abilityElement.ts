import request from '@/utils/request'
import type { AbilityElementVO, AbilityElementCreateDTO, AbilityElementUpdateDTO } from '@/types/abilityElement'
import type { PageResult } from '@/types/common'

/**
 * 创建能力要素
 */
export const createAbilityElement = (data: AbilityElementCreateDTO): Promise<number> => {
  return request.post('/ability-elements', data)
}

/**
 * 更新能力要素
 */
export const updateAbilityElement = (data: AbilityElementUpdateDTO): Promise<void> => {
  return request.put('/ability-elements', data)
}

/**
 * 删除能力要素
 */
export const deleteAbilityElement = (id: number): Promise<void> => {
  return request.delete(`/ability-elements/${id}`)
}

/**
 * 根据ID查询能力要素
 */
export const getAbilityElementById = (id: number): Promise<AbilityElementVO> => {
  return request.get(`/ability-elements/${id}`)
}

/**
 * 分页查询能力要素
 */
export const pageAbilityElements = (pageNum = 1, pageSize = 10): Promise<PageResult<AbilityElementVO>> => {
  return request.get('/ability-elements', { params: { pageNum, pageSize } })
}

/**
 * 根据类别ID分页查询要素列表
 */
export const pageElementsByCategoryId = (categoryId: number, pageNum = 1, pageSize = 10): Promise<PageResult<AbilityElementVO>> => {
  return request.get(`/ability-elements/category/${categoryId}`, { params: { pageNum, pageSize } })
}

/**
 * 查询所有能力要素（用于下拉框，不分页）
 */
export const listAbilityElements = async (): Promise<AbilityElementVO[]> => {
  const result = await pageAbilityElements(1, 1000)
  return result.list
}

/**
 * 根据类别ID查询所有能力要素（用于下拉框，只获取启用状态）
 */
export const listElementsByCategoryId = async (categoryId: number): Promise<AbilityElementVO[]> => {
  return await request.get(`/ability-elements/list/category/${categoryId}?status=1`)
}
