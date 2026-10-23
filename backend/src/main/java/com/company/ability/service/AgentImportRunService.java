package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 一键导入流水线编排（方案 11）。
 * run 状态机权威在后端：agent 三出口（deliver/ask/reject）经 /agent/outcome 驱动迁移，
 * 轮次后端计数（第 4 次 ask 按耗尽处理）；deliver 必过后端复验才置 READY；
 * 写入仍只有 draft + commitDraft 一条路。提示词模板进 git（resources/agent），版本入 run 留档。
 */
@Slf4j
@Service
public class AgentImportRunService {

    public static final String PROMPT_VERSION = "imp-v1";
    private static final int MAX_ASK_ROUNDS = 3;
    private static final int STALE_MINUTES = 30;

    private static final String S_DRAFT = "DRAFT";
    private static final String S_PARSING = "PARSING";
    private static final String S_NEED_INPUT = "NEED_INPUT";
    private static final String S_EXHAUSTED = "EXHAUSTED";
    private static final String S_READY = "READY";
    private static final String S_DONE = "DONE";
    private static final String S_FAILED = "FAILED_RETRYABLE";
    private static final String S_REJECTED = "REJECTED";
    private static final String S_STALE = "STALE";
    private static final String S_CHAT_ESC = "CHAT_ESC";
    private static final String S_MANUAL = "MANUAL";

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentSessionService sessionService;
    private final AgentBridgeService bridge;
    private final long companyId;
    private final String internalToken;
    private final Path workspace;

    private String systemPrompt = "";
    private String taskTemplate = "";
    /** run 事件总线（SSE）与本回合 outcome 到达标记 */
    private final Map<Long, List<Consumer<Map<String, Object>>>> runListeners = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> outcomeSeen = new ConcurrentHashMap<>();
    /** 回合派发排队器：上一轮 CLI 进程未退出时，answer 起的下一轮在此等待（review：ask 后模型仍会收尾几秒） */
    private final java.util.concurrent.ExecutorService dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "import-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, String> pendingStatus = new ConcurrentHashMap<>();

