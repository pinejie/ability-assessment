import request from '@/utils/request'
import type { OrgAbilityReqVO, OrgAbilityReqCreateDTO, OrgAbilityReqUpdateDTO } from '@/types/orgAbilityReq'

/**
 * 创建部门能力要求
 */
export const createOrgAbilityReq = (data: OrgAbilityReqCreateDTO): Promise<number> => {
  return request.post('/org-ability-reqs', data)
}

/**
 * 更新部门能力要求
 */
export const updateOrgAbilityReq = (data: OrgAbilityReqUpdateDTO): Promise<void> => {
  return request.put('/org-ability-reqs', data)
}

/**
 * 删除部门能力要求
 */
export const deleteOrgAbilityReq = (id: number): Promise<void> => {
  return request.delete(`/org-ability-reqs/${id}`)
}

/**
 * 根据ID查询部门能力要求
 */
export const getOrgAbilityReqById = (id: number): Promise<OrgAbilityReqVO> => {
  return request.get(`/org-ability-reqs/${id}`)
}

/**
 * 查询所有部门能力要求
 */
export const listOrgAbilityReqs = (): Promise<OrgAbilityReqVO[]> => {
  return request.get('/org-ability-reqs')
}

/**
 * 根据部门ID查询能力要求列表
 */
export const listOrgAbilityReqsByDepartmentId = (departmentId: number): Promise<OrgAbilityReqVO[]> => {
  return request.get(`/org-ability-reqs/department/${departmentId}`)
}
