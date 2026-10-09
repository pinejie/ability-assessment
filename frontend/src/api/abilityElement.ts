import request from '@/utils/request'
import type { AbilityElementVO, AbilityElementCreateDTO, AbilityElementUpdateDTO } from '@/types/abilityElement'

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
 * 查询所有能力要素
 */
export const listAbilityElements = (): Promise<AbilityElementVO[]> => {
  return request.get('/ability-elements')
}

/**
 * 根据类别ID查询要素列表
 */
export const listElementsByCategoryId = (categoryId: number): Promise<AbilityElementVO[]> => {
  return request.get(`/ability-elements/category/${categoryId}`)
}
