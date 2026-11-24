import request from '@/utils/request'
import type {
  PaPostVO,
  PaPostCreateDTO,
  PaPostUpdateDTO,
  PaPostQuery,
} from '@/types/paPost'
import type { PageResult } from '@/types/common'

/** 创建岗位 */
export const createPaPost = (data: PaPostCreateDTO): Promise<number> => {
  return request.post('/pa/posts', data)
}

/** 更新岗位 */
export const updatePaPost = (data: PaPostUpdateDTO): Promise<void> => {
  return request.put('/pa/posts', data)
}

/** 删除岗位（有引用时后端拒绝，提示改为停用） */
export const deletePaPost = (id: number): Promise<void> => {
  return request.delete(`/pa/posts/${id}`)
}

/** 岗位详情（含类别数/能力项数/权重合计） */
export const getPaPost = (id: number): Promise<PaPostVO> => {
  return request.get(`/pa/posts/${id}`)
}

/** 分页查询岗位 */
export const pagePaPosts = (query: PaPostQuery): Promise<PageResult<PaPostVO>> => {
  return request.get('/pa/posts', { params: query })
}

/** 岗位下拉（仅启用，可按部门过滤） */
export const listPaPosts = (departmentId?: number): Promise<PaPostVO[]> => {
  return request.get('/pa/posts/list', { params: departmentId ? { departmentId } : {} })
}

/**
 * 全公司通用岗位下拉（仅启用）。
 *
 * 单独一个接口：listPaPosts(null) 的语义是「不限部门，全都要」，
 * 混进通用语义会让别的调用方多出通用岗位。
 */
export const listGenericPaPosts = (): Promise<PaPostVO[]> => {
  return request.get('/pa/posts/list/generic')
}
