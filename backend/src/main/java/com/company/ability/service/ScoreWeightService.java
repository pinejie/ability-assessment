package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.ScoreWeightCreateDTO;
import com.company.ability.dto.ScoreWeightUpdateDTO;
import com.company.ability.entity.ScoreWeight;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.vo.ScoreWeightVO;

import java.util.List;

/**
 * 评分权重配置服务接口
 */
public interface ScoreWeightService extends IService<ScoreWeight> {

    /**
     * 创建评分权重配置
     */
    Long createScoreWeight(ScoreWeightCreateDTO dto);

    /**
     * 更新评分权重配置
     */
    void updateScoreWeight(ScoreWeightUpdateDTO dto);

    /**
     * 删除评分权重配置
     */
    void deleteScoreWeight(Long id);

    /**
     * 根据ID查询评分权重配置
     */
    ScoreWeightVO getScoreWeightById(Long id);

    /**
     * 分页查询评分权重配置
     */
    PageResult<ScoreWeightVO> pageScoreWeights(PageRequest pageRequest);

    /**
     * 根据分公司ID分页查询权重配置列表
     */
    PageResult<ScoreWeightVO> pageScoreWeightsByCompanyId(Long companyId, PageRequest pageRequest);
}
