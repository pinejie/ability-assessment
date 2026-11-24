package com.company.ability.controller;

import com.company.ability.dto.PaImportAnswer;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportSessionVO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 能力结构导入（Excel → 能力模型）。
 *
 * <p><b>所有动作都是「投任务 + 立刻返回」，前端用 {@code GET /state} 轮询</b>
 * （业务明确要求，与人资报表的分析问答同一套做法）。原因：模型单次调用实测 18~25 秒，
 * 一张表一次，5 张表两分钟。一个请求挂两分钟，中间任何一层代理超时断开，
 * 后端还在跑前端已判失败 —— 用户重试一次就有两个任务写同一个岗位。</p>
 *
 * <p>会话落库（{@code uf_pa_import_session}），所以切页面、关浏览器、进程重启都不影响
 * 「回来接着看」。进页面先调 {@code GET /sessions/current} 恢复。</p>
 *
 * <p><b>文件大小限制 20MB</b>（application.yml 的 multipart 配置）：客户这份能力模型
 * 实测 25 个 sheet、130KB。留到 20MB 是因为带图片的模板会大很多，而导入只读文字。</p>
 */
@Tag(name = "能力结构导入", description = "Excel 批量导入岗位能力模型（AI 识别 + 人机确认）")
@RestController
@RequestMapping("/api/v1/pa/import")
@RequiredArgsConstructor
public class PaImportController {

    private final PaImportService importService;

    @Operation(summary = "上传 Excel，返回会话号（后续都靠它轮询）")
    @PostMapping("/sessions")
    public Result<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {
        String sessionKey = importService.upload(file, overwrite);
        return Result.success("文件已接收，正在识别", Map.of("sessionKey", sessionKey));
    }

    @Operation(summary = "进页面恢复：当前登录人最近一次会话（没有返回 null）")
    @GetMapping("/sessions/current")
    public Result<PaImportSessionVO> current() {
        return Result.success(importService.current());
    }

    @Operation(summary = "轮询会话状态（1.5 秒一次）")
    @GetMapping("/sessions/{sessionKey}")
    public Result<PaImportSessionVO> state(@PathVariable String sessionKey) {
        return Result.success(importService.state(sessionKey));
    }

    @Operation(summary = "确认要导入哪些工作表")
    @PostMapping("/sessions/{sessionKey}/sheets")
    public Result<PaImportSessionVO> selectSheets(@PathVariable String sessionKey,
                                                  @RequestBody PaImportAnswer answer) {
        importService.selectSheets(sessionKey, answer);
        return Result.success("已开始识别选中的工作表", importService.state(sessionKey));
    }

    @Operation(summary = "提交一轮回答（可多轮，直到没有疑问）")
    @PostMapping("/sessions/{sessionKey}/answers")
    public Result<PaImportSessionVO> answer(@PathVariable String sessionKey,
                                            @RequestBody PaImportAnswer answer) {
        importService.answer(sessionKey, answer);
        return Result.success("已收到", importService.state(sessionKey));
    }

    @Operation(summary = "调整导入计划（覆盖策略 / 重算封存报告 / 撤回进行中任务），重算预览")
    @PutMapping("/sessions/{sessionKey}/plan")
    public Result<PaImportSessionVO> updatePlan(@PathVariable String sessionKey,
                                                @RequestBody PaImportPlan plan) {
        return Result.success("已更新导入计划", importService.updatePlan(sessionKey, plan));
    }

    @Operation(summary = "确认导入（写库，整批一个事务）")
    @PostMapping("/sessions/{sessionKey}/apply")
    public Result<PaImportSessionVO> apply(@PathVariable String sessionKey) {
        importService.apply(sessionKey);
        return Result.success("已开始写入", importService.state(sessionKey));
    }

    @Operation(summary = "放弃当前会话")
    @DeleteMapping("/sessions/{sessionKey}")
    public Result<Void> abandon(@PathVariable String sessionKey) {
        importService.abandon(sessionKey);
        return Result.success("已放弃本次导入", null);
    }
}
