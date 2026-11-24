package com.company.ability.controller;

import com.company.ability.dto.PaTaskDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaTaskService;
import com.company.ability.vo.PaTaskVO;
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
 * 测评任务发起（方案 17 §7.2）。
 *
 * <p>权限：发起是管理动作，依赖 {@code DimensionContextFilter} 的维度授权校验
 * （前端带 X-Dimension-Id）。未授权维度在过滤器层即 403，本接口不再重复判定。</p>
 *
 * <p>列表 / 详情 / 撤回 / 催办在 {@link PaTaskOpController}（同路径，不同动作）。</p>
 */
@Tag(name = "测评任务发起", description = "发起测评任务并生成自评/上级/360 三条评价链接")
@RestController
@RequestMapping("/api/v1/pa/assess-tasks")
@RequiredArgsConstructor
public class PaTaskController {

    private final PaTaskService taskService;

    @Operation(summary = "发起测评任务（年度 + 被测人 + 岗位 + 360 测评人）")
    @PostMapping
    public Result<PaTaskVO.CreateResult> create(@Valid @RequestBody PaTaskDTO.CreateRequest req) {
        return Result.success("测评任务已发起", taskService.create(req));
    }

    @Operation(summary = "发起前人员预检（列出所有登不进系统的人，只读不写）")
    @GetMapping("/precheck")
    public Result<PaTaskVO.PrecheckResult> precheck(@Valid PaTaskDTO.PrecheckRequest req) {
        return Result.success(taskService.precheck(req));
    }

    /**
     * 发起页的「直接上级」：由被测人档案带出，页面只读展示。
     * 不提供搜索接口 —— 上级是可申辩的组织关系，不能让人在下拉里随便挑一个。
     */
    @Operation(summary = "按被测人取直接上级（OA 组织关系，只读）")
    @GetMapping("/leader")
    public Result<PaTaskVO.LeaderInfo> leader(@RequestParam Long resourceId) {
        return Result.success(taskService.resolveLeader(resourceId));
    }

    /**
     * 按岗位取能力模型信息：页面要在选中岗位的瞬间就知道
     * 「几项、要不要选 360」，否则只能等点发起才报错。
     */
    @Operation(summary = "按岗位取能力模型信息（含能力项数与 360 项数）")
    @GetMapping("/post")
    public Result<PaTaskVO.PostInfo> post(@RequestParam Long postId) {
        return Result.success(taskService.resolvePost(postId));
    }
}
