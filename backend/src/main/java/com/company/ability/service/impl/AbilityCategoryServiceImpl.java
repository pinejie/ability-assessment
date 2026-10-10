package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.AbilityCategoryCreateDTO;
import com.company.ability.dto.AbilityCategoryUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.entity.AbilityElement;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AbilityCategoryMapper;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.vo.AbilityCategoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 能力类别服务实现
 */
@Slf4j
@Service
public class AbilityCategoryServiceImpl
    extends ServiceImpl<AbilityCategoryMapper, AbilityCategory>
    implements AbilityCategoryService {

    @Autowired
    @Lazy
    private AbilityElementService abilityElementService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(AbilityCategoryCreateDTO dto) {
        AbilityCategory category = new AbilityCategory();
        BeanUtils.copyProperties(dto, category);
        save(category);
        log.info("创建能力类别成功，ID: {}, 名称: {}", category.getId(), category.getCategoryName());
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(AbilityCategoryUpdateDTO dto) {
        AbilityCategory category = getById(dto.getId());
        if (category == null) {
            throw new BusinessException("能力类别不存在");
        }

        // 检测状态变化：从启用变为停用
        boolean isDisabling = category.getStatus() != null && category.getStatus() == 1
                && dto.getStatus() != null && dto.getStatus() == 0;

        BeanUtils.copyProperties(dto, category, "id");
        updateById(category);
        log.info("更新能力类别成功，ID: {}", category.getId());

        // 如果是停用操作，级联停用该类别下所有能力要素
        if (isDisabling) {
            LambdaUpdateWrapper<AbilityElement> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(AbilityElement::getCategoryId, dto.getId())
                   .set(AbilityElement::getStatus, 0);
            abilityElementService.update(wrapper);
            log.info("停用能力类别及其下所有能力要素，类别ID: {}", dto.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        AbilityCategory category = getById(id);
        if (category == null) {
            throw new BusinessException("能力类别不存在");
        }

        // 检查该类别下是否还有能力要素
        LambdaQueryWrapper<AbilityElement> elementWrapper = new LambdaQueryWrapper<>();
        elementWrapper.eq(AbilityElement::getCategoryId, id);
        long elementCount = abilityElementService.count(elementWrapper);
        if (elementCount > 0) {
            throw new BusinessException("该类别下存在" + elementCount + "个能力要素，请先删除能力要素后再删除类别");
        }

        removeById(id);
        log.info("删除能力类别成功，ID: {}", id);
    }

    @Override
    public AbilityCategoryVO getCategoryById(Long id) {
        AbilityCategory category = getById(id);
        if (category == null) {
            throw new BusinessException("能力类别不存在");
        }
        return convertToVO(category);
    }

    @Override
    public PageResult<AbilityCategoryVO> pageCategories(PageRequest pageRequest) {
        pageRequest.validate();
        Page<AbilityCategory> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<AbilityCategory> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        Page<AbilityCategory> result = page(page, wrapper);
        List<AbilityCategoryVO> voList = result.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    @Override
    public List<AbilityCategoryVO> listCategories(Integer status) {
        QueryWrapper<AbilityCategory> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("sort").orderByAsc("id");
        List<AbilityCategory> list = list(wrapper);
        return list.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }

    /**
     * 转换为 VO
     */
    private AbilityCategoryVO convertToVO(AbilityCategory category) {
        AbilityCategoryVO vo = new AbilityCategoryVO();
        BeanUtils.copyProperties(category, vo);
        return vo;
    }
}
