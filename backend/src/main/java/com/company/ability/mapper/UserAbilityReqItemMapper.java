package com.company.ability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.ability.entity.UserAbilityReqItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人员能力要求明细表 Mapper
 */
@Mapper
public interface UserAbilityReqItemMapper extends BaseMapper<UserAbilityReqItem> {
}
