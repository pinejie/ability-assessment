package com.company.ability.controller;

import com.company.ability.dto.OrgAbilityReqCreateDTO;
import com.company.ability.dto.OrgAbilityReqUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import com.company.ability.service.OrgAbilityReqService;
import com.company.ability.vo.OrgAbilityReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门能力要求控制器
 */
@Tag(name = "部门能力要求管理", description = "部门能力要求的增删改查")
@RestController
@RequestMapping("/api/v1/org-ability-reqs")
@RequiredArgsConstructor
public class OrgAbilityReqController {

    private final OrgAbilityReqService orgAbilityReqService;

    @Operation(summary = "创建部门能力要求")
    @PostMapping
    public Result<Long> createOrgAbilityReq(@Valid @RequestBody OrgAbilityReqCreateDTO dto) {
        Long id = orgAbilityReqService.createOrgAbilityReq(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新部门能力要求")
    @PutMapping
    public Result<Void> updateOrgAbilityReq(@Valid @RequestBody OrgAbilityReqUpdateDTO dto) {
        orgAbilityReqService.updateOrgAbilityReq(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除部门能力要求")
    @DeleteMapping("/{id}")
    public Result<Void> deleteOrgAbilityReq(@PathVariable Long id) {
        orgAbilityReqService.deleteOrgAbilityReq(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询部门能力要求")
    @GetMapping("/{id}")
    public Result<OrgAbilityReqVO> getOrgAbilityReqById(@PathVariable Long id) {
        OrgAbilityReqVO vo = orgAbilityReqService.getOrgAbilityReqById(id);
        return Result.success(vo);
    }

    @Operation(summary = "分页查询所有部门能力要求")
    @GetMapping
    public Result<PageResult<OrgAbilityReqVO>> pageOrgAbilityReqs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        PageResult<OrgAbilityReqVO> result = orgAbilityReqService.pageOrgAbilityReqs(pageRequest);
        return Result.success(result);
    }

    @Operation(summary = "根据部门ID分页查询能力要求列表")
    @GetMapping("/department/{departmentId}")
    public Result<PageResult<OrgAbilityReqVO>> pageOrgAbilityReqsByDepartmentId(
            @PathVariable Long departmentId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        PageResult<OrgAbilityReqVO> result = orgAbilityReqService.pageOrgAbilityReqsByDepartmentId(departmentId, pageRequest);
        return Result.success(result);
    }
}
