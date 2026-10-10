package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.OrgAbilityReqCreateDTO;
import com.company.ability.dto.OrgAbilityReqUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.OrgAbilityReq;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.OrgAbilityReqMapper;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.OrgAbilityReqService;
import com.company.ability.vo.OrgAbilityReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 部门能力要求服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAbilityReqServiceImpl
    extends ServiceImpl<OrgAbilityReqMapper, OrgAbilityReq>
    implements OrgAbilityReqService {

    private final AbilityElementService abilityElementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOrgAbilityReq(OrgAbilityReqCreateDTO dto) {
        // 检查能力要素是否存在
        AbilityElement element = abilityElementService.getById(dto.getElementId());
        if (element == null) {
            throw new BusinessException("能力要素不存在");
        }

        OrgAbilityReq req = new OrgAbilityReq();
        BeanUtils.copyProperties(dto, req);
        save(req);
        log.info("创建部门能力要求成功，ID: {}, 部门ID: {}", req.getId(), req.getDepartmentId());
        return req.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOrgAbilityReq(OrgAbilityReqUpdateDTO dto) {
        OrgAbilityReq req = getById(dto.getId());
        if (req == null) {
            throw new BusinessException("部门能力要求不存在");
        }

        if (dto.getElementId() != null) {
            AbilityElement element = abilityElementService.getById(dto.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在");
            }
        }

        BeanUtils.copyProperties(dto, req);
        updateById(req);
        log.info("更新部门能力要求成功，ID: {}", req.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrgAbilityReq(Long id) {
        OrgAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("部门能力要求不存在");
        }
        removeById(id);
        log.info("删除部门能力要求成功，ID: {}", id);
    }

    @Override
    public OrgAbilityReqVO getOrgAbilityReqById(Long id) {
        OrgAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("部门能力要求不存在");
        }
        return convertToVO(req);
    }

    @Override
    public PageResult<OrgAbilityReqVO> pageOrgAbilityReqs(PageRequest pageRequest) {
        pageRequest.validate();
        Page<OrgAbilityReq> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<OrgAbilityReq> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        Page<OrgAbilityReq> result = page(page, wrapper);
        List<OrgAbilityReqVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    @Override
    public PageResult<OrgAbilityReqVO> pageOrgAbilityReqsByDepartmentId(Long departmentId, PageRequest pageRequest) {
        pageRequest.validate();
        Page<OrgAbilityReq> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        LambdaQueryWrapper<OrgAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrgAbilityReq::getDepartmentId, departmentId)
               .orderByAsc(OrgAbilityReq::getId);
        Page<OrgAbilityReq> result = page(page, wrapper);
        List<OrgAbilityReqVO> voList = result.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return PageResult.from(result, voList);
    }

    private OrgAbilityReqVO convertToVO(OrgAbilityReq req) {
        OrgAbilityReqVO vo = new OrgAbilityReqVO();
        BeanUtils.copyProperties(req, vo);

        // 查询部门名称
        try {
            String deptName = jdbcTemplate.queryForObject(
                "SELECT departmentname FROM HrmDepartment WHERE id = ?",
                String.class,
                req.getDepartmentId()
            );
            vo.setDepartmentName(deptName);
        } catch (Exception e) {
            log.warn("查询部门名称失败，部门ID: {}", req.getDepartmentId());
        }

        // 查询能力要素名称
        try {
            AbilityElement element = abilityElementService.getById(req.getElementId());
            if (element != null) {
                vo.setElementName(element.getElementName());
            }
        } catch (Exception e) {
            log.warn("查询能力要素名称失败，要素ID: {}", req.getElementId());
        }

        return vo;
    }
}
