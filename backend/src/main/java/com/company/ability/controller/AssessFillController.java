package com.company.ability.controller;

import com.company.ability.dto.AssessFillDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessFillService;
import com.company.ability.vo.AssessTaskVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价填写（评价人使用）。
 *
 * 鉴权：需登录（SecurityConfig 的 anyRequest().authenticated()），且登录人必须等于 rater_resource_id
 * （服务层校验，防代填）。token 只是入口，不是身份 —— 链接被转发也提交不了（§10.2）。
 *
 * 注意：GET 接口在任务已撤回时返回成功码 + status='VOIDED'，不抛异常 ——
 * 评价人点旧链接要看到「已撤回」提示，而不是错误页（§4.3.8）。
 */
@Tag(name = "评价填写", description = "评价人专属链接：取数、暂存草稿、提交")
@RestController
@RequestMapping("/api/v1/assess-fill")
@RequiredArgsConstructor
public class AssessFillController {

    private final AssessFillService fillService;

    @Operation(summary = "填写页数据（任务已撤回时返回 status=VOIDED，不含要素与草稿）")
    @GetMapping("/{token}")
    public Result<AssessTaskVO.FillPage> page(@PathVariable String token) {
        return Result.success(fillService.page(token));
    }

    @Operation(summary = "暂存草稿（每次选择要素触发；带 version 做并发保护）")
    @PostMapping("/{token}/draft")
    public Result<AssessTaskVO.DraftResult> saveDraft(@PathVariable String token,
                                                      @Valid @RequestBody AssessFillDTO.DraftRequest req) {
        return Result.success(fillService.saveDraft(token, req));
    }

    @Operation(summary = "提交评价（服务端二次校验完整性；单人提交不触发算分，全员齐备才算）")
    @PostMapping("/{token}/submit")
    public Result<AssessTaskVO.SubmitResult> submit(@PathVariable String token,
                                                    @Valid @RequestBody AssessFillDTO.SubmitRequest req) {
        return Result.success(fillService.submit(token, req));
    }
}
