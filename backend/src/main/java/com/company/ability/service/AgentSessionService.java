package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能导入会话运行时：测评后端自派生 claude CLI（模式对齐 ai-chat-app，但完全独立、不复用）。
 *
 * 会话模型（方案 §3/§6 落地）：
 * - 每次进页面 createSession = 全新会话，零上下文；轮次内 --resume 保持记忆；
 * - 闲置 30 分钟 / 绝对上限 2 小时 → CLOSED，再发消息直接 410，写入钥匙（draft）由
 *   AgentBridgeService 同规则作废；
 * - 全部消息 JSONL 落盘（cp/sessions/），即审计留痕；
 * - 下行 SSE（fetch 流读取），上行普通 POST；浏览器不再接触任何 zd-code 端口与 cookie。
 */
@Slf4j
@Service
public class AgentSessionService {

    static final int ERR_SESSION_GONE = 41001;

    /** 会话状态 */
    enum Status { ACTIVE, CLOSED }

    private static final class Session {
        final String id;
        volatile String cliSessionId;      // claude --resume 用
        volatile LocalDateTime createdAt = LocalDateTime.now();
        volatile LocalDateTime lastActiveAt = LocalDateTime.now();
        volatile LocalDateTime turnStartedAt;
        volatile String pendingSystemPrompt;   // 本回合 --append-system-prompt 内容（run/聊天各自的行为条款）
        volatile Status status = Status.ACTIVE;
        final AtomicBoolean running = new AtomicBoolean(false);
        volatile Process process;
        final Long ownerResourceId;
        /** 回合事件多播：聊天 SSE 与导入 run 各挂各的监听器，共用一条 spawn */
        final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Map<String, Object>>>
                turnListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        Session(String id, Long ownerResourceId) {
            this.id = id;
            this.ownerResourceId = ownerResourceId;
        }
    }

