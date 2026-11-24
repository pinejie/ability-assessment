package com.company.ability.controller;

import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaTaskOpService;
import com.company.ability.vo.PaTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测评任务运营（方案 17 §7.2）：列表、详情、撤回、催办、重算、删除、我的评价。
 *
 * <p>与 {@link PaTaskController}（发起）共用 {@code /api/v1/pa/assess-tasks} 前缀，
 * 拆两个类只是因为一个管「造」一个管「维」，方法签名各自内聚；Spring 按
 * 方法+路径元组路由，不会冲突（POST / 与 GET / 分属两侧）。</p>
 */
@Tag(name = "测评任务管理", description = "任务列表、详情、撤回、催办、重算、删除")
@RestController
@RequestMapping("/api/v1/pa/assess-tasks")
@RequiredArgsConstructor
public class PaTaskOpController {

    private final PaTaskOpService opService;

    @Operation(summary = "任务列表（分页；默认只看进行中与已完成）")
    @GetMapping
    public Result<Map<String, Object>> list(@Valid @ModelAttribute PaTaskOpDTO.TaskQuery query) {
        return Result.success(opService.list(query));
    }

    @Operation(summary = "任务详情 + 每个评价人的进度")
    @GetMapping("/{id}")
    public Result<PaTaskVO.TaskDetail> detail(@PathVariable Long id) {
        return Result.success(opService.detail(id));
    }

    /**
     * 撤回：删评级 + 重置页面 + 作废评价人 + 通知已提交者。
     * 已完成任务需 force=true（已出分的数一旦撤回不可恢复）。
     */
    @Operation(summary = "撤回测评任务（回到评价初）")
    @PostMapping("/{id}/void")
    public Result<PaTaskVO.VoidResult> voidTask(@PathVariable Long id,
                                                @Valid @RequestBody PaTaskOpDTO.VoidRequest req) {
        PaTaskVO.VoidResult r = opService.voidTask(id, req);
        if (Boolean.TRUE.equals(r.getAlreadyVoided())) {
            return Result.success("任务此前已撤回，本次未做改动", r);
        }
        return Result.success("任务已撤回，评价人链接已失效", r);
    }

    @Operation(summary = "补发撤回通知（仅已撤回任务）")
    @PostMapping("/{id}/void-notice")
    public Result<PaTaskVO.VoidResult> resendVoidNotice(@PathVariable Long id) {
        return Result.success("补发完成", opService.resendVoidNotice(id));
    }

    @Operation(summary = "催办（只推未提交的人）")
    @PostMapping("/{id}/remind")
    public Result<PaTaskVO.RemindResult> remind(@PathVariable Long id,
                                                @RequestBody(required = false) PaTaskOpDTO.RemindRequest req) {
        PaTaskVO.RemindResult r = opService.remind(id, req);
        return Result.success(r.getMessage(), r);
    }

    @Operation(summary = "重新计算得分（全员提交后才可用）")
    @PostMapping("/{id}/retry")
    public Result<PaTaskVO.RetryResult> retry(@PathVariable Long id) {
        return Result.success("已重新计算", opService.retry(id));
    }

    /** 只删任务与评价人；测评页（人+年）保留，可能仍被其他任务引用 */
    @Operation(summary = "删除已撤回/已作废的任务记录")
    @DeleteMapping("/{id}")
    public Result<PaTaskVO.DeleteResult> delete(@PathVariable Long id) {
        return Result.success("任务已删除，测评页已保留", opService.delete(id));
    }

    /**
     * 「我的评价」：查谁由服务端从登录态取，不接受任何 resourceId 参数，
     * 避免越权枚举别人的待办。
     *
     * @param year 年度，可空；「我的测评」页会传当前选中的年度
     */
    @Operation(summary = "我的评价列表")
    @GetMapping("/mine")
    public Result<List<PaTaskVO.MyTaskItem>> myTasks(
            @RequestParam(required = false) Integer year) {
        return Result.success(opService.myTasks(year));
    }
}
