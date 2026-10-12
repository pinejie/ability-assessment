package com.company.ability.service;

import com.company.ability.dto.AssessConfigDTO;
import com.company.ability.vo.AssessConfigVO;
import java.util.List;

/**
 * 能力测评配置服务
 */
public interface AssessConfigService {

    List<AssessConfigVO.AbilityWeightVO> getAbilityWeights(Long companyId);

    void saveAbilityWeights(AssessConfigDTO.AbilityWeightSaveDTO dto);

    List<AssessConfigVO.AssessorWeightVO> getAssessorWeights(Long companyId);

    void saveAssessorWeights(AssessConfigDTO.AssessorWeightSaveDTO dto);

    List<AssessConfigVO.GradeLevelVO> getGradeLevels(Long companyId);

    void saveGradeLevels(AssessConfigDTO.GradeLevelSaveDTO dto);

    List<AssessConfigVO.LevelScoreVO> getLevelScores(Long companyId);

    void saveLevelScores(AssessConfigDTO.LevelScoreSaveDTO dto);
}
