package com.company.ability.controller;

import com.company.ability.dto.PositionAbilityReqCreateDTO;
import com.company.ability.dto.PositionAbilityReqUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PositionAbilityReqService;
import com.company.ability.vo.PositionAbilityReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 岗位能力要求控制器
 */
@Tag(name = "岗位能力要求管理", description = "岗位能力要求的增删改查")
@RestController
@RequestMapping("/api/v1/position-ability-reqs")
@RequiredArgsConstructor
public class PositionAbilityReqController {

    private final PositionAbilityReqService positionAbilityReqService;

    @Operation(summary = "创建岗位能力要求")
    @PostMapping
    public Result<Long> createPositionAbilityReq(@Valid @RequestBody PositionAbilityReqCreateDTO dto) {
        Long id = positionAbilityReqService.createPositionAbilityReq(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新岗位能力要求")
    @PutMapping
    public Result<Void> updatePositionAbilityReq(@Valid @RequestBody PositionAbilityReqUpdateDTO dto) {
        positionAbilityReqService.updatePositionAbilityReq(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除岗位能力要求")
    @DeleteMapping("/{id}")
    public Result<Void> deletePositionAbilityReq(@PathVariable Long id) {
        positionAbilityReqService.deletePositionAbilityReq(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询岗位能力要求")
    @GetMapping("/{id}")
    public Result<PositionAbilityReqVO> getPositionAbilityReqById(@PathVariable Long id) {
        PositionAbilityReqVO vo = positionAbilityReqService.getPositionAbilityReqById(id);
        return Result.success(vo);
    }

    @Operation(summary = "查询所有岗位能力要求")
    @GetMapping
    public Result<List<PositionAbilityReqVO>> listAllPositionAbilityReqs() {
        List<PositionAbilityReqVO> list = positionAbilityReqService.listAllPositionAbilityReqs();
        return Result.success(list);
    }

    @Operation(summary = "根据岗位ID查询能力要求列表")
    @GetMapping("/job-title/{jobTitleId}")
    public Result<List<PositionAbilityReqVO>> listPositionAbilityReqsByJobTitleId(@PathVariable Long jobTitleId) {
        List<PositionAbilityReqVO> list = positionAbilityReqService.listPositionAbilityReqsByJobTitleId(jobTitleId);
        return Result.success(list);
    }

    @Operation(summary = "根据部门和岗位查询能力要求配置")
    @GetMapping("/by-department-jobtitle")
    public Result<PositionAbilityReqVO> getPositionAbilityReqByDeptAndJobTitle(
            @RequestParam Long departmentId,
            @RequestParam Long jobTitleId) {
        PositionAbilityReqVO vo = positionAbilityReqService.getByDepartmentAndJobTitle(departmentId, jobTitleId);
        return Result.success(vo);
    }
}
