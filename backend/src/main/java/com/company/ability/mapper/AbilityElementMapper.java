package com.company.ability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.ability.entity.AbilityElement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 能力要素 Mapper
 */
@Mapper
public interface AbilityElementMapper extends BaseMapper<AbilityElement> {

    /**
     * 根据类别ID查询要素列表（包含类别名称）
     */
    @Select("SELECT e.*, c.category_name " +
            "FROM uf_ability_element e " +
            "LEFT JOIN uf_ability_category c ON e.category_id = c.id " +
            "WHERE e.category_id = #{categoryId} AND e.deleted = 0 " +
            "ORDER BY e.sort ASC")
    List<AbilityElement> selectByCategoryId(@Param("categoryId") Long categoryId);
}
