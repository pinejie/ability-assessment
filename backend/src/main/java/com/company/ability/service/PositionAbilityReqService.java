package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PositionAbilityReqCreateDTO;
import com.company.ability.dto.PositionAbilityReqUpdateDTO;
import com.company.ability.entity.PositionAbilityReq;
import com.company.ability.vo.PositionAbilityReqVO;

import java.util.List;

/**
 * 岗位能力要求服务接口
 */
public interface PositionAbilityReqService extends IService<PositionAbilityReq> {

    /**
     * 创建岗位能力要求
     */
    Long createPositionAbilityReq(PositionAbilityReqCreateDTO dto);

    /**
     * 更新岗位能力要求
     */
    void updatePositionAbilityReq(PositionAbilityReqUpdateDTO dto);

    /**
     * 删除岗位能力要求
     */
    void deletePositionAbilityReq(Long id);

    /**
     * 根据ID查询岗位能力要求
     */
    PositionAbilityReqVO getPositionAbilityReqById(Long id);

    /**
     * 分页查询所有岗位能力要求
     */
    PageResult<PositionAbilityReqVO> pagePositionAbilityReqs(PageRequest pageRequest);

    /**
     * 根据岗位ID分页查询能力要求列表
     */
    PageResult<PositionAbilityReqVO> pagePositionAbilityReqsByJobTitleId(Long jobTitleId, PageRequest pageRequest);

    /**
     * 根据部门和岗位查询能力要求配置
     */
    PositionAbilityReqVO getByDepartmentAndJobTitle(Long departmentId, Long jobTitleId);
}
