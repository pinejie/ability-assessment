package com.company.ability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.entity.PositionAbilityReqItem;
import com.company.ability.mapper.PositionAbilityReqItemMapper;
import com.company.ability.service.PositionAbilityReqItemService;
import org.springframework.stereotype.Service;

@Service
public class PositionAbilityReqItemServiceImpl extends ServiceImpl<PositionAbilityReqItemMapper, PositionAbilityReqItem>
    implements PositionAbilityReqItemService {
}
