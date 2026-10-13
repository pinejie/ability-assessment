package com.company.ability.controller;

import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessReportService;
import com.company.ability.service.AssessSheetQueryService;
import com.company.ability.vo.AssessSheetVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


/**
 * 测评汇总页面（只读）。评级、意见与年度分均由泛微钩子写入，本接口不提供写入。
 */
@Tag(name = "测评汇总", description = "测评汇总页面列表与详情（只读）")
@RestController
@RequestMapping("/api/v1/assess")
@RequiredArgsConstructor
public class AssessSheetController {

    private final AssessSheetQueryService queryService;
    private final AssessReportService reportService;

    @Operation(summary = "测评汇总列表（分页）")
    @GetMapping("/sheets")
    public Result<PageResult<AssessSheetVO.ListItem>> page(
            @RequestParam Integer year,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String gradeName,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(queryService.page(year, resourceId, departmentId, gradeName, status, pageNum, pageSize));
    }

    @Operation(summary = "测评汇总详情（弹窗）")
    @GetMapping("/sheets/{id}")
    public Result<AssessSheetVO.Detail> detail(@PathVariable Long id) {
        return Result.success(queryService.detail(id));
    }

    @Operation(summary = "个人测评报告（弹窗）")
    @GetMapping("/sheets/{id}/report")
    public Result<AssessSheetVO.Report> report(@PathVariable Long id) {
        return Result.success(reportService.get(id));
    }
}
