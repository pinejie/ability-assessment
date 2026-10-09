import request from '@/utils/request'
import type { ScoreWeightVO, ScoreWeightCreateDTO, ScoreWeightUpdateDTO } from '@/types/scoreWeight'

/**
 * 创建评分权重配置
 */
export const createScoreWeight = (data: ScoreWeightCreateDTO): Promise<number> => {
  return request.post('/score-weights', data)
}

/**
 * 更新评分权重配置
 */
export const updateScoreWeight = (data: ScoreWeightUpdateDTO): Promise<void> => {
  return request.put('/score-weights', data)
}

/**
 * 删除评分权重配置
 */
export const deleteScoreWeight = (id: number): Promise<void> => {
  return request.delete(`/score-weights/${id}`)
}

/**
 * 根据ID查询评分权重配置
 */
export const getScoreWeightById = (id: number): Promise<ScoreWeightVO> => {
  return request.get(`/score-weights/${id}`)
}

/**
 * 查询所有评分权重配置
 */
export const listScoreWeights = (): Promise<ScoreWeightVO[]> => {
  return request.get('/score-weights')
}

/**
 * 根据分公司ID查询权重配置列表
 */
export const listScoreWeightsByCompanyId = (companyId: number): Promise<ScoreWeightVO[]> => {
  return request.get(`/score-weights/company/${companyId}`)
}
