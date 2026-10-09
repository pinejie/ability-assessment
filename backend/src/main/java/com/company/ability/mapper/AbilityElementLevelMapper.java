package com.company.ability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.ability.entity.AbilityElementLevel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 能力要素等级 Mapper
 */
@Mapper
public interface AbilityElementLevelMapper extends BaseMapper<AbilityElementLevel> {

    /**
     * 根据能力要素ID查询等级列表
     */
    List<AbilityElementLevel> selectByElementId(@Param("elementId") Long elementId);
}
