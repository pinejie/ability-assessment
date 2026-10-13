package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.service.AssessPeriodService;
import com.company.ability.vo.AssessSheetVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 年度设置
 */
@Tag(name = "年度设置", description = "新增年度，设置进行中/已关闭")
@RestController
@RequestMapping("/api/v1/assess/periods")
@RequiredArgsConstructor
public class AssessPeriodController {

    private final AssessPeriodService periodService;

    @Operation(summary = "年度列表")
    @GetMapping
    public Result<List<AssessSheetVO.PeriodItem>> list() {
        return Result.success(periodService.list());
    }

    @Operation(summary = "新增年度（默认进行中）")
    @PostMapping
    public Result<Void> create(@RequestParam Integer year) {
        periodService.create(year);
        return Result.success();
    }

    @Operation(summary = "设置年度状态：0 进行中 1 已关闭")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        periodService.changeStatus(id, status);
        return Result.success();
    }
}
