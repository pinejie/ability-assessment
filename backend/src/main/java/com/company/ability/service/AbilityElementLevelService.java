package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.AbilityElementLevelCreateDTO;
import com.company.ability.dto.AbilityElementLevelUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.vo.AbilityElementLevelVO;

/**
 * 能力要素等级服务
 */
public interface AbilityElementLevelService extends IService<AbilityElementLevel> {

    /**
     * 创建等级配置
     */
    Long createLevel(AbilityElementLevelCreateDTO dto);

    /**
     * 更新等级配置
     */
    void updateLevel(AbilityElementLevelUpdateDTO dto);

    /**
     * 删除等级配置
     */
    void deleteLevel(Long id);

    /**
     * 根据ID查询等级配置
     */
    AbilityElementLevelVO getLevelById(Long id);

    /**
     * 根据能力要素ID分页查询等级列表
     */
    PageResult<AbilityElementLevelVO> pageLevelsByElementId(Long elementId, PageRequest pageRequest);
}
