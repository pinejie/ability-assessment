package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.ScoreWeightCreateDTO;
import com.company.ability.dto.ScoreWeightUpdateDTO;
import com.company.ability.entity.ScoreWeight;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.ScoreWeightMapper;
import com.company.ability.service.ScoreWeightService;
import com.company.ability.vo.ScoreWeightVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 评分权重配置服务实现
 */
@Slf4j
@Service
public class ScoreWeightServiceImpl
    extends ServiceImpl<ScoreWeightMapper, ScoreWeight>
    implements ScoreWeightService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createScoreWeight(ScoreWeightCreateDTO dto) {
        // 验证权重总和
        validateWeightSum(dto.getCategoryWeight(), dto.getElementWeight());

        ScoreWeight weight = new ScoreWeight();
        BeanUtils.copyProperties(dto, weight);
        save(weight);
        log.info("创建评分权重配置成功，ID: {}, 名称: {}", weight.getId(), weight.getWeightName());
        return weight.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateScoreWeight(ScoreWeightUpdateDTO dto) {
        ScoreWeight weight = getById(dto.getId());
        if (weight == null) {
            throw new BusinessException("评分权重配置不存在");
        }

        // 如果修改了权重，验证总和
        if (dto.getCategoryWeight() != null && dto.getElementWeight() != null) {
            validateWeightSum(dto.getCategoryWeight(), dto.getElementWeight());
        }

        BeanUtils.copyProperties(dto, weight, "id");
        updateById(weight);
        log.info("更新评分权重配置成功，ID: {}", weight.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteScoreWeight(Long id) {
        ScoreWeight weight = getById(id);
        if (weight == null) {
            throw new BusinessException("评分权重配置不存在");
        }
        removeById(id);
        log.info("删除评分权重配置成功，ID: {}", id);
    }

    @Override
    public ScoreWeightVO getScoreWeightById(Long id) {
        ScoreWeight weight = getById(id);
        if (weight == null) {
            throw new BusinessException("评分权重配置不存在");
        }
        return convertToVO(weight);
    }

    @Override
    public PageResult<ScoreWeightVO> pageScoreWeights(PageRequest pageRequest) {
        pageRequest.validate();
        Page<ScoreWeight> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<ScoreWeight> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        Page<ScoreWeight> result = page(page, wrapper);
        List<ScoreWeightVO> voList = result.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    @Override
    public PageResult<ScoreWeightVO> pageScoreWeightsByCompanyId(Long companyId, PageRequest pageRequest) {
        pageRequest.validate();
        Page<ScoreWeight> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        Page<ScoreWeight> result = lambdaQuery()
            .eq(ScoreWeight::getCompanyId, companyId)
            .orderByAsc(ScoreWeight::getId)
            .page(page);
        List<ScoreWeightVO> voList = result.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    /**
     * 验证权重总和
     */
    private void validateWeightSum(BigDecimal categoryWeight, BigDecimal elementWeight) {
        BigDecimal sum = categoryWeight.add(elementWeight);
        if (sum.compareTo(new BigDecimal("100")) != 0) {
            throw new BusinessException("类别权重和要素权重之和必须等于100%");
        }
    }

    /**
     * 转换为 VO
     */
    private ScoreWeightVO convertToVO(ScoreWeight weight) {
        ScoreWeightVO vo = new ScoreWeightVO();
        BeanUtils.copyProperties(weight, vo);

        // TODO: 获取分公司名称（从泛微视图）
        vo.setCompanyName("分公司" + weight.getCompanyId());

        return vo;
    }
}
