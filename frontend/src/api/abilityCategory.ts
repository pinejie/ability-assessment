import request from '@/utils/request'
import type { AbilityCategoryVO, AbilityCategoryCreateDTO, AbilityCategoryUpdateDTO } from '@/types/abilityCategory'

/**
 * 创建能力类别
 */
export const createAbilityCategory = (data: AbilityCategoryCreateDTO): Promise<number> => {
  return request.post('/ability-categories', data)
}

/**
 * 更新能力类别
 */
export const updateAbilityCategory = (data: AbilityCategoryUpdateDTO): Promise<void> => {
  return request.put('/ability-categories', data)
}

/**
 * 删除能力类别
 */
export const deleteAbilityCategory = (id: number): Promise<void> => {
  return request.delete(`/ability-categories/${id}`)
}

/**
 * 根据ID查询能力类别
 */
export const getAbilityCategoryById = (id: number): Promise<AbilityCategoryVO> => {
  return request.get(`/ability-categories/${id}`)
}

/**
 * 查询所有能力类别
 */
export const listAbilityCategories = (): Promise<AbilityCategoryVO[]> => {
  return request.get('/ability-categories')
}
