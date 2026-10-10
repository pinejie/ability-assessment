package com.company.ability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.entity.UserAbilityReqItem;
import com.company.ability.mapper.UserAbilityReqItemMapper;
import com.company.ability.service.UserAbilityReqItemService;
import org.springframework.stereotype.Service;

/**
 * 人员能力要求明细表服务实现
 */
@Service
public class UserAbilityReqItemServiceImpl
    extends ServiceImpl<UserAbilityReqItemMapper, UserAbilityReqItem>
    implements UserAbilityReqItemService {
}
