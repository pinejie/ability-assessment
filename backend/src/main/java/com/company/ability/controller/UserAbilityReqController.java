package com.company.ability.controller;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.UserAbilityReqCreateDTO;
import com.company.ability.dto.UserAbilityReqUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.UserAbilityReqService;
import com.company.ability.vo.UserAbilityReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人员能力要求控制器
 */
@Tag(name = "人员能力要求管理", description = "人员能力要求的增删改查")
@RestController
@RequestMapping("/api/v1/user-ability-reqs")
@RequiredArgsConstructor
public class UserAbilityReqController {

    private final UserAbilityReqService userAbilityReqService;

    @Operation(summary = "创建人员能力要求（一对多）")
    @PostMapping
    public Result<Long> createUserAbilityReq(@Valid @RequestBody UserAbilityReqCreateDTO dto) {
        Long id = userAbilityReqService.createUserAbilityReq(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新人员能力要求")
    @PutMapping
    public Result<Void> updateUserAbilityReq(@Valid @RequestBody UserAbilityReqUpdateDTO dto) {
        userAbilityReqService.updateUserAbilityReq(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除人员能力要求")
    @DeleteMapping("/{id}")
    public Result<Void> deleteUserAbilityReq(@PathVariable Long id) {
        userAbilityReqService.deleteUserAbilityReq(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据人员ID删除所有能力要求")
    @DeleteMapping("/resource/{resourceId}")
    public Result<Void> deleteUserAbilityReqsByResourceId(@PathVariable Long resourceId) {
        userAbilityReqService.deleteUserAbilityReqsByResourceId(resourceId);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询人员能力要求")
    @GetMapping("/{id}")
    public Result<UserAbilityReqVO> getUserAbilityReqById(@PathVariable Long id) {
        UserAbilityReqVO vo = userAbilityReqService.getUserAbilityReqById(id);
        return Result.success(vo);
    }

    @Operation(summary = "分页查询人员能力要求")
    @GetMapping
    public Result<PageResult<UserAbilityReqVO>> pageUserAbilityReqs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        return Result.success(userAbilityReqService.pageUserAbilityReqs(pageRequest));
    }

    @Operation(summary = "根据人员ID分页查询能力要求列表")
    @GetMapping("/resource/{resourceId}")
    public Result<PageResult<UserAbilityReqVO>> pageUserAbilityReqsByResourceId(
            @PathVariable Long resourceId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        return Result.success(userAbilityReqService.pageUserAbilityReqsByResourceId(resourceId, pageRequest));
    }
}
