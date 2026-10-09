package com.company.ability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.AbilityCategoryCreateDTO;
import com.company.ability.dto.AbilityCategoryUpdateDTO;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AbilityCategoryMapper;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.vo.AbilityCategoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
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
        BeanUtils.copyProperties(dto, category, "id");
        updateById(category);
        log.info("更新能力类别成功，ID: {}", category.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id) {
        AbilityCategory category = getById(id);
        if (category == null) {
            throw new BusinessException("能力类别不存在");
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
    public List<AbilityCategoryVO> listAllCategories() {
        List<AbilityCategory> categories = list();
        return categories.stream()
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