    public AgentImportRunService(org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper mapper,
                                 AgentSessionService sessionService, AgentBridgeService bridge,
                                 @Value("${assess.company-id:1}") long companyId,
                                 @Value("${agent.internal-token:}") String internalToken,
                                 @Value("${agent.workspace-path:/home/wgd/ws/cp}") String workspacePath) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sessionService = sessionService;
        this.bridge = bridge;
        this.companyId = companyId;
        this.internalToken = internalToken;
        this.workspace = Path.of(workspacePath);
    }

    @PostConstruct
    void loadPrompts() throws IOException {
        systemPrompt = readClasspath("agent/import-prompt-system.md");
        taskTemplate = readClasspath("agent/import-prompt-task.md");
    }

    private static String readClasspath(String r) throws IOException {
        try (var in = AgentImportRunService.class.getClassLoader().getResourceAsStream(r)) {
            if (in == null) {
                throw new IOException("缺少提示词模板 " + r);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ==================== 批次生命周期 ====================

    /**
     * 建批 = 建内部会话 + DRAFT run；上传沿用 /agent/upload?sessionId 走会话隔离目录。
     * 建批即固化操作人页面当前维度（请求头上下文，越权已被 Filter 拦）——后续 AI 回合与交付校验都以此为准，
     * 权限再多也以当前维度为准。
     */
    public Map<String, Object> createRun(String rules) {
        LoginUser user = requireUser();
        long dimensionId = com.company.ability.context.DimensionContext.getOrDefault();
        String sessionId = (String) sessionService.createSession().get("sessionId");
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uf_assess_import_run (company_id, dimension_id, session_id, file_path, file_name, user_rules, status, prompt_version, created_by)"
                            + " VALUES (?, ?, ?, '', 'pending', ?, '" + S_DRAFT + "', ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, companyId);
            ps.setLong(2, dimensionId);
            ps.setString(3, sessionId);
            ps.setString(4, rules == null || rules.isBlank() ? null : rules);
            ps.setString(5, PROMPT_VERSION);
            ps.setLong(6, user.resourceId());
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new BusinessException(500, "批次创建失败");
        }
        return snapshot(key.longValue());
    }

    /** 开始/重试解析：校验文件归属本会话目录后起第一轮 */
    public Map<String, Object> startRun(long runId, String fileName) {
        Map<String, Object> run = requireOwnedRun(runId);
        if (!List.of(S_DRAFT, S_FAILED, S_STALE).contains((String) run.get("status"))) {
            throw new BusinessException(409, "当前状态不可开始解析：" + run.get("status"));
        }
        String sessionId = (String) run.get("session_id");
        Path file = workspace.resolve("uploads").resolve(sessionId).resolve(fileName == null ? "" : fileName)
                .normalize();
        if (!file.startsWith(workspace.resolve("uploads").resolve(sessionId))
                || !Files.isRegularFile(file)
                || !List.of(".xlsx", ".xls", ".csv").contains(suffix(file.toString()))) {
            throw new BusinessException(400, "文件不存在或不属于本批次会话（仅 .xlsx/.xls/.csv）");
        }
        jdbc.update("UPDATE uf_assess_import_run SET file_path=?, file_name=?, status='" + S_PARSING
                + "', fail_reason=NULL, updated_at=SYSDATETIME() WHERE id=?", file.toString(), fileName, runId);
        broadcast(runId, Map.of("type", "run", "status", S_PARSING));
        beginRound(runId, sessionId, run.get("user_rules") == null ? null : String.valueOf(run.get("user_rules")),
                run.get("transcript_json") == null ? null : String.valueOf(run.get("transcript_json")),
                intOf(run.get("round_count")));
        return snapshot(runId);
    }

    /** 用户对 ask 的答复 → 下一轮 */
    public Map<String, Object> answerRun(long runId, List<Map<String, Object>> answers) {
        Map<String, Object> run = requireOwnedRun(runId);
        if (!S_NEED_INPUT.equals(run.get("status"))) {
            throw new BusinessException(409, "当前状态不接受补充：" + run.get("status"));
        }
        ArrayNode transcript = parseArray(run.get("transcript_json") == null ? null : String.valueOf(run.get("transcript_json")));
        ObjectNode entry = transcript.addObject();
        entry.set("answer", mapper.valueToTree(answers == null ? List.of() : answers));
        jdbc.update("UPDATE uf_assess_import_run SET status='" + S_PARSING + "', transcript_json=?," +
                " updated_at=SYSDATETIME() WHERE id=?", transcript.toString(), runId);
        broadcast(runId, Map.of("type", "run", "status", S_PARSING));
        enqueueRound(runId, (String) run.get("session_id"),
                run.get("user_rules") == null ? null : String.valueOf(run.get("user_rules")),
                transcript.toString(), intOf(run.get("round_count")));
        return snapshot(runId);
    }

    /** agent 三出口回调（/agent/outcome，内部令牌）。status 权威迁移点。 */
    public void handleOutcome(String sessionId, String kind, String outcomeJson) {
        Map<String, Object> run = jdbc.queryForList(
                        "SELECT * FROM uf_assess_import_run WHERE session_id = ? AND company_id = ?" +
                        " AND status IN ('" + S_PARSING + "','" + S_CHAT_ESC + "')", sessionId, companyId).stream()
                .findFirst().orElseThrow(() -> new BusinessException(409, "无正在等待收场的批次（状态不符或已处理）"));
        long runId = longOf(run.get("id"));
        JsonNode outcome;
        try {
            outcome = mapper.readTree(outcomeJson);
        } catch (Exception e) {
            throw new BusinessException(400, "outcome 不是合法 JSON");
        }
        outcomeSeen.put(runId, true);
        switch (kind) {
            case "ask" -> handleAsk(runId, run, outcome);
            case "reject" -> {
                String reason = outcome.path("reason").asText("agent 判定无法导入");
                update(runId, S_REJECTED, outcomeJson, null, reason, null, null);
                broadcast(runId, Map.of("type", "run", "status", S_REJECTED, "reason", reason,
                        "evidence", outcome.path("evidence")));
            }
            case "deliver" -> handleDeliver(runId, run, outcome, outcomeJson);
            default -> throw new BusinessException(400, "未知 kind：" + kind);
        }
    }

    private void handleAsk(long runId, Map<String, Object> run, JsonNode outcome) {
        int round = intOf(run.get("round_count")) + 1;
        if (round > MAX_ASK_ROUNDS) {   // 第 4 次 ask：闸在后端，不在模型
            update(runId, S_EXHAUSTED, outcome.toString(), null, "补充轮次用尽", null, round);
            broadcast(runId, Map.of("type", "run", "status", S_EXHAUSTED, "reason", "补充轮次用尽，请选人工通道"));
            return;
        }
        ArrayNode transcript = parseArray(run.get("transcript_json") == null ? null : String.valueOf(run.get("transcript_json")));
        ObjectNode entry = transcript.addObject();
        entry.put("round", round);
        entry.set("questions", outcome.path("questions"));
        entry.put("note", round == MAX_ASK_ROUNDS ? "FINAL_ROUND" : "");
        update(runId, S_NEED_INPUT, outcome.toString(), transcript.toString(), null, null, round);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "run");
        ev.put("status", S_NEED_INPUT);
        ev.put("round", round);
        ev.put("maxRounds", MAX_ASK_ROUNDS);
        ev.put("questions", outcome.path("questions"));
        broadcast(runId, ev);
    }

    private void handleDeliver(long runId, Map<String, Object> run, JsonNode outcome, String outcomeJson) {
        String sessionId = String.valueOf(run.get("session_id"));
        // 模型可能已走 deliver 旧路生成稿：带 draftId 则验归属后直接收编，不重复造稿
        long presetDraft = outcome.path("draftId").asLong(0);
        if (presetDraft > 0) {
            List<Map<String, Object>> owns = jdbc.queryForList(
                    "SELECT year, dimension_id FROM uf_assess_agent_draft WHERE id = ? AND session_id = ? AND status = 'READY'",
                    presetDraft, sessionId);
            if (owns.isEmpty()) {
                update(runId, S_FAILED, outcomeJson, null, "deliver 引用的 draftId 无效或不属于本批次会话", null, null);
                broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", "draftId 归属校验失败"));
                return;
            }
            update(runId, S_READY, outcomeJson, null, null, presetDraft, null);
            Map<String, Object> own = owns.get(0);
            jdbc.update("UPDATE uf_assess_import_run SET year=?, dimension_id=? WHERE id=?",
                    own.get("year"), own.get("dimension_id"), runId);
            broadcast(runId, Map.of("type", "run", "status", S_READY, "draftId", presetDraft));
            return;
        }
        // 无 draftId：从 payload 或顶层字段组稿；sessionId 一律以 run 记录为准（模型回传不可信）
        ObjectNode payloadNode;
        if (outcome.path("payload").isObject()) {
            payloadNode = (ObjectNode) outcome.path("payload").deepCopy();
        } else {
            payloadNode = mapper.createObjectNode();
            payloadNode.set("rows", outcome.path("rows"));
            if (outcome.hasNonNull("year")) payloadNode.set("year", outcome.get("year"));
            if (outcome.hasNonNull("dimensionId")) payloadNode.set("dimensionId", outcome.get("dimensionId"));
        }
        payloadNode.put("sessionId", sessionId);
        String payload = payloadNode.toString();
        Map<String, Object> draftRes;
        try {
            draftRes = bridge.submitDraft(internalToken, payload, false);
        } catch (BusinessException e) {
            update(runId, S_FAILED, outcomeJson, null, "deliver 未过后端复验：" + e.getMessage(), null, null);
            broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", "deliver 复验失败：" + e.getMessage()));
            return;
        }
        if (!Boolean.TRUE.equals(draftRes.get("ok")) || draftRes.get("draftId") == null) {
            if (draftRes.get("reconciliation") != null) {
                // 行对账不齐：不直接判死——有轮次则合成 NEED_INPUT 让用户表态，耗尽则带标记强制成稿
                draftRes = reconcileOrForceDraft(runId, run, outcomeJson, payloadNode, draftRes);
                if (draftRes == null) {
                    return;   // 已置 NEED_INPUT，等待用户回答
                }
            } else {
                String blocks = String.valueOf(draftRes.get("lines"));
                update(runId, S_FAILED, outcomeJson, null, "deliver 含阻断行，拒收：" + abbrev(blocks), null, null);
                broadcast(runId, Map.of("type", "run", "status", S_FAILED,
                        "reason", "数据未完全满足接口要求（模型谎报已对齐，已拒收）", "lines", draftRes.get("lines")));
                return;
            }
        }
        long draftId = longOf(draftRes.get("draftId"));
        Map<String, Object> draft = jdbc.queryForMap(
                "SELECT year, dimension_id FROM uf_assess_agent_draft WHERE id = ?", draftId);
        update(runId, S_READY, outcomeJson, null, null, draftId, null);
        jdbc.update("UPDATE uf_assess_import_run SET year=?, dimension_id=? WHERE id=?",
                draft.get("year"), draft.get("dimension_id"), runId);
        broadcast(runId, Map.of("type", "run", "status", S_READY, "draftId", draftId));
    }

    /**
     * deliver 行对账不齐的处理：轮次内 → 合成 NEED_INPUT 问题请用户表态（忽略 or 补充去向），
     * 返回 null 等待回答；轮次耗尽 → 带 unreconciled 标记强制成稿（对账豁免），返回新 draftRes 由调用方走 READY 收尾。
     */
    private Map<String, Object> reconcileOrForceDraft(long runId, Map<String, Object> run, String outcomeJson,
                                                      ObjectNode payloadNode, Map<String, Object> draftRes) {
        String recon = String.valueOf(draftRes.get("reconciliation"));
        int round = intOf(run.get("round_count")) + 1;
        if (round <= MAX_ASK_ROUNDS) {
            ArrayNode transcript = parseArray(
                    run.get("transcript_json") == null ? null : String.valueOf(run.get("transcript_json")));
            ObjectNode entry = transcript.addObject();
            entry.put("round", round);
            ArrayNode questions = entry.putArray("questions");
            ObjectNode q = questions.addObject();
            q.put("id", "reconcile-" + round);
            q.put("type", "choice");
            q.put("question", "交付被后端行对账拦下。" + recon);
            q.putArray("options")
                    .add("确认忽略未交代的行（不导入，原因记为用户确认忽略）")
                    .add("我来补充说明这些行的去向");
            entry.put("note", round == MAX_ASK_ROUNDS ? "FINAL_ROUND" : "");
            // outcome_json 存合成本身的问题（前端提问卡只认 outcome_json.questions）
            ObjectNode outcomeForUi = mapper.createObjectNode();
            outcomeForUi.put("kind", "reconcile");
            outcomeForUi.set("questions", questions);
            update(runId, S_NEED_INPUT, outcomeForUi.toString(), transcript.toString(), null, null, round);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "run");
            ev.put("status", S_NEED_INPUT);
            ev.put("round", round);
            ev.put("maxRounds", MAX_ASK_ROUNDS);
            ev.put("questions", questions);
            ev.put("reconciliation", recon);
            broadcast(runId, ev);
            return null;
        }
        payloadNode.put("unreconciled", true);
        Map<String, Object> forced = bridge.submitDraft(internalToken, payloadNode.toString(), false);
        if (Boolean.TRUE.equals(forced.get("ok")) && forced.get("draftId") != null) {
            return forced;
        }
        String reason = "行对账未过且无法强制成稿：" + String.valueOf(forced.get("lines"));
        update(runId, S_FAILED, outcomeJson, null, abbrev(reason), null, null);
        broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", abbrev(reason)));
        return null;
    }

    /** EXHAUSTED/FAILED 出口：chat（同会话转人工）/ manual（转表单）/ abandon（放弃） */
    public Map<String, Object> exitRun(long runId, String to) {
        Map<String, Object> run = requireOwnedRun(runId);
        if (!List.of(S_EXHAUSTED, S_FAILED, S_NEED_INPUT).contains((String) run.get("status"))) {
            throw new BusinessException(409, "当前状态无出口可选：" + run.get("status"));
        }
        String status = switch (to) {
            case "chat" -> S_CHAT_ESC;
            case "manual" -> S_MANUAL;
            case "abandon" -> S_REJECTED;
            default -> throw new BusinessException(400, "未知出口：" + to);
        };
        update(runId, status, null, null, "abandon".equals(to) ? "用户放弃本批" : null, null, null);
        return snapshot(runId);
    }

    /** 前端 commitDraft 成功后收尾回执 */
    public void completeRun(long runId, String resultJson) {
        Map<String, Object> run = requireRun(runId);
        if (!S_READY.equals(run.get("status"))) {
            throw new BusinessException(409, "仅 READY 可标记完成");
        }
        update(runId, S_DONE, null, null, null, null, null);
        jdbc.update("UPDATE uf_assess_import_run SET result_json=? WHERE id=?", resultJson, runId);
        broadcast(runId, Map.of("type", "run", "status", S_DONE));
    }

    /** 排队起轮：等待该会话上一轮 CLI 进程退出（最多 120 秒），再做本轮 */
    private void enqueueRound(long runId, String sessionId, String rules, String transcriptJson, int askCount) {
        dispatcher.submit(() -> {
            long t0 = System.currentTimeMillis();
            while (sessionService.isTurnRunning(sessionId)) {
                if (System.currentTimeMillis() - t0 > 120_000) {
                    update(runId, S_FAILED, null, null, "上一轮未在时限内结束，请重试", null, null);
                    broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", "上一轮未在时限内结束"));
                    return;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            beginRound(runId, sessionId, rules, transcriptJson, askCount);
        });
    }

    /** 收编模型绕过收场直接 deliver 生成的稿（会话与 run 一比一，归属天然明确） */
    private boolean adoptStrayDraft(long runId) {
        Map<String, Object> run = requireRun(runId);
        List<Map<String, Object>> drafts = jdbc.queryForList(
                "SELECT TOP 1 id, year, dimension_id FROM uf_assess_agent_draft" +
                " WHERE session_id = ? AND status = 'READY' ORDER BY id DESC",
                String.valueOf(run.get("session_id")));
        if (drafts.isEmpty() || run.get("draft_id") != null) {
            return false;
        }
        Map<String, Object> d = drafts.get(0);
        update(runId, S_READY, null, null, null, longOf(d.get("id")), null);
        jdbc.update("UPDATE uf_assess_import_run SET year=?, dimension_id=? WHERE id=?",
                d.get("year"), d.get("dimension_id"), runId);
        broadcast(runId, Map.of("type", "run", "status", S_READY, "draftId", d.get("id")));
        log.info("run {} 收编 stray draft {}", runId, d.get("id"));
        return true;
    }

    private void updateToNeedInput(long runId) {
        Map<String, Object> run = requireRun(runId);
        JsonNode o;
        try {
            o = mapper.readTree(String.valueOf(run.get("outcome_json")));
        } catch (Exception e) {
            return;
        }
        update(runId, S_NEED_INPUT, null, null, null, null, null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "run");
        ev.put("status", S_NEED_INPUT);
        ev.put("round", run.get("round_count"));
        ev.put("maxRounds", MAX_ASK_ROUNDS);
        ev.put("questions", o.path("questions"));
        broadcast(runId, ev);
    }

    // ==================== 回合驱动 ====================

    private void beginRound(long runId, String sessionId, String rules, String transcriptJson, int askCount) {
        outcomeSeen.remove(runId);
        Map<String, Object> runRow = requireRun(runId);
        Object dim = runRow.get("dimension_id");
        String dimText = dim == null ? "由文件人员页面判定"
                : dim + "（操作人当前维度，强制——所有查询与写入都限定在此维度，禁止切换或借用其他维度）";
        String task = taskTemplate
                .replace("{{round}}", String.valueOf(askCount + 1))
                .replace("{{maxRounds}}", String.valueOf(MAX_ASK_ROUNDS))
                .replace("{{sessionId}}", sessionId)
                .replace("{{dimensionId}}", dimText)
                .replace("{{filePath}}", String.valueOf(runRow.get("file_path")))
                .replace("{{yearHint}}", "未指定则按 get_periods 判定")
                .replace("{{rules}}", rules == null || rules.isBlank() ? "（无）" : rules)
                .replace("{{transcript}}", renderTranscript(transcriptJson));
        Consumer<Map<String, Object>> listener = ev -> {
            broadcast(runId, withType(ev, "turn"));
            if (!"done".equals(ev.get("type")) && !"error".equals(ev.get("type"))) {
                return;
            }
            String statusNow = String.valueOf(requireRun(runId).get("status"));
            if (!S_PARSING.equals(statusNow)) {
                return;  // 收场已迁移状态（deliver/reject/ask→pending 由下面处理）
            }
            if (Boolean.TRUE.equals(outcomeSeen.get(runId))) {
                // ask：状态暂存 PARSING，done 后落 NEED_INPUT（回合中模型还要收尾文本）
                if (S_NEED_INPUT.equals(pendingStatus.remove(runId))) {
                    updateToNeedInput(runId);
                }
                return;
            }
            // 模型绕过收场：先收编 stray draft，再兜住 ask-pending，最后才判失败
            if (adoptStrayDraft(runId)) {
                return;
            }
            if (S_NEED_INPUT.equals(pendingStatus.remove(runId))) {
                updateToNeedInput(runId);
                return;
            }
            update(runId, S_FAILED, null, null, "本轮结束但未收到 submit_outcome 收场（可重试解析）", null, null);
            broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", "回合无收场，可重试"));
        };
                try {
            sessionService.startTurnAs(sessionId, task, List.of(), listener, systemPrompt,
                    longOf(requireRun(runId).get("created_by")));
        } catch (BusinessException e) {
            update(runId, S_FAILED, null, null, e.getMessage(), null, null);
            broadcast(runId, Map.of("type", "run", "status", S_FAILED, "reason", e.getMessage()));
        }
    }

    private String renderTranscript(String transcriptJson) {
        if (transcriptJson == null || transcriptJson.isBlank() || "[]".equals(transcriptJson)) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : parseArray(transcriptJson)) {
            if (entry.has("questions")) {
                for (JsonNode q : entry.path("questions")) {
                    sb.append("问[").append(q.path("id").asText()).append("]：")
                            .append(q.path("question").asText()).append('\n');
                }
            }
            if (entry.has("answer")) {
                for (JsonNode a : entry.path("answer")) {
                    sb.append("答：").append(a.path("questionId").asText()).append(" = ")
                            .append(a.path("value").asText()).append('\n');
                }
            }
        }
        return sb.isEmpty() ? "（无）" : sb.toString();
    }

    // ==================== 查询/事件/巡检 ====================

    public Map<String, Object> snapshot(long runId) {
        Map<String, Object> run = requireRun(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.get("id"));
        out.put("status", run.get("status"));
        out.put("sessionId", run.get("session_id"));
        out.put("fileName", run.get("file_name"));
        out.put("year", run.get("year"));
        out.put("dimensionId", run.get("dimension_id"));
        out.put("round", run.get("round_count"));
        out.put("maxRounds", MAX_ASK_ROUNDS);
        out.put("draftId", run.get("draft_id"));
        out.put("failReason", run.get("fail_reason"));
        out.put("promptVersion", run.get("prompt_version"));
        out.put("transcript", run.get("transcript_json"));
        out.put("outcome", run.get("outcome_json"));
        out.put("result", run.get("result_json"));
        return out;
    }

    public List<Map<String, Object>> history() {
        LoginUser user = requireUser();
        return jdbc.queryForList(
                "SELECT TOP 30 id, file_name, status, year, dimension_id, round_count, draft_id, fail_reason," +
                "       CONVERT(VARCHAR(19), created_at, 120) AS created_at, created_by, prompt_version" +
                " FROM uf_assess_import_run WHERE company_id = ? AND created_at > DATEADD(day, -30, SYSDATETIME())" +
                " ORDER BY id DESC", companyId).stream()
                .filter(r -> user.resourceId().equals(longOf(r.get("created_by"))) || isAdmin(user))
                .toList();
    }

    public SseEmitter events(long runId) {
        requireRun(runId);
        SseEmitter emitter = new SseEmitter(0L);
        List<Consumer<Map<String, Object>>> list =
                runListeners.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        Consumer<Map<String, Object>> l = new Consumer<>() {
            @Override public void accept(Map<String, Object> ev) {
                try {
                    emitter.send(SseEmitter.event().data(mapper.writeValueAsString(ev)));
                    if ("run".equals(ev.get("type"))
                            && List.of(S_DONE, S_REJECTED, S_STALE).contains(String.valueOf(ev.get("status")))) {
                        emitter.complete();
                    }
                } catch (Exception e) {
                    list.remove(this);
                }
            }
        };
        emitter.onCompletion(() -> list.remove(l));
        emitter.onTimeout(() -> list.remove(l));
        list.add(l);
        return emitter;
    }

    /** 每分钟：NEED_INPUT/DRAFT 超时 STALE；PARSING 但会话已死（重启）→ STALE */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        try {
            jdbc.update("UPDATE uf_assess_import_run SET status='" + S_STALE +
                            "', fail_reason='超时未继续', updated_at=SYSDATETIME()" +
                            " WHERE status IN ('" + S_NEED_INPUT + "','" + S_DRAFT + "') AND updated_at < DATEADD(minute, ?, SYSDATETIME())",
                    -STALE_MINUTES);
            List<Map<String, Object>> parsing = jdbc.queryForList(
                    "SELECT id, session_id FROM uf_assess_import_run WHERE status='" + S_PARSING + "'");
            for (Map<String, Object> r : parsing) {
                String sid = (String) r.get("session_id");
                if (!sessionService.isTurnRunning(sid) && !sessionService.isLiveSession(sid)) {
                    long id = longOf(r.get("id"));
                    update(id, S_STALE, null, null, "服务或会话已重启，请重新发起", null, null);
                    broadcast(id, Map.of("type", "run", "status", S_STALE));
                }
            }
        } catch (Exception e) {
            log.warn("import run 巡检异常: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    private void update(long runId, String status, String outcomeJson, String transcriptJson,
                        String failReason, Long draftId, Integer round) {
        jdbc.update("UPDATE uf_assess_import_run SET status=?," +
                " outcome_json=COALESCE(?, outcome_json), transcript_json=COALESCE(?, transcript_json)," +
                " fail_reason=CASE WHEN ? IS NULL THEN NULL ELSE ? END," +
                " draft_id=COALESCE(?, draft_id), round_count=COALESCE(?, round_count)," +
                " updated_at=SYSDATETIME() WHERE id=?",
                status, outcomeJson, transcriptJson, failReason, failReason, draftId, round, runId);
    }

    /** 属主门：仅创建者或管理员可推进批次（review #2 同类越权面） */
    private Map<String, Object> requireOwnedRun(long runId) {
        Map<String, Object> run = requireRun(runId);
        LoginUser user = requireUser();
        long owner = longOf(run.get("created_by"));
        if (owner != user.resourceId() && !bridge.currentUserIsAdmin()) {
            throw new BusinessException(403, "无权操作他人批次");
        }
        return run;
    }

    private Map<String, Object> requireRun(long runId) {
        return jdbc.queryForList("SELECT * FROM uf_assess_import_run WHERE id = ? AND company_id = ?",
                runId, companyId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(404, "批次不存在"));
    }

    private void broadcast(long runId, Map<String, Object> ev) {
        for (Consumer<Map<String, Object>> l : runListeners.getOrDefault(runId, List.of())) {
            try {
                l.accept(ev);
            } catch (Exception ignored) {
                // 单消费者失败不影响广播
            }
        }
    }

    private static Map<String, Object> withType(Map<String, Object> ev, String type) {
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("type", type);
        wrapped.putAll(ev);
        return wrapped;
    }

    private ArrayNode parseArray(String json) {
        try {
            JsonNode n = mapper.readTree(json == null || json.isBlank() ? "[]" : json);
            return n.isArray() ? (ArrayNode) n : mapper.createArrayNode();
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private static String suffix(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase();
    }

    private static String abbrev(String s) {
        return s != null && s.length() > 460 ? s.substring(0, 460) : s;
    }

    private static int intOf(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static long longOf(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private boolean isAdmin(LoginUser user) {
        try {
            return bridge.currentUserIsAdmin();
        } catch (Exception e) {
            return false;
        }
    }

    private static LoginUser requireUser() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(401, "需要登录身份");
        }
        return user;
    }
}
