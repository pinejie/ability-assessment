package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.UserAbilityReqCreateDTO;
import com.company.ability.dto.UserAbilityReqUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.entity.UserAbilityReq;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.UserAbilityReqMapper;
import com.company.ability.service.AbilityElementLevelService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.UserAbilityReqService;
import com.company.ability.vo.UserAbilityReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 人员能力要求服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAbilityReqServiceImpl
    extends ServiceImpl<UserAbilityReqMapper, UserAbilityReq>
    implements UserAbilityReqService {

    private final AbilityElementService abilityElementService;
    private final AbilityElementLevelService abilityElementLevelService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUserAbilityReq(UserAbilityReqCreateDTO dto) {
        List<UserAbilityReq> reqs = new ArrayList<>();

        // 为每个能力要素配置创建一条记录
        for (UserAbilityReqCreateDTO.UserAbilityElementConfig config : dto.getElementConfigs()) {
            // 检查能力要素是否存在
            AbilityElement element = abilityElementService.getById(config.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在，ID: " + config.getElementId());
            }

            // 检查等级配置是否存在
            AbilityElementLevel level = abilityElementLevelService.getById(config.getLevelId());
            if (level == null) {
                throw new BusinessException("等级配置不存在，ID: " + config.getLevelId());
            }

            UserAbilityReq req = new UserAbilityReq();
            req.setResourceId(dto.getResourceId());
            req.setElementId(config.getElementId());
            req.setLevelId(config.getLevelId());
            req.setScore(config.getScore());
            req.setDescription(config.getDescription());
            reqs.add(req);
        }

        // 逐条保存（避免 SQL Server 批量插入获取自增ID的问题）
        for (UserAbilityReq req : reqs) {
            save(req);
        }
        log.info("创建人员能力要求成功，人员ID: {}, 创建{}条记录", dto.getResourceId(), reqs.size());
        return reqs.get(0).getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserAbilityReq(UserAbilityReqUpdateDTO dto) {
        UserAbilityReq req = getById(dto.getId());
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }

        if (dto.getElementId() != null) {
            AbilityElement element = abilityElementService.getById(dto.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在");
            }
        }

        if (dto.getLevelId() != null) {
            AbilityElementLevel level = abilityElementLevelService.getById(dto.getLevelId());
            if (level == null) {
                throw new BusinessException("等级配置不存在");
            }
        }

        BeanUtils.copyProperties(dto, req);
        updateById(req);
        log.info("更新人员能力要求成功，ID: {}", req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserAbilityReq(Long id) {
        UserAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }
        removeById(id);
        log.info("删除人员能力要求成功，ID: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserAbilityReqsByResourceId(Long resourceId) {
        LambdaQueryWrapper<UserAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAbilityReq::getResourceId, resourceId);
        remove(wrapper);
        log.info("删除人员的所有能力要求，人员ID: {}", resourceId);
    }

    @Override
    public UserAbilityReqVO getUserAbilityReqById(Long id) {
        UserAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }
        return convertToVO(req);
    }

    @Override
    public List<UserAbilityReqVO> listAllUserAbilityReqs() {
        List<UserAbilityReq> list = list();
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserAbilityReqVO> listUserAbilityReqsByResourceId(Long resourceId) {
        LambdaQueryWrapper<UserAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAbilityReq::getResourceId, resourceId);
        List<UserAbilityReq> list = list(wrapper);
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private UserAbilityReqVO convertToVO(UserAbilityReq req) {
        UserAbilityReqVO vo = new UserAbilityReqVO();
        BeanUtils.copyProperties(req, vo);

        // 查询人员姓名
        try {
            String lastName = jdbcTemplate.queryForObject(
                "SELECT lastname FROM HrmResource WHERE id = ?",
                String.class,
                req.getResourceId()
            );
            vo.setResourceLastName(lastName);
        } catch (Exception e) {
            log.warn("查询人员姓名失败，人员ID: {}", req.getResourceId());
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

        // 查询等级信息
        try {
            AbilityElementLevel level = abilityElementLevelService.getById(req.getLevelId());
            if (level != null) {
                vo.setLevel(level.getLevel());
                vo.setLevelName(level.getLevelName());
                vo.setLevelRequirement(level.getLevelRequirement());
            }
        } catch (Exception e) {
            log.warn("查询等级信息失败，等级ID: {}", req.getLevelId());
        }

        return vo;
    }
}
