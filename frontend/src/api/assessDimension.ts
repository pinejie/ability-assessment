import request from '@/utils/request'
import type {
  AssessDimensionVO,
  AssessDimensionCreateDTO,
  AssessDimensionUpdateDTO,
  AssessDimensionAuthVO,
  DimensionAuthItemDTO,
  DimensionScopeVO,
} from '@/types/assessDimension'

/**
 * 查询全部维度（管理页）
 */
export const listAllDimensions = (): Promise<AssessDimensionVO[]> => {
  return request.get('/assess-dimensions')
}

/**
 * 查询指定人员可用的维度（顶栏切换器；不传 resourceId 返回全部启用维度）
 */
export const listAuthorizedDimensions = (resourceId?: number): Promise<AssessDimensionVO[]> => {
  return request.get('/assess-dimensions/authorized', { params: { resourceId } })
}

/**
 * 创建维度
 */
export const createDimension = (data: AssessDimensionCreateDTO): Promise<number> => {
  return request.post('/assess-dimensions', data)
}

/**
 * 更新维度
 */
export const updateDimension = (data: AssessDimensionUpdateDTO): Promise<void> => {
  return request.put('/assess-dimensions', data)
}

/**
 * 删除维度
 */
export const deleteDimension = (id: number): Promise<void> => {
  return request.delete(`/assess-dimensions/${id}`)
}

/**
 * 查询维度授权列表
 */
export const listDimensionAuths = (id: number): Promise<AssessDimensionAuthVO[]> => {
  return request.get(`/assess-dimensions/${id}/auths`)
}

/**
 * 整体覆盖保存维度授权
 */
export const saveDimensionAuths = (id: number, data: DimensionAuthItemDTO[]): Promise<void> => {
  return request.put(`/assess-dimensions/${id}/auths`, data)
}

/**
 * 当前维度的组织范围（授权分部下的部门）。
 * 维度取自请求头 X-Dimension-Id，由全局拦截器自动带上，这里不传参。
 */
export const getCurrentDimensionScope = (): Promise<DimensionScopeVO> => {
  return request.get('/assess-dimensions/current-scope')
}
