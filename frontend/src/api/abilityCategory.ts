import request from '@/utils/request'
import type { AbilityCategoryVO, AbilityCategoryCreateDTO, AbilityCategoryUpdateDTO } from '@/types/abilityCategory'
import type { PageResult } from '@/types/common'

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
 * 分页查询能力类别
 */
export const pageAbilityCategories = (pageNum = 1, pageSize = 10): Promise<PageResult<AbilityCategoryVO>> => {
  return request.get('/ability-categories', { params: { pageNum, pageSize } })
}

/**
 * 查询所有能力类别（用于下拉框，不分页）
 */
export const listAbilityCategories = async (): Promise<AbilityCategoryVO[]> => {
  const result = await pageAbilityCategories(1, 1000)
  return result.list
}
