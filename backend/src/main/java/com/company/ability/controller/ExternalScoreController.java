package com.company.ability.controller;

import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.ExternalScoreService;
import com.company.ability.vo.ExternalScoreVO;
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

import java.util.List;

/**
 * 外部评分导入（非流程要素评级 / 年度分）。
 * 泛微钩子通道不动；本接口是测评侧唯一外部写入口，全部经存储过程落库。
 */
@Tag(name = "外部评分导入", description = "非流程要素评级与年度分的外部导入通道")
@RestController
@RequestMapping("/api/v1/external")
@RequiredArgsConstructor
public class ExternalScoreController {

    private final ExternalScoreService externalScoreService;

    @Operation(summary = "某年度本维度被测人列表（含外部要素覆盖）")
    @GetMapping("/contexts")
    public Result<List<ExternalScoreVO.SheetContext>> contexts(@RequestParam int year) {
        return Result.success(externalScoreService.contexts(year));
    }

    @Operation(summary = "某人某年度：非流程要素与当前外部值")
    @GetMapping("/context")
    public Result<ExternalScoreVO.SheetContext> context(@RequestParam int year,
                                                        @RequestParam long resourceId) {
        return Result.success(externalScoreService.context(year, resourceId));
    }

    @Operation(summary = "整稿校验（PASS/WARN/BLOCK + 覆盖摘要），不落库")
    @PostMapping("/preview")
    public Result<ExternalScoreVO.PreviewResult> preview(@Valid @RequestBody ExternalScoreDTO.ImportRequest req) {
        return Result.success(externalScoreService.preview(req));
    }

    @Operation(summary = "提交（存在 BLOCK 行整体拒绝；按人独立成败，要素级 upsert）")
    @PostMapping("/commit")
    public Result<ExternalScoreVO.CommitResult> commit(@Valid @RequestBody ExternalScoreDTO.ImportRequest req) {
        return Result.success("提交完成", externalScoreService.commit(req));
    }

    @Operation(summary = "按上稿重填（评价人名单变化后用最后成功稿重放）")
    @PostMapping("/refill")
    public Result<ExternalScoreVO.CommitResult> refill(@Valid @RequestBody ExternalScoreDTO.PersonRequest req) {
        return Result.success("重填完成", externalScoreService.refill(req));
    }

    @Operation(summary = "清空某人全部外部导入评级（显式删除，唯一删除路径）")
    @PostMapping("/clear")
    public Result<ExternalScoreVO.CommitResult> clear(@Valid @RequestBody ExternalScoreDTO.PersonRequest req) {
        return Result.success("已清空", externalScoreService.clear(req));
    }
}
