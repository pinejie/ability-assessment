import request from '@/utils/request'
import type { UserAbilityReqVO, UserAbilityReqCreateDTO, UserAbilityReqUpdateDTO } from '@/types/userAbilityReq'

/**
 * 创建人员能力要求（一对多）
 */
export const createUserAbilityReq = (data: UserAbilityReqCreateDTO): Promise<number> => {
  return request.post('/user-ability-reqs', data)
}

/**
 * 更新人员能力要求
 */
export const updateUserAbilityReq = (data: UserAbilityReqUpdateDTO): Promise<void> => {
  return request.put('/user-ability-reqs', data)
}

/**
 * 删除人员能力要求
 */
export const deleteUserAbilityReq = (id: number): Promise<void> => {
  return request.delete(`/user-ability-reqs/${id}`)
}

/**
 * 根据人员ID删除所有能力要求
 */
export const deleteUserAbilityReqsByResourceId = (resourceId: number): Promise<void> => {
  return request.delete(`/user-ability-reqs/resource/${resourceId}`)
}

/**
 * 根据ID查询人员能力要求
 */
export const getUserAbilityReqById = (id: number): Promise<UserAbilityReqVO> => {
  return request.get(`/user-ability-reqs/${id}`)
}

/**
 * 查询所有人员能力要求
 */
export const listUserAbilityReqs = (): Promise<UserAbilityReqVO[]> => {
  return request.get('/user-ability-reqs')
}

/**
 * 根据人员ID查询能力要求列表
 */
export const listUserAbilityReqsByResourceId = (resourceId: number): Promise<UserAbilityReqVO[]> => {
  return request.get(`/user-ability-reqs/resource/${resourceId}`)
}
