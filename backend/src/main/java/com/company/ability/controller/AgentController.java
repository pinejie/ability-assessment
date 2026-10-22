package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.service.AgentBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 智能导入（二期）：会话代理 + Agent 交付稿。
 * /draft 由 cp_server 以内部令牌调用（SecurityConfig 放行，令牌在服务内校验）；
 * 其余接口走测评 JWT 登录态。写入只存在于 /drafts/{id}/commit（前端按钮）。
 */
@Tag(name = "智能导入", description = "AI 会话桥与交付稿")
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentBridgeService agentBridgeService;

    @Operation(summary = "前端连接配置（WS 地址等）")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.success(agentBridgeService.clientConfig());
    }

    @Operation(summary = "新建智能导入会话（绑定 cp 工作空间）")
    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession() {
        return Result.success(agentBridgeService.createSession());
    }

    @Operation(summary = "会话历史")
    @GetMapping("/sessions/{sessionId}/history")
    public Result<JsonNode> history(@PathVariable String sessionId) {
        return Result.success(agentBridgeService.history(sessionId));
    }

    @Operation(summary = "停止会话生成")
    @PostMapping("/sessions/{sessionId}/stop")
    public Result<JsonNode> stop(@PathVariable String sessionId) {
        return Result.success(agentBridgeService.stopSession(sessionId));
    }

    @Operation(summary = "附件上传（转发 zd-code，返回文件路径随消息发送）")
    @PostMapping("/upload")
    public Result<JsonNode> upload(@RequestPart("files") List<MultipartFile> files) {
        return Result.success(agentBridgeService.upload(files));
    }

    /**
     * cp_server deliver/validate 入口（内部令牌，无 JWT）。
     * validate=true 仅校验不落稿；否则校验通过即落 READY 稿并返回 ready 标识。
     */
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
}
