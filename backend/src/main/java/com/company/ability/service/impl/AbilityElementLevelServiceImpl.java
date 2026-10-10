package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.AbilityElementLevelCreateDTO;
import com.company.ability.dto.AbilityElementLevelUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AbilityElementLevelMapper;
import com.company.ability.service.AbilityElementLevelService;
import com.company.ability.vo.AbilityElementLevelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 能力要素等级服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbilityElementLevelServiceImpl extends ServiceImpl<AbilityElementLevelMapper, AbilityElementLevel>
        implements AbilityElementLevelService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createLevel(AbilityElementLevelCreateDTO dto) {
        // 检查是否已存在相同等级
        LambdaQueryWrapper<AbilityElementLevel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AbilityElementLevel::getElementId, dto.getElementId())
               .eq(AbilityElementLevel::getLevel, dto.getLevel());
        if (this.count(wrapper) > 0) {
            throw new BusinessException("该能力要素已存在等级" + dto.getLevel() + "的配置");
        }

        AbilityElementLevel level = new AbilityElementLevel();
        BeanUtils.copyProperties(dto, level);
        this.save(level);
        log.info("创建能力要素等级配置成功，ID: {}, 能力要素ID: {}, 等级: {}",
                level.getId(), level.getElementId(), level.getLevel());
        return level.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateLevel(AbilityElementLevelUpdateDTO dto) {
        AbilityElementLevel level = this.getById(dto.getId());
        if (level == null) {
            throw new BusinessException("等级配置不存在");
        }
        BeanUtils.copyProperties(dto, level);
        this.updateById(level);
        log.info("更新能力要素等级配置成功，ID: {}", level.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLevel(Long id) {
        AbilityElementLevel level = this.getById(id);
        if (level == null) {
            throw new BusinessException("等级配置不存在");
        }
        this.removeById(id);
        log.info("删除能力要素等级配置成功，ID: {}", id);
    }

    @Override
    public AbilityElementLevelVO getLevelById(Long id) {
        AbilityElementLevel level = this.getById(id);
        if (level == null) {
            throw new BusinessException("等级配置不存在");
        }
        AbilityElementLevelVO vo = new AbilityElementLevelVO();
        BeanUtils.copyProperties(level, vo);
        return vo;
    }

    @Override
    public PageResult<AbilityElementLevelVO> pageLevelsByElementId(Long elementId, PageRequest pageRequest) {
        pageRequest.validate();
        Page<AbilityElementLevel> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        LambdaQueryWrapper<AbilityElementLevel> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AbilityElementLevel::getElementId, elementId)
               .orderByAsc(AbilityElementLevel::getLevel);
        Page<AbilityElementLevel> result = this.page(page, wrapper);
        List<AbilityElementLevelVO> voList = result.getRecords().stream()
                .map(level -> {
                    AbilityElementLevelVO vo = new AbilityElementLevelVO();
                    BeanUtils.copyProperties(level, vo);
                    return vo;
                })
                .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }
}