    private final ObjectMapper mapper;
    private final Path workspace;
    private final Path sessionsDir;
    private final Path uploadsDir;
    private final String claudeBin;
    private final int idleMinutes;
    private final int maxHours;
    private final int maxActive;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService pumpPool = Executors.newCachedThreadPool();
    private final java.util.concurrent.ScheduledExecutorService housekeeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-housekeeper");
                t.setDaemon(true);
                return t;
            });

    /** 定时巡检（review #9）：闲置/超时会话主动回收（杀挂死进程）；90 天文件清理（uploads+sessions） */
    @jakarta.annotation.PostConstruct
    void startHousekeeper() {
        housekeeper.scheduleWithFixedDelay(this::housekeeping, 5, 5, TimeUnit.MINUTES);
    }

    void housekeeping() {
        try {
            sessions.values().forEach(this::reap);
            cleanOldFiles(uploadsDir);
            cleanOldFiles(sessionsDir);
        } catch (Exception e) {
            log.warn("agent 定时巡检异常: {}", e.getMessage());
        }
    }

    private void cleanOldFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant()
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                                    .isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 单次清理失败下次再试
                        }
                    });
        } catch (IOException e) {
            log.warn("agent 文件清理遍历失败 {}: {}", root, e.getMessage());
        }
    }

    private static final String ALLOWED_TOOLS = String.join(",",
            "mcp__cp__lookup_person", "mcp__cp__get_periods", "mcp__cp__get_non_flow_elements",
            "mcp__cp__get_existing_raters", "mcp__cp__check_period_open", "mcp__cp__parse_import_file",
            "mcp__cp__validate_draft", "mcp__cp__deliver", "mcp__cp__submit_outcome");

    private static final String DISALLOWED_TOOLS = String.join(",",
            "Bash", "Read", "Write", "Edit", "MultiEdit", "NotebookEdit", "Glob", "Grep",
            "WebFetch", "WebSearch", "Task", "Monitor", "mcp__mssql-ecology__*");

    public AgentSessionService(ObjectMapper mapper,
                               @Value("${agent.workspace-path:/home/wgd/ws/cp}") String workspacePath,
                               @Value("${agent.claude-bin:/home/wgd/bin/claude}") String claudeBin,
                               @Value("${agent.draft-idle-minutes:30}") int idleMinutes,
                               @Value("${agent.draft-max-hours:2}") int maxHours,
                               @Value("${agent.max-active-sessions:6}") int maxActive) {
        this.mapper = mapper;
        this.workspace = Path.of(workspacePath);
        this.sessionsDir = workspace.resolve("sessions");
        this.uploadsDir = workspace.resolve("uploads");
        this.claudeBin = claudeBin;
        this.idleMinutes = idleMinutes;
        this.maxHours = maxHours;
        this.maxActive = maxActive;
        try {
            Files.createDirectories(sessionsDir);
            Files.createDirectories(uploadsDir);
        } catch (IOException e) {
            throw new IllegalStateException("agent 工作目录初始化失败: " + e.getMessage(), e);
        }
    }

    // ==================== 会话生命周期 ====================

    public Map<String, Object> createSession() {
        LoginUser user = requireUser();
        long active = sessions.values().stream().filter(s -> s.status == Status.ACTIVE).count();
        if (active >= maxActive) {
            throw new BusinessException(503, "当前导入会话已满（" + maxActive + "），请稍后再试");
        }
        reapIdle();
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(id, user.resourceId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", id);
        return out;
    }

    /** 发消息 → SSE 流式回推。一轮一个 claude 进程（--resume 续上下文），prompt 走 stdin。 */
    public SseEmitter sendMessage(String sessionId, String content, List<String> files) {
        return sendMessage(sessionId, content, files, null);
    }

    public SseEmitter sendMessage(String sessionId, String content, List<String> files, String systemPrompt) {
        String prompt = systemPrompt == null ? chatSystemPrompt : systemPrompt;
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(15));
        startTurn(sessionId, content, files, ev -> {
            send(emitter, ev);
            if ("done".equals(ev.get("type")) || "error".equals(ev.get("type"))) {
                emitter.complete();
            }
        }, prompt);
        return emitter;
    }

    /** 启动一个回合（供聊天 SSE 与导入 run 共用）。listener 收到本回合全部事件后自动摘除。 */
    public void startTurn(String sessionId, String content, List<String> files,
                          java.util.function.Consumer<Map<String, Object>> listener) {
        startTurn(sessionId, content, files, listener, null);
    }

    public void startTurn(String sessionId, String content, List<String> files,
                          java.util.function.Consumer<Map<String, Object>> listener, String systemPrompt) {
        startTurnAs(sessionId, content, files, listener, systemPrompt, requireUser().resourceId());
    }

    /** 供导入 run 派发线程使用：身份显式传入 */
    public void startTurnAs(String sessionId, String content, List<String> files,
                            java.util.function.Consumer<Map<String, Object>> listener,
                            String systemPrompt, Long actingResourceId) {
        Session s = requireSessionAs(sessionId, actingResourceId);
        if (!s.running.compareAndSet(false, true)) {
            throw new BusinessException(409, "上一轮仍在生成，请先停止或等待完成");
        }
        String fullPrompt = withFileHint(content, files);
        appendHistory(s, "user", fullPrompt);
        s.lastActiveAt = LocalDateTime.now();
        s.pendingSystemPrompt = systemPrompt;
        s.turnListeners.add(listener);
        pumpPool.submit(() -> runTurn(s, fullPrompt));
    }

    /** run 编排用：回合是否进行中 + 起点时间（超时判定） */
    public boolean isTurnRunning(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        return s != null && s.running.get();
    }

    public LocalDateTime turnStartedAt(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        return s == null ? null : s.turnStartedAt;
    }

    /** 停止本轮生成：销毁进程；stdout 到 EOF 后 runTurn 自然收尾发 done */
    public void stopSession(String sessionId) {
        Session s = requireSession(sessionId);
        Process p = s.process;
        if (p != null && p.isAlive()) {
            p.destroy();
            log.info("agent 会话 {} 本轮生成已手动停止", sessionId);
        }
    }

    public List<Map<String, Object>> history(String sessionId) {
        Session s = requireSession(sessionId);
        Path f = historyFile(s);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(mapper.readValue(line, Map.class));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(500, "读取历史失败");
        }
        return out;
    }

    /** 上传落 uploads/{sessionId}/，解析工具按会话校验归属（review #8：杜绝跨会话读他人原件） */
    public List<Map<String, Object>> upload(String sessionId, List<MultipartFile> files) {
        requireSession(sessionId);
        if (files == null || files.isEmpty()) {
            throw new BusinessException(400, "缺少文件");
        }
        Path dir = uploadsDir.resolve(sessionId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "file" : f.getOriginalFilename();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            if (!List.of(".xlsx", ".xls", ".csv").contains(ext.toLowerCase())) {
                throw new BusinessException(400, "仅支持 .xlsx / .xls / .csv，收到：" + name);
            }
            Path target = dir.resolve(UUID.randomUUID() + ext);
            try {
                Files.createDirectories(dir);
                f.transferTo(target.toFile());
            } catch (IOException e) {
                throw new BusinessException(500, "保存上传文件失败：" + e.getMessage());
            }
            out.add(Map.of("name", name, "path", target.toString()));
        }
        return out;
    }

    /** 活会话登记（review #2：submitDraft 必须验 sessionId 真实存在且未结束，防任意会话投毒） */
    public boolean isLiveSession(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null) {
            return false;
        }
        reap(s);
        return s.status == Status.ACTIVE;
    }

    // ==================== 内部 ====================

    private void runTurn(Session s, String prompt) {
        StringBuilder assistantText = new StringBuilder();
        s.turnStartedAt = LocalDateTime.now();
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCmd(s));
            pb.directory(new File(workspace.toString()));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            s.process = p;
            drainStderr(p, s.id);

            try (OutputStream stdin = p.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    forward(s, line, assistantText);
                }
            }
            int code = p.waitFor();
            if (code != 0 && assistantText.isEmpty()) {
                dispatch(s, event("error", Map.of("message", "claude 进程退出码 " + code)));
            }
            if (assistantText.length() > 0) {
                appendHistory(s, "assistant", assistantText.toString());
            }
            dispatch(s, event("done", Map.of("ok", code == 0)));
        } catch (Exception e) {
            log.warn("agent 轮次异常 session={}: {}", s.id, e.getMessage());
            dispatch(s, event("error", Map.of("message", String.valueOf(e.getMessage()))));
        } finally {
            s.turnListeners.clear();
            s.running.set(false);
            s.process = null;
            s.turnStartedAt = null;
            s.pendingSystemPrompt = null;
            s.lastActiveAt = LocalDateTime.now();
        }
    }

    /**
     * stderr 必须独立抽干：管道缓冲（约 64KB）写满会阻塞子进程，stdout 不再输出、
     * readLine 永不 EOF、waitFor 挂死（review P1#5）。内容限前 200 行进日志供排障。
     */
    private void drainStderr(Process p, String sessionId) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n++ < 200) {
                    if (!line.isBlank()) {
                        log.warn("[claude stderr {}] {}", sessionId.substring(0, 8), line);
                    }
                }
            } catch (IOException ignored) {
                // 进程销毁后读管道报错属正常收尾
            }
        }, "claude-stderr-" + sessionId.substring(0, 8));
        t.setDaemon(true);
        t.start();
    }

    private List<String> buildCmd(Session s) {
        List<String> cmd = new ArrayList<>(List.of(
                claudeBin, "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", "default",
                "--strict-mcp-config",
                "--mcp-config", workspace.resolve(".mcp.json").toString(),
                "--allowedTools", ALLOWED_TOOLS,
                "--disallowedTools", DISALLOWED_TOOLS));
        if (s.cliSessionId != null) {
            cmd.addAll(List.of("--resume", s.cliSessionId));
        }
        if (s.pendingSystemPrompt != null && !s.pendingSystemPrompt.isBlank()) {
            cmd.addAll(List.of("--append-system-prompt", s.pendingSystemPrompt));
        }
        return cmd;
    }

    /**
     * CLI stream-json 帧 → 前端事件：
     * system/init 取 cli session id；assistant 文本块转 delta、tool_use 转 tool（仅公开名）；
     * result 兜底定稿；其余（thinking/user/tool_result 原文）不外发，防内部信息泄漏。
     */
    private void forward(Session s, String line, StringBuilder assistantText) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (Exception e) {
            return;
        }
        String type = msg.path("type").asText();
        if ("system".equals(type) && "init".equals(msg.path("subtype").asText())) {
            String cliId = msg.path("session_id").asText(null);
            if (cliId != null) {
                s.cliSessionId = cliId;
            }
            return;
        }
        if ("assistant".equals(type)) {
            for (JsonNode block : msg.path("message").path("content")) {
                String b = block.path("type").asText();
                if ("text".equals(b)) {
                    String text = block.path("text").asText("");
                    assistantText.append(text);
                    dispatch(s, event("delta", Map.of("text", text)));
                } else if ("tool_use".equals(b)) {
                    dispatch(s, event("tool", Map.of("name", shortTool(block.path("name").asText("")))));
                }
            }
            return;
        }
        if ("result".equals(type) && msg.path("is_error").asBoolean(false)) {
            dispatch(s, event("error", Map.of("message",
                    msg.path("result").asText(msg.path("subtype").asText("agent 执行出错")))));
        }
    }

    /** 聊天模式行为条款（模板由测评后端注入，工作空间 CLAUDE.md 仅为角色锚） */
    private String chatSystemPrompt = "";

    @jakarta.annotation.PostConstruct
    void loadChatPrompt() throws java.io.IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream("agent/chat-prompt-system.md")) {
            if (in != null) {
                chatSystemPrompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    /** 回合事件多播：监听器异常互不影响 */
    private void dispatch(Session s, Map<String, Object> ev) {
        for (var listener : s.turnListeners) {
            try {
                listener.accept(ev);
            } catch (Exception ignored) {
                // 单个消费者失败（如前端断开）不影响其他监听器与回合本身
            }
        }
    }

    private static String shortTool(String name) {
        String tail = name.contains("__") ? name.substring(name.lastIndexOf("__") + 2) : name;
        Map<String, String> zh = Map.of(
                "lookup_person", "查员工", "get_periods", "查年度页面",
                "get_non_flow_elements", "查非流程要素", "get_existing_raters", "查评价人",
                "check_period_open", "查年度状态", "parse_import_file", "解析文件",
                "validate_draft", "整稿校验", "deliver", "交付整理稿", "submit_outcome", "流水线收场");
        return zh.getOrDefault(tail, tail);
    }

    private String withFileHint(String content, List<String> files) {
        if (files == null || files.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder(content == null ? "" : content);
        sb.append("\n\n---\n用户上传的文件路径：");
        for (String f : files) {
            sb.append("\n- ").append(f);
        }
        sb.append("\n请调用 parse_import_file 工具解析上述文件。\n---");
        return sb.toString();
    }

    private void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private static Map<String, Object> event(String type, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.putAll(data);
        return e;
    }

    private Session requireSession(String sessionId) {
        return requireSessionAs(sessionId, requireUser().resourceId());
    }

    /** 显式属主校验版（run 派发线程无 HTTP 上下文，身份由批次记录携带） */
    private Session requireSessionAs(String sessionId, Long actingResourceId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null) {
            throw new BusinessException(404, "会话不存在（服务重启或从未创建），请重新进入页面");
        }
        // 会话属主门：别人的 sessionId 不可发消息/看历史/停止
        if (s.ownerResourceId != null && !s.ownerResourceId.equals(actingResourceId)) {
            throw new BusinessException(403, "无权操作他人会话");
        }
        reap(s);
        if (s.status == Status.CLOSED) {
            throw new BusinessException(ERR_SESSION_GONE, "会话已结束（闲置超时或超过时长上限），请新建会话");
        }
        return s;
    }

    private void reapIdle() {
        sessions.values().forEach(this::reap);
    }

    /** 闲置 30min / 绝对 2h → CLOSED（联动语义：其 draft 由 AgentBridgeService 同规则作废） */
    private void reap(Session s) {
        if (s.status == Status.ACTIVE) {
            LocalDateTime now = LocalDateTime.now();
            boolean idle = now.isAfter(s.lastActiveAt.plusMinutes(idleMinutes));
            boolean expired = now.isAfter(s.createdAt.plusHours(maxHours));
            if (idle || expired) {
                s.status = Status.CLOSED;
                Process p = s.process;
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
                log.info("agent 会话回收 {} ({})", s.id, idle ? "闲置超时" : "超过时长上限");
            }
        }
    }

    private void appendHistory(Session s, String role, String content) {
        Path f = historyFile(s);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().toString());
        row.put("role", role);
        row.put("content", content);
        try {
            Files.createDirectories(f.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(row));
                w.write("\n");
            }
        } catch (IOException e) {
            log.warn("历史留痕写入失败 session={}: {}", s.id, e.getMessage());
        }
    }

    private Path historyFile(Session s) {
        return sessionsDir.resolve(s.id + ".jsonl");
    }

    private LoginUser requireUser() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(401, "会话需要登录身份");
        }
        return user;
    }
}
