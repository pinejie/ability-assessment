package com.company.ability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.entity.AbilityElement;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AbilityElementMapper;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.vo.AbilityElementVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 能力要素服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbilityElementServiceImpl
    extends ServiceImpl<AbilityElementMapper, AbilityElement>
    implements AbilityElementService {

    private final AbilityCategoryService abilityCategoryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createElement(AbilityElementCreateDTO dto) {
        // 检查类别是否存在
        AbilityCategory category = abilityCategoryService.getById(dto.getCategoryId());
        if (category == null) {
            throw new BusinessException("所属类别不存在");
        }

        AbilityElement element = new AbilityElement();
        BeanUtils.copyProperties(dto, element);
        save(element);
        log.info("创建能力要素成功，ID: {}, 名称: {}", element.getId(), element.getElementName());
        return element.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateElement(AbilityElementUpdateDTO dto) {
        AbilityElement element = getById(dto.getId());
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }

        // 如果修改了类别，检查类别是否存在
        if (dto.getCategoryId() != null) {
            AbilityCategory category = abilityCategoryService.getById(dto.getCategoryId());
            if (category == null) {
                throw new BusinessException("所属类别不存在");
            }
        }

        BeanUtils.copyProperties(dto, element, "id");
        updateById(element);
        log.info("更新能力要素成功，ID: {}", element.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteElement(Long id) {
        AbilityElement element = getById(id);
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }
        removeById(id);
        log.info("删除能力要素成功，ID: {}", id);
    }

    @Override
    public AbilityElementVO getElementById(Long id) {
        AbilityElement element = getById(id);
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }
        return convertToVO(element);
    }

    @Override
    public List<AbilityElementVO> listAllElements() {
        List<AbilityElement> elements = list();
        return elements.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }

    @Override
    public List<AbilityElementVO> listElementsByCategoryId(Long categoryId) {
        List<AbilityElement> elements = baseMapper.selectByCategoryId(categoryId);
        return elements.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }

    /**
     * 转换为 VO
     */
    private AbilityElementVO convertToVO(AbilityElement element) {
        AbilityElementVO vo = new AbilityElementVO();
        BeanUtils.copyProperties(element, vo);

        // 获取类别名称
        AbilityCategory category = abilityCategoryService.getById(element.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getCategoryName());
        }

        return vo;
    }
}
