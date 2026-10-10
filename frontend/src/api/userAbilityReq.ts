import request from '@/utils/request'
import type { UserAbilityReqVO, UserAbilityReqCreateDTO, UserAbilityReqUpdateDTO } from '@/types/userAbilityReq'
import type { PageResult } from '@/types/common'

/**
 * 创建人员能力要求（一主多从）
 */
export const createUserAbilityReq = (data: UserAbilityReqCreateDTO): Promise<number> => {
  return request.post('/user-ability-reqs-new', data)
}

/**
 * 更新人员能力要求
 */
export const updateUserAbilityReq = (data: UserAbilityReqUpdateDTO): Promise<void> => {
  return request.put('/user-ability-reqs-new', data)
}

/**
 * 删除人员能力要求
 */
export const deleteUserAbilityReq = (id: number): Promise<void> => {
  return request.delete(`/user-ability-reqs-new/${id}`)
}

/**
 * 根据ID查询人员能力要求
 */
export const getUserAbilityReqById = (id: number): Promise<UserAbilityReqVO> => {
  return request.get(`/user-ability-reqs-new/${id}`)
}

/**
 * 分页查询所有人员能力要求
 */
export const pageUserAbilityReqs = (pageNum = 1, pageSize = 10): Promise<PageResult<UserAbilityReqVO>> => {
  return request.get('/user-ability-reqs-new', { params: { pageNum, pageSize } })
}

/**
 * 根据人员ID查询能力要求
 */
export const getUserAbilityReqByResourceId = (resourceId: number): Promise<UserAbilityReqVO | null> => {
  return request.get(`/user-ability-reqs-new/by-resource/${resourceId}`)
}
