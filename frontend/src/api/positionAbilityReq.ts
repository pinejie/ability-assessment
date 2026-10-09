import request from '@/utils/request'
import type { PositionAbilityReqVO, PositionAbilityReqCreateDTO, PositionAbilityReqUpdateDTO } from '@/types/positionAbilityReq'

/**
 * 创建岗位能力要求
 */
export const createPositionAbilityReq = (data: PositionAbilityReqCreateDTO): Promise<number> => {
  return request.post('/position-ability-reqs', data)
}

/**
 * 更新岗位能力要求
 */
export const updatePositionAbilityReq = (data: PositionAbilityReqUpdateDTO): Promise<void> => {
  return request.put('/position-ability-reqs', data)
}

/**
 * 删除岗位能力要求
 */
export const deletePositionAbilityReq = (id: number): Promise<void> => {
  return request.delete(`/position-ability-reqs/${id}`)
}

/**
 * 根据ID查询岗位能力要求
 */
export const getPositionAbilityReqById = (id: number): Promise<PositionAbilityReqVO> => {
  return request.get(`/position-ability-reqs/${id}`)
}

/**
 * 查询所有岗位能力要求
 */
export const listPositionAbilityReqs = (): Promise<PositionAbilityReqVO[]> => {
  return request.get('/position-ability-reqs')
}

/**
 * 根据岗位ID查询能力要求列表
 */
export const listPositionAbilityReqsByJobTitleId = (jobTitleId: number): Promise<PositionAbilityReqVO[]> => {
  return request.get(`/position-ability-reqs/job-title/${jobTitleId}`)
}
