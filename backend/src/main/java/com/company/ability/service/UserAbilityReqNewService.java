package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.UserAbilityReqNewCreateDTO;
import com.company.ability.dto.UserAbilityReqNewUpdateDTO;
import com.company.ability.entity.UserAbilityReqNew;
import com.company.ability.vo.UserAbilityReqNewVO;

import java.util.List;

/**
 * 人员能力要求主表服务接口
 */
public interface UserAbilityReqNewService extends IService<UserAbilityReqNew> {

    /**
     * 创建人员能力要求（一主多从）
     */
    Long createUserAbilityReq(UserAbilityReqNewCreateDTO dto);

    /**
     * 更新人员能力要求
     */
    void updateUserAbilityReq(UserAbilityReqNewUpdateDTO dto);

    /**
     * 删除人员能力要求（同时删除明细）
     */
    void deleteUserAbilityReq(Long id);

    /**
     * 根据ID查询人员能力要求
     */
    UserAbilityReqNewVO getUserAbilityReqById(Long id);

    /**
     * 查询所有人员能力要求
     */
    List<UserAbilityReqNewVO> listAllUserAbilityReqs();

    /**
     * 根据人员ID查询能力要求
     */
    UserAbilityReqNewVO getByResourceId(Long resourceId);
}
