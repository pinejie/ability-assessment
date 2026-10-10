package com.company.ability.controller;

import com.company.ability.dto.UserAbilityReqNewCreateDTO;
import com.company.ability.dto.UserAbilityReqNewUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.UserAbilityReqNewService;
import com.company.ability.vo.UserAbilityReqNewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 人员能力要求控制器（新版一主多从）
 */
@Tag(name = "人员能力要求管理（新版）", description = "人员能力要求的增删改查（一主多从）")
@RestController
@RequestMapping("/api/v1/user-ability-reqs-new")
@RequiredArgsConstructor
public class UserAbilityReqNewController {

    private final UserAbilityReqNewService userAbilityReqNewService;

    @Operation(summary = "创建人员能力要求（一主多从）")
    @PostMapping
    public Result<Long> createUserAbilityReq(@Valid @RequestBody UserAbilityReqNewCreateDTO dto) {
        Long id = userAbilityReqNewService.createUserAbilityReq(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新人员能力要求")
    @PutMapping
    public Result<Void> updateUserAbilityReq(@Valid @RequestBody UserAbilityReqNewUpdateDTO dto) {
        userAbilityReqNewService.updateUserAbilityReq(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除人员能力要求")
    @DeleteMapping("/{id}")
    public Result<Void> deleteUserAbilityReq(@PathVariable Long id) {
        userAbilityReqNewService.deleteUserAbilityReq(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询人员能力要求")
    @GetMapping("/{id}")
    public Result<UserAbilityReqNewVO> getUserAbilityReqById(@PathVariable Long id) {
        UserAbilityReqNewVO vo = userAbilityReqNewService.getUserAbilityReqById(id);
        return Result.success(vo);
    }

    @Operation(summary = "查询所有人员能力要求")
    @GetMapping
    public Result<List<UserAbilityReqNewVO>> listAllUserAbilityReqs() {
        List<UserAbilityReqNewVO> list = userAbilityReqNewService.listAllUserAbilityReqs();
        return Result.success(list);
    }

    @Operation(summary = "根据人员ID查询能力要求")
    @GetMapping("/by-resource/{resourceId}")
    public Result<UserAbilityReqNewVO> getByResourceId(@PathVariable Long resourceId) {
        UserAbilityReqNewVO vo = userAbilityReqNewService.getByResourceId(resourceId);
        return Result.success(vo);
    }
}
