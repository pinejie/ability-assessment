package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.entity.AbilityElement;
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
     * 查询所有能力要素
     */
    List<AbilityElementVO> listAllElements();

    /**
     * 根据类别ID查询要素列表
     */
    List<AbilityElementVO> listElementsByCategoryId(Long categoryId);
}
