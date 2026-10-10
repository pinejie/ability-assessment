package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.OrgAbilityReqCreateDTO;
import com.company.ability.dto.OrgAbilityReqUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.OrgAbilityReq;
import com.company.ability.vo.OrgAbilityReqVO;

import java.util.List;

/**
 * 部门能力要求服务接口
 */
public interface OrgAbilityReqService extends IService<OrgAbilityReq> {

    /**
     * 创建部门能力要求
     */
    Long createOrgAbilityReq(OrgAbilityReqCreateDTO dto);

    /**
     * 更新部门能力要求
     */
    void updateOrgAbilityReq(OrgAbilityReqUpdateDTO dto);

    /**
     * 删除部门能力要求
     */
    void deleteOrgAbilityReq(Long id);

    /**
     * 根据ID查询部门能力要求
     */
    OrgAbilityReqVO getOrgAbilityReqById(Long id);

    /**
     * 分页查询所有部门能力要求
     */
    PageResult<OrgAbilityReqVO> pageOrgAbilityReqs(PageRequest pageRequest);

    /**
     * 根据部门ID分页查询能力要求列表
     */
    PageResult<OrgAbilityReqVO> pageOrgAbilityReqsByDepartmentId(Long departmentId, PageRequest pageRequest);
}
