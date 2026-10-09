package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.UserAbilityReqCreateDTO;
import com.company.ability.dto.UserAbilityReqUpdateDTO;
import com.company.ability.entity.UserAbilityReq;
import com.company.ability.vo.UserAbilityReqVO;

import java.util.List;

/**
 * 人员能力要求服务接口
 */
public interface UserAbilityReqService extends IService<UserAbilityReq> {

    /**
     * 创建人员能力要求（一对多）
     */
    Long createUserAbilityReq(UserAbilityReqCreateDTO dto);

    /**
     * 更新人员能力要求
     */
    void updateUserAbilityReq(UserAbilityReqUpdateDTO dto);

    /**
     * 删除人员能力要求
     */
    void deleteUserAbilityReq(Long id);

    /**
     * 根据人员ID删除所有能力要求
     */
    void deleteUserAbilityReqsByResourceId(Long resourceId);

    /**
     * 根据ID查询人员能力要求
     */
    UserAbilityReqVO getUserAbilityReqById(Long id);

    /**
     * 查询所有人员能力要求
     */
    List<UserAbilityReqVO> listAllUserAbilityReqs();

    /**
     * 根据人员ID查询能力要求列表
     */
    List<UserAbilityReqVO> listUserAbilityReqsByResourceId(Long resourceId);
}
