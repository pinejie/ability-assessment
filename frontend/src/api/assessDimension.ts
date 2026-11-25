import request from '@/utils/request'
import type {
  AssessDimensionVO,
  AssessDimensionCreateDTO,
  AssessDimensionUpdateDTO,
  AssessDimensionAuthVO,
  DimensionAuthItemDTO,
  DimensionScopeVO,
  DimensionIntegrationVO,
  DimensionIntegrationSaveDTO,
  IntegrationOptions,
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

// ==================== 集成配置（单点登录 + 消息推送，仅超管） ====================

/**
 * 读取某维度的集成配置。未配置的维度返回默认值空壳，不会 404。
 */
export const getDimensionIntegration = (id: number): Promise<DimensionIntegrationVO> => {
  return request.get(`/assess-dimensions/${id}/integration`)
}

/**
 * 保存某维度的集成配置。
 * 密钥字段传 SECRET_MASK 表示不修改，传空串表示清空，传新值表示覆盖。
 */
export const saveDimensionIntegration = (
  id: number,
  data: DimensionIntegrationSaveDTO
): Promise<void> => {
  return request.put(`/assess-dimensions/${id}/integration`, data)
}

/**
 * 集成配置的可选项（协议类型 / 匹配字段 / 推送渠道）
 */
export const getIntegrationOptions = (): Promise<IntegrationOptions> => {
  return request.get('/assess-dimensions/integration-options')
}

/**
 * 按需取一次已保存密钥的明文（仅超管）。
 *
 * <p>单独一个接口是为了让「打开配置页」不产生明文流量 —— 只有点了「查看」才取。
 * 服务端每次调用都会记一条日志（谁、哪个维度、哪个字段，不记值）。</p>
 */
export const revealDimensionSecret = (
  id: number,
  field: 'sso_client_secret' | 'push_secret'
): Promise<string> => {
  return request.post(`/assess-dimensions/${id}/integration/secret`, { field })
}
