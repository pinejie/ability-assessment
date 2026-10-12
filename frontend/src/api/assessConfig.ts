import request from '@/utils/request'
import type {
  AbilityWeightVO,
  AbilityWeightItem,
  AssessorWeightVO,
  AssessorWeightItem,
  GradeLevelVO,
  GradeLevelItem,
  LevelScoreVO,
  LevelScoreItem,
} from '@/types/assessConfig'

/** 查询能力类型权重 */
export const getAbilityWeights = (companyId: number): Promise<AbilityWeightVO[]> =>
  request.get('/assess-config/ability-weights', { params: { companyId } })

/** 保存能力类型权重（整体保存） */
export const saveAbilityWeights = (companyId: number, items: AbilityWeightItem[]): Promise<void> =>
  request.put('/assess-config/ability-weights', { companyId, items })

/** 查询测评对象权重 */
export const getAssessorWeights = (companyId: number): Promise<AssessorWeightVO[]> =>
  request.get('/assess-config/assessor-weights', { params: { companyId } })

/** 保存测评对象权重（整体保存） */
export const saveAssessorWeights = (companyId: number, items: AssessorWeightItem[]): Promise<void> =>
  request.put('/assess-config/assessor-weights', { companyId, items })

/** 查询等级标准 */
export const getGradeLevels = (companyId: number): Promise<GradeLevelVO[]> =>
  request.get('/assess-config/grade-levels', { params: { companyId } })

/** 保存等级标准（整体保存） */
export const saveGradeLevels = (companyId: number, items: GradeLevelItem[]): Promise<void> =>
  request.put('/assess-config/grade-levels', { companyId, items })

/** 查询等级分值 */
export const getLevelScores = (companyId: number): Promise<LevelScoreVO[]> =>
  request.get('/assess-config/level-scores', { params: { companyId } })

/** 保存等级分值（整体保存） */
export const saveLevelScores = (companyId: number, items: LevelScoreItem[]): Promise<void> =>
  request.put('/assess-config/level-scores', { companyId, items })
