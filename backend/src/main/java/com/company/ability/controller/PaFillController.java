package com.company.ability.controller;

import com.company.ability.dto.PaFillDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaFillService;
import com.company.ability.vo.PaFillPageVO;
import com.company.ability.vo.PaTaskVO;
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
 * 评价填写（评价人使用，方案 17 §7.2）。
 *
 * <p>鉴权：需登录，且登录人必须等于评价人本人（服务层校验，防代填）。
 * token 只是入口，不是身份 —— 链接被转发也提交不了。</p>
 *
 * <p>注意：GET 在任务已撤回时返回成功码 + status=VOIDED，不抛异常 ——
 * 评价人点旧链接要看到「已撤回」提示，而不是错误页。</p>
 */
@Tag(name = "评价填写", description = "评价人专属链接：取数、暂存草稿、提交")
@RestController
@RequestMapping("/api/v1/pa/assess-fill")
@RequiredArgsConstructor
public class PaFillController {

    private final PaFillService fillService;

    @Operation(summary = "填写页数据（任务已撤回时返回 status=VOIDED，不含能力项与草稿）")
    @GetMapping("/{token}")
    public Result<PaFillPageVO> page(@PathVariable String token) {
        return Result.success(fillService.page(token));
    }

    @Operation(summary = "暂存草稿（每次选择等级触发；带 version 做并发保护）")
    @PostMapping("/{token}/draft")
    public Result<PaTaskVO.DraftResult> saveDraft(@PathVariable String token,
                                                 @Valid @RequestBody PaFillDTO.DraftRequest req) {
        return Result.success(fillService.saveDraft(token, req));
    }

    @Operation(summary = "提交评价（服务端二次校验完整性；单人提交不触发算分，全员齐备才算）")
    @PostMapping("/{token}/submit")
    public Result<PaTaskVO.SubmitResult> submit(@PathVariable String token,
                                                @Valid @RequestBody PaFillDTO.SubmitRequest req) {
        return Result.success(fillService.submit(token, req));
    }
}
