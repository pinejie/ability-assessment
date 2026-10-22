import request from '@/utils/request'
import type { AssessAdminCreateDTO, AssessAdminVO } from '@/types/admin'

/**
 * 管理员名单（含配置超管合成条目）
 */
export const listAdmins = (): Promise<AssessAdminVO[]> => {
  return request.get('/assess-admins')
}

/**
 * 新增管理员（泛微在职人员）
 */
export const addAdmin = (data: AssessAdminCreateDTO): Promise<number> => {
  return request.post('/assess-admins', data)
}

/**
 * 移除管理员（配置超管与本人不可删，后端二次校验）
 */
export const removeAdmin = (id: number): Promise<void> => {
  return request.delete(`/assess-admins/${id}`)
}
