package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.PositionAbilityReqItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AbilityElementMapper;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.PositionAbilityReqItemService;
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
    private final PositionAbilityReqItemService positionAbilityReqItemService;

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
        normalizeAssessFlags(element);
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

        // 保存原始值（BeanUtils.copyProperties 会用 null 覆盖未传的字段）
        Long originalCategoryId = element.getCategoryId();
        Integer originalStatus = element.getStatus();
        Integer originalAssessInFlow = element.getAssessInFlow();

        // 如果修改了类别，检查类别是否存在
        if (dto.getCategoryId() != null) {
            AbilityCategory category = abilityCategoryService.getById(dto.getCategoryId());
            if (category == null) {
                throw new BusinessException("所属类别不存在");
            }
        }

        // 检测状态变化：从停用变为启用
        boolean isEnabling = originalStatus != null && originalStatus == 0
                && dto.getStatus() != null && dto.getStatus() == 1;

        BeanUtils.copyProperties(dto, element, "id");
        // 未传「需评价」时沿用原值（copyProperties 置 null），保证联动判定基于生效值
        if (element.getAssessInFlow() == null) {
            element.setAssessInFlow(originalAssessInFlow);
        }
        normalizeAssessFlags(element);
        updateById(element);
        log.info("更新能力要素成功，ID: {}", element.getId());

        // 启用要素时，如果所属类别处于停用状态，自动启用类别
        if (isEnabling && originalCategoryId != null) {
            AbilityCategory category = abilityCategoryService.getById(originalCategoryId);
            if (category != null && category.getStatus() != null && category.getStatus() == 0) {
                category.setStatus(1);
                abilityCategoryService.updateById(category);
                log.info("启用能力要素时自动启用所属类别，要素ID: {}, 类别ID: {}",
                        element.getId(), originalCategoryId);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteElement(Long id) {
        AbilityElement element = getById(id);
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }

        // 检查该要素是否被岗位能力要求引用
        LambdaQueryWrapper<PositionAbilityReqItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(PositionAbilityReqItem::getElementId, id);
        long itemCount = positionAbilityReqItemService.count(itemWrapper);
        if (itemCount > 0) {
            throw new BusinessException("该能力要素已被岗位能力要求引用（" + itemCount + "处），请先删除相关岗位能力要求后再删除要素");
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
    public PageResult<AbilityElementVO> pageElements(PageRequest pageRequest) {
        pageRequest.validate();
        Page<AbilityElement> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<AbilityElement> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        Page<AbilityElement> result = page(page, wrapper);
        List<AbilityElementVO> voList = result.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    @Override
    public PageResult<AbilityElementVO> pageElementsByCategoryId(Long categoryId, PageRequest pageRequest) {
        pageRequest.validate();
        Page<AbilityElement> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        Page<AbilityElement> result = baseMapper.selectPageByCategoryId(page, categoryId);
        List<AbilityElementVO> voList = result.getRecords().stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    @Override
    public List<AbilityElementVO> listElementsByCategoryId(Long categoryId, Integer status) {
        QueryWrapper<AbilityElement> wrapper = new QueryWrapper<>();
        wrapper.eq("category_id", categoryId);
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("sort").orderByAsc("id");
        List<AbilityElement> list = list(wrapper);
        return list.stream()
            .map(this::convertToVO)
            .collect(Collectors.toList());
    }

    /**
     * 评价开关联动：不需评价（assessInFlow=0）的要素不进测评页面，
     * 「需客户评价」无意义，强制归 0，避免出现 0/1 的混淆配置。
     */
    private void normalizeAssessFlags(AbilityElement element) {
        if (Integer.valueOf(0).equals(element.getAssessInFlow())) {
            element.setAllowClientAssess(0);
        }
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
