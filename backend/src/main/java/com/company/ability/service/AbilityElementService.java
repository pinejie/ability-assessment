package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.vo.AbilityElementVO;

import java.util.List;

/**
 * 能力要素服务接口
 */
public interface AbilityElementService extends IService<AbilityElement> {

    /**
     * 创建能力要素
     */
    Long createElement(AbilityElementCreateDTO dto);

    /**
     * 更新能力要素
     */
    void updateElement(AbilityElementUpdateDTO dto);

    /**
     * 删除能力要素
     */
    void deleteElement(Long id);

    /**
     * 根据ID查询能力要素
     */
    AbilityElementVO getElementById(Long id);

    /**
     * 分页查询能力要素
     */
    PageResult<AbilityElementVO> pageElements(PageRequest pageRequest);

    /**
     * 根据类别ID分页查询要素列表
     */
    PageResult<AbilityElementVO> pageElementsByCategoryId(Long categoryId, PageRequest pageRequest);

    /**
     * 根据类别ID查询要素列表（用于选择框，可过滤状态）
     */
    List<AbilityElementVO> listElementsByCategoryId(Long categoryId, Integer status);
}
