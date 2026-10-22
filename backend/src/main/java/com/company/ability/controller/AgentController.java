package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.service.AgentBridgeService;
import com.company.ability.service.AgentSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 智能导入（二期）：会话运行时 + 交付稿。全程测评 JWT 同源，浏览器不接触任何其他服务。
 * /draft 由 cp_server 以内部令牌调用（SecurityConfig 放行，服务内校验令牌）；
 * 写入只存在于 /drafts/{id}/commit（前端按钮点击=确认）。
 */
@Tag(name = "智能导入", description = "测评内置 Agent 会话与交付稿")
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentBridgeService agentBridgeService;
    private final AgentSessionService agentSessionService;

    @Operation(summary = "新建导入会话（每次进入页面调用，全新上下文）")
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession() {
        return Result.success(agentSessionService.createSession());
    }

    /**
     * 发送消息：请求体本身是普通 POST JSON，响应是 SSE 流（text/event-stream），
     * 前端用 fetch 流式读取。一轮 = 一个 claude 进程回合。
     */
    @Operation(summary = "发送消息（SSE 流式回复）")
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter messages(@PathVariable String sessionId, @RequestBody MessageRequest req) {
        return agentSessionService.sendMessage(sessionId, req.getContent(), req.getFiles());
    }

    @Operation(summary = "会话历史（审计留痕）")
    @GetMapping("/sessions/{sessionId}/history")
    public Result<List<Map<String, Object>>> history(@PathVariable String sessionId) {
        return Result.success(agentSessionService.history(sessionId));
    }

    @Operation(summary = "停止本轮生成")
    @PostMapping("/sessions/{sessionId}/stop")
    public Result<Void> stop(@PathVariable String sessionId) {
        agentSessionService.stopSession(sessionId);
        return Result.success();
    }

    @Operation(summary = "附件上传（按会话落盘 cp/uploads/{sessionId}，agent 解析时校验归属）")
    @PostMapping("/upload")
    public Result<List<Map<String, Object>>> upload(@RequestParam String sessionId,
                                                    @RequestPart("files") List<MultipartFile> files) {
        return Result.success(agentSessionService.upload(sessionId, files));
    }

    /** cp_server deliver/validate 入口（内部令牌，无 JWT） */
    @Operation(summary = "Agent 提交交付稿（内部令牌）")
    @PostMapping("/draft")
    public ResponseEntity<Result<Map<String, Object>>> draft(
            @RequestHeader(value = "X-Agent-Token", required = false) String agentToken,
            @RequestBody Map<String, Object> body) {
        String payload = String.valueOf(body.get("payload"));
        boolean validateOnly = Boolean.parseBoolean(String.valueOf(body.getOrDefault("validate", "false")));
        Result<Map<String, Object>> result =
                Result.success(agentBridgeService.submitDraft(agentToken, payload, validateOnly));
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "按会话查最新交付稿（前端轮询=活跃心跳）")
    @GetMapping("/drafts")
    public Result<List<Map<String, Object>>> drafts(@RequestParam String sessionId) {
        return Result.success(agentBridgeService.listDrafts(sessionId));
    }

    @Operation(summary = "点击写入：确认+提交交付稿（重校验挡回时保持可重议）")
    @PostMapping("/drafts/{id}/commit")
    public Result<Map<String, Object>> commit(@PathVariable long id) {
        return Result.success(agentBridgeService.commitDraft(id));
    }

    /** 消息请求体 */
    @lombok.Data
    public static class MessageRequest {
        private String content;
        private List<String> files;
    }
}
