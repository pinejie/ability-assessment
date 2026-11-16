package com.company.ability.controller;

import com.company.ability.dto.AssessTaskDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessTaskService;
import com.company.ability.vo.AssessTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测评任务（S1：仅发起）。列表、进度矩阵、催办、撤回/取消/重算在 S3。
 *
 * 权限：发起是管理动作，依赖 DimensionContextFilter 的维度授权校验（前端带 X-Dimension-Id）。
 * 未授权维度在过滤器层即 403，本接口不再重复判定。
 */
@Tag(name = "测评任务", description = "发起测评任务并生成评价链接")
@RestController
@RequestMapping("/api/v1/assess-tasks")
@RequiredArgsConstructor
public class AssessTaskController {

    private final AssessTaskService taskService;

    @Operation(summary = "发起测评任务（生成自评/领导/客户链接，S1 无消息通道，靠返回的链接手工验证）")
    @PostMapping
    public Result<AssessTaskVO.CreateResult> create(@Valid @RequestBody AssessTaskDTO.CreateRequest req) {
        return Result.success("测评任务已发起", taskService.create(req));
    }

    @Operation(summary = "发起前人员预检（列出所有登不进系统的人，只读不写）")
    @GetMapping("/precheck")
    public Result<AssessTaskVO.PrecheckResult> precheck(@Valid AssessTaskDTO.PrecheckRequest req) {
        return Result.success(taskService.precheck(req));
    }

    /**
     * 发起页的「直接上级」：由被测人档案带出，页面只读展示。
     * 不提供搜索接口 —— 上级是可申辩的组织关系，不能让人在下拉里随便挑一个。
     */
    @Operation(summary = "按被测人取直接上级（OA 组织关系，只读）")
    @GetMapping("/leader")
    public Result<AssessTaskVO.LeaderInfo> leader(@RequestParam Long resourceId) {
        return Result.success(taskService.resolveLeader(resourceId));
    }
}
