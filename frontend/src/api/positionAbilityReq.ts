import request from '@/utils/request'
import type { PositionAbilityReqVO, PositionAbilityReqCreateDTO, PositionAbilityReqUpdateDTO } from '@/types/positionAbilityReq'
import type { PageResult } from '@/types/common'

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
 * 分页查询所有岗位能力要求
 */
export const pagePositionAbilityReqs = (pageNum = 1, pageSize = 10): Promise<PageResult<PositionAbilityReqVO>> => {
  return request.get('/position-ability-reqs', { params: { pageNum, pageSize } })
}

/**
 * 根据岗位ID分页查询能力要求列表
 */
export const pagePositionAbilityReqsByJobTitleId = (jobTitleId: number, pageNum = 1, pageSize = 10): Promise<PageResult<PositionAbilityReqVO>> => {
  return request.get(`/position-ability-reqs/job-title/${jobTitleId}`, { params: { pageNum, pageSize } })
}

/**
 * 根据部门ID查询岗位列表（从人员表中获取不同岗位）
 */
export const listJobTitlesByDepartment = (departmentId: number): Promise<{ id: number; name: string }[]> => {
  return request.get(`/job-titles/by-department?departmentId=${departmentId}`)
}

/**
 * 根据部门和岗位查询能力要求配置
 */
export const getPositionAbilityReqByDeptAndJobTitle = (
  departmentId: number,
  jobTitleId: number
): Promise<PositionAbilityReqVO | null> => {
  return request.get(`/position-ability-reqs/by-department-jobtitle?departmentId=${departmentId}&jobTitleId=${jobTitleId}`)
}
