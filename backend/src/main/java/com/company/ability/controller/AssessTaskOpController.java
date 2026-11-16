package com.company.ability.controller;

import com.company.ability.dto.AssessTaskOpDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessTaskOpService;
import com.company.ability.vo.AssessTaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测评任务运营接口（S3）：列表、详情、撤回、取消、催办、重算。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.1、§7.2.1
 *
 * 权限：维度授权由 DimensionContextFilter 在入口统一把关（管理员豁免 + 授权表），
 * 本层不再重复判定；服务层只校验任务归属维度与状态。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AssessTaskOpController {

    private final AssessTaskOpService opService;

    /** 任务列表（分页） */
    @GetMapping("/assess-tasks")
    public Result<Map<String, Object>> list(@ModelAttribute AssessTaskOpDTO.TaskQuery query) {
        return Result.success(opService.list(query));
    }

    /** 任务详情 + 评价人进度 */
    @GetMapping("/assess-tasks/{id}")
    public Result<AssessTaskVO.TaskDetail> detail(@PathVariable Long id) {
        return Result.success(opService.detail(id));
    }

    /**
     * 撤回任务（§4.3）：删评级 + 重置页面 + 作废评价人 + 通知已提交者。
     * 已完成任务需 force=true。
     */
    @PostMapping("/assess-tasks/{id}/void")
    public Result<AssessTaskVO.VoidResult> voidTask(@PathVariable Long id,
                                                    @Valid @RequestBody AssessTaskOpDTO.VoidRequest req) {
        AssessTaskVO.VoidResult r = opService.voidTask(id, req);
        String msg = Boolean.TRUE.equals(r.getAlreadyVoided()) ? "任务此前已撤回"
                : Boolean.TRUE.equals(r.getNoticeWritten())
                    ? "已撤回，测评数据已回到评价初状态"
                    : "撤回成功，但撤回通知发送失败，请在任务详情页补发";
        return Result.success(msg, r);
    }

    /** 补发撤回通知（§8.6.4 兜底）：仅已撤回任务可用，靠 biz_key 判重 */
    @PostMapping("/assess-tasks/{id}/void-notice")
    public Result<AssessTaskVO.VoidResult> resendVoidNotice(@PathVariable Long id) {
        AssessTaskVO.VoidResult r = opService.resendVoidNotice(id);
        return Result.success("补发完成", r);
    }

    /** 催办（§8.5）：只推未提交的人 */
    @PostMapping("/assess-tasks/{id}/remind")
    public Result<AssessTaskVO.RemindResult> remind(@PathVariable Long id,
                                                    @RequestBody(required = false) AssessTaskOpDTO.RemindRequest req) {
        AssessTaskVO.RemindResult r = opService.remind(id, req);
        return Result.success(r.getMessage(), r);
    }

    /** 重新计算得分（§7.1.2）：仅进行中 / 已完成可用 */
    @PostMapping("/assess-tasks/{id}/retry")
    public Result<AssessTaskVO.RetryResult> retry(@PathVariable Long id) {
        return Result.success("已重新计算", opService.retry(id));
    }

    /**
     * 删除任务（2026-11-14）：仅已取消 / 已撤回可用。
     *
     * 物理删除任务与评价人记录；关联的测评页（人+年）保留，可能仍被其他任务引用。
     */
    @DeleteMapping("/assess-tasks/{id}")
    public Result<AssessTaskVO.DeleteResult> delete(@PathVariable Long id) {
        AssessTaskVO.DeleteResult r = opService.delete(id);
        return Result.success("任务已删除，测评页已保留", r);
    }

    /**
     * 「我的评价」列表（§7.2.1）。
     * 不接受任何 resourceId 参数 —— 查谁由服务端从登录态取，避免越权枚举。
     */
    @GetMapping("/my-assess-tasks")
    public Result<List<AssessTaskVO.MyTaskItem>> myTasks() {
        return Result.success(opService.myTasks());
    }
}
