package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.PositionAbilityReqCreateDTO;
import com.company.ability.dto.PositionAbilityReqUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.PositionAbilityReq;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PositionAbilityReqMapper;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.PositionAbilityReqService;
import com.company.ability.vo.PositionAbilityReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 岗位能力要求服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAbilityReqServiceImpl
    extends ServiceImpl<PositionAbilityReqMapper, PositionAbilityReq>
    implements PositionAbilityReqService {

    private final AbilityElementService abilityElementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPositionAbilityReq(PositionAbilityReqCreateDTO dto) {
        // 检查能力要素是否存在
        AbilityElement element = abilityElementService.getById(dto.getElementId());
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }

        PositionAbilityReq req = new PositionAbilityReq();
        BeanUtils.copyProperties(dto, req);
        save(req);
        log.info("创建岗位能力要求成功，ID: {}, 岗位ID: {}", req.getId(), req.getJobTitleId());
        return req.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePositionAbilityReq(PositionAbilityReqUpdateDTO dto) {
        PositionAbilityReq req = getById(dto.getId());
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }

        if (dto.getElementId() != null) {
            AbilityElement element = abilityElementService.getById(dto.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在");
            }
        }

        BeanUtils.copyProperties(dto, req);
        updateById(req);
        log.info("更新岗位能力要求成功，ID: {}", req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePositionAbilityReq(Long id) {
        PositionAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }
        removeById(id);
        log.info("删除岗位能力要求成功，ID: {}", id);
    }

    @Override
    public PositionAbilityReqVO getPositionAbilityReqById(Long id) {
        PositionAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }
        return convertToVO(req);
    }

    @Override
    public List<PositionAbilityReqVO> listAllPositionAbilityReqs() {
        List<PositionAbilityReq> list = list();
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PositionAbilityReqVO> listPositionAbilityReqsByJobTitleId(Long jobTitleId) {
        LambdaQueryWrapper<PositionAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PositionAbilityReq::getJobTitleId, jobTitleId);
        List<PositionAbilityReq> list = list(wrapper);
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private PositionAbilityReqVO convertToVO(PositionAbilityReq req) {
        PositionAbilityReqVO vo = new PositionAbilityReqVO();
        BeanUtils.copyProperties(req, vo);

        // 查询岗位名称
        try {
            String jobTitleName = jdbcTemplate.queryForObject(
                "SELECT jobtitlemark FROM HrmJobTitles WHERE id = ?",
                String.class,
                req.getJobTitleId()
            );
            vo.setJobTitleName(jobTitleName);
        } catch (Exception e) {
            log.warn("查询岗位名称失败，岗位ID: {}", req.getJobTitleId());
        }

        // 查询能力要素名称
        try {
            AbilityElement element = abilityElementService.getById(req.getElementId());
            if (element != null) {
                vo.setElementName(element.getElementName());
            }
        } catch (Exception e) {
            log.warn("查询能力要素名称失败，要素ID: {}", req.getElementId());
        }

        return vo;
    }
}
