package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.service.AgentBridgeService;
import com.company.ability.service.AgentImportRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 一键导入流水线端点（方案 11 §4.2）。
 * /api/v1/agent/outcome：cp_server 三出口回调（内部令牌，SecurityConfig 放行）；
 * /api/v1/external/import-runs*：批次编排（测评 JWT）。确认写入仍走 /api/v1/agent/drafts/{id}/commit。
 */
@Tag(name = "一键导入流水线", description = "上传→agent 校验→回环补充→确认写入")
@RestController
@RequiredArgsConstructor
public class ImportRunController {

    private final AgentImportRunService runService;
    private final AgentBridgeService bridge;

    @Operation(summary = "agent 收场回调（deliver|ask|reject，内部令牌）")
    @PostMapping("/api/v1/agent/outcome")
    public ResponseEntity<Result<Void>> outcome(
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken,
            @RequestBody Map<String, Object> body) {
        bridge.requireInternalToken(agentToken);
        runService.handleOutcome(String.valueOf(body.get("sessionId")),
                String.valueOf(body.get("kind")), String.valueOf(body.get("outcome")));
        return ResponseEntity.ok(Result.success());
    }

    @Operation(summary = "建批（返回 sessionId 供 /agent/upload 上传文件）")
    @PostMapping("/api/v1/external/import-runs")
    public Result<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        Object rules = body == null ? null : body.get("rules");
        return Result.success(runService.createRun(rules == null ? null : String.valueOf(rules)));
    }

    @Operation(summary = "开始/重试解析（文件须属本会话上传目录）")
    @PostMapping("/api/v1/external/import-runs/{id}/start")
    public Result<Map<String, Object>> start(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.success(runService.startRun(id, String.valueOf(body.get("fileName"))));
    }

    @Operation(summary = "对 ask 的答复（触发下一轮）")
    @PostMapping("/api/v1/external/import-runs/{id}/answer")
    @SuppressWarnings("unchecked")
    public Result<Map<String, Object>> answer(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> answers = (List<Map<String, Object>>) body.get("answers");
        return Result.success(runService.answerRun(id, answers));
    }

    @Operation(summary = "EXHAUSTED/FAILED 出口：chat|manual|abandon")
    @PostMapping("/api/v1/external/import-runs/{id}/exit")
    public Result<Map<String, Object>> exit(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return Result.success(runService.exitRun(id, String.valueOf(body.get("to"))));
    }

    @Operation(summary = "commitDraft 成功后收尾（回执入库）")
    @PostMapping("/api/v1/external/import-runs/{id}/complete")
    public Result<Void> complete(@PathVariable long id, @RequestBody Map<String, Object> body) {
        runService.completeRun(id, String.valueOf(body.get("result")));
        return Result.success();
    }

    @Operation(summary = "批次快照（断线兜底轮询）")
    @GetMapping("/api/v1/external/import-runs/{id}")
    public Result<Map<String, Object>> get(@PathVariable long id) {
        return Result.success(runService.snapshot(id));
    }

    @Operation(summary = "批次事件流（SSE：run 状态 + turn 回合帧）")
    @GetMapping(value = "/api/v1/external/import-runs/{id}/events",
            produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        return runService.events(id);
    }

    @Operation(summary = "近 30 天批次历史")
    @GetMapping("/api/v1/external/import-runs")
    public Result<List<Map<String, Object>>> history() {
        return Result.success(runService.history());
    }
}
