package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.service.AssessPeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 年度设置。
 *
 * <p>2026-11-23 起挂在维度上：读对维度内所有人开放（发起测评 / 任务发起 / 测评报告
 * 三页都要年度下拉），写只给该维度的维度管理员与系统管理员（方案 B）。</p>
 */
@Tag(name = "年度设置", description = "新增年度，设置进行中/已关闭")
@RestController
@RequestMapping("/api/v1/assess/periods")
@RequiredArgsConstructor
public class AssessPeriodController {

    private final AssessPeriodService periodService;

    @Operation(summary = "年度列表")
    @GetMapping
    public Result<List<AssessPeriodService.PeriodItem>> list() {
        return Result.success(periodService.list());
    }

    /** 当前登录人能否维护当前维度的年度：前端据此点亮/置灰按钮，真实拦截在服务层 */
    @Operation(summary = "是否有年度维护权限")
    @GetMapping("/can-write")
    public Result<Boolean> canWrite() {
        return Result.success(periodService.canWrite());
    }

    @Operation(summary = "新增年度（默认进行中，仅维度管理员/系统管理员）")
    @PostMapping
    public Result<Void> create(@RequestParam Integer year) {
        periodService.create(year);
        return Result.success();
    }

    @Operation(summary = "设置年度状态：0 进行中 1 已关闭（仅维度管理员/系统管理员）")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        periodService.changeStatus(id, status);
        return Result.success();
    }
}
