import request from '@/utils/request'
import type { AbilityElementLevelVO, AbilityElementLevelCreateDTO, AbilityElementLevelUpdateDTO } from '@/types/abilityElementLevel'
import type { PageResult } from '@/types/common'

/**
 * 创建等级配置
 */
export const createAbilityElementLevel = (data: AbilityElementLevelCreateDTO): Promise<number> => {
  return request.post('/ability-element-levels', data)
}

/**
 * 更新等级配置
 */
export const updateAbilityElementLevel = (data: AbilityElementLevelUpdateDTO): Promise<void> => {
  return request.put('/ability-element-levels', data)
}

/**
 * 删除等级配置
 */
export const deleteAbilityElementLevel = (id: number): Promise<void> => {
  return request.delete(`/ability-element-levels/${id}`)
}

/**
 * 根据ID查询等级配置
 */
export const getAbilityElementLevelById = (id: number): Promise<AbilityElementLevelVO> => {
  return request.get(`/ability-element-levels/${id}`)
}

/**
 * 根据能力要素ID分页查询等级列表
 */
export const pageAbilityElementLevelsByElementId = (elementId: number, pageNum = 1, pageSize = 10): Promise<PageResult<AbilityElementLevelVO>> => {
  return request.get(`/ability-element-levels/element/${elementId}`, { params: { pageNum, pageSize } })
}

/**
 * 根据能力要素ID查询所有等级（用于下拉框，不分页）
 */
export const listAbilityElementLevelsByElementId = async (elementId: number): Promise<AbilityElementLevelVO[]> => {
  const result = await pageAbilityElementLevelsByElementId(elementId, 1, 1000)
  return result.list
}
