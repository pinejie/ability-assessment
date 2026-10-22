package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.ExternalScoreVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能导入二期：测评后端 ↔ zd-code（ai-chat-app）桥 + Agent 交付稿（draft）管理。
 *
 * 职责边界（方案 10 §5）：
 * - cp_server（agent）只能用内部令牌调 submitDraft：校验+落 draft，绝不写业务表；
 * - 写入唯一路径 = 前端点击 → commitDraft → 复用一期 ExternalScoreService（存储过程）；
 * - 会话代理：createSession/stop/history/upload 转发 zd-code（服务端持 zd_auth Cookie，浏览器不碰 APP_TOKEN）。
 */
@Slf4j
@Service
public class AgentBridgeService {

    static final int ERR_DRAFT_EXPIRED = 40301;
    static final int ERR_ZDCODE = 50210;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExternalScoreService externalScoreService;
    // HTTP/1.1：Java HttpClient 默认 HTTP/2 先升级，uvicorn(h11) 会回 400 Invalid HTTP request
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final boolean enabled;
    private final String zdBaseUrl;
    private final String zdCookie;
    private final String workspacePath;
    private final String internalToken;
    private final String wsPublicUrl;
    private final long companyId;
    private final int idleMinutes;
    private final int maxHours;

    private volatile String cachedWorkspaceId;
    private volatile long workspaceIdFetchedAt;

    public AgentBridgeService(JdbcTemplate jdbc, ObjectMapper mapper,
                              ExternalScoreService externalScoreService,
                              @Value("${agent.enabled:false}") boolean enabled,
                              @Value("${agent.zdcode-base-url:http://127.0.0.1:8000}") String zdBaseUrl,
                              @Value("${agent.zdcode-app-token:}") String appToken,
                              @Value("${agent.workspace-path:/home/wgd/ws/cp}") String workspacePath,
                              @Value("${agent.internal-token:}") String internalToken,
                              @Value("${agent.ws-public-url:}") String wsPublicUrl,
                              @Value("${assess.company-id:1}") long companyId,
                              @Value("${agent.draft-idle-minutes:30}") int idleMinutes,
                              @Value("${agent.draft-max-hours:2}") int maxHours) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.externalScoreService = externalScoreService;
        this.enabled = enabled;
        this.zdBaseUrl = zdBaseUrl.replaceAll("/+$", "");
        this.zdCookie = appToken.isBlank() ? "" : "zd_auth=" + appToken;
        this.workspacePath = workspacePath;
        this.internalToken = internalToken;
        this.wsPublicUrl = wsPublicUrl;
        this.companyId = companyId;
        this.idleMinutes = idleMinutes;
        this.maxHours = maxHours;
    }

    // ==================== 会话代理 ====================

    /** 前端页配置：WS 直连地址（REST 全走本代理） */
    public Map<String, Object> clientConfig() {
        requireEnabled();
        String ws = wsPublicUrl.isBlank()
                ? "ws://" + URI.create(zdBaseUrl).getHost() + ":8000"
                : wsPublicUrl;
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("wsUrl", ws);
        cfg.put("workspacePath", workspacePath);
        return cfg;
    }

    /** 新建 zd-code 会话（固定绑定 cp 工作空间，目录快照写入会话索引） */
    public Map<String, Object> createSession() {
        requireEnabled();
        ObjectNode body = mapper.createObjectNode();
        body.put("workspace_id", resolveWorkspaceId());
        JsonNode r = zdCall("POST", "/api/sessions", body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", r.path("session_id").asText());
        out.put("title", r.path("title").asText());
        return out;
    }

    public JsonNode history(String sessionId) {
        requireEnabled();
        return zdCall("GET", "/api/sessions/" + safeId(sessionId) + "/history", null);
    }

    public JsonNode stopSession(String sessionId) {
        requireEnabled();
        return zdCall("POST", "/api/sessions/" + safeId(sessionId) + "/stop", null);
    }

    /** 附件上传转发：存到 zd-code 的 UPLOAD_DIR，返回路径给前端随消息 files 发送 */
    public JsonNode upload(List<MultipartFile> files) {
        requireEnabled();
        if (files == null || files.isEmpty()) {
            throw new BusinessException(400, "缺少文件");
        }
        String boundary = "----extImport" + System.nanoTime();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (MultipartFile f : files) {
                out.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files\"; filename=\""
                        + sanitizeName(f.getOriginalFilename()) + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(f.getBytes());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(zdBaseUrl + "/api/upload"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Cookie", zdCookie)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException(ERR_ZDCODE, "文件上传失败（zd-code " + resp.statusCode() + "）");
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ERR_ZDCODE, "文件上传异常：" + e.getMessage());
        }
    }

    private String resolveWorkspaceId() {
        long now = System.currentTimeMillis();
        String cached = cachedWorkspaceId;
        if (cached != null && now - workspaceIdFetchedAt < 60_000) {
            return cached;
        }
        JsonNode list = zdCall("GET", "/api/workspaces", null).path("items");
        for (JsonNode ws : list) {
            if (workspacePath.equals(ws.path("path").asText())) {
                cachedWorkspaceId = ws.path("id").asText();
                workspaceIdFetchedAt = now;
                return cachedWorkspaceId;
            }
        }
        throw new BusinessException(ERR_ZDCODE,
                "cp 工作空间未注册（期望路径 " + workspacePath + "，请在 zd-code 工作空间管理中添加）");
    }

    // ==================== draft：提交（cp_server 内部令牌） ====================

    /**
     * cp_server deliver/validate 入口。校验通过且非 validateOnly 时落 draft。
     * 维度歧义（同人同年度多维度页面）在入库前阻断，与前端"必须确认"的口径一致。
     */
    public Map<String, Object> submitDraft(String agentToken, String payloadJson, boolean validateOnly) {
        requireEnabled();
        if (internalToken.isBlank() || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                agentToken == null ? new byte[0] : agentToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "内部令牌无效");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new BusinessException(400, "载荷不是合法 JSON");
        }
        Resolution res = resolveDraft(payload);
        ExternalScoreVO.PreviewResult preview = withDimension(res.dimensionId,
                () -> externalScoreService.preview(res.request));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", mapper.convertValue(preview, Map.class).get("lines"));
        out.put("coverage", mapper.convertValue(preview, Map.class).get("coverage"));
        if (!preview.isCommitable()) {
            out.put("ok", false);
            out.put("ready", false);
            return out;
        }
        if (validateOnly) {
            out.put("ok", true);
            out.put("validateOnly", true);
            return out;
        }

        long draftId = insertDraft(res, payloadJson);
        out.put("ok", true);
        out.put("draftId", draftId);
        out.put("ready", true);
        out.put("expiresAt", LocalDateTime.now().plusMinutes(idleMinutes)
                .isBefore(LocalDateTime.now().plusHours(maxHours))
                ? LocalDateTime.now().plusMinutes(idleMinutes) : LocalDateTime.now().plusHours(maxHours));
        return out;
    }

    // ==================== draft：查询与写入（前端登录态） ====================

    /** 会话下最新稿列表（前端轮询=活跃心跳，刷新 last_seen） */
    public List<Map<String, Object>> listDrafts(String sessionId) {
        requireEnabled();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TOP 5 id, year, dimension_id, status, payload_json," +
                "       CONVERT(VARCHAR(19), ready_time, 120) AS ready_time" +
                " FROM uf_assess_agent_draft WHERE session_id = ? AND company_id = ? ORDER BY id DESC",
                safeId(sessionId), companyId);
        List<Map<String, Object>> out = new ArrayList<>();
        LoginUser user = UserContext.currentOrNull();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String status = (String) row.get("status");
            if ("READY".equals(status)) {
                if (isExpired(row)) {
                    jdbc.update("UPDATE uf_assess_agent_draft SET status='EXPIRED' WHERE id=? AND status='READY'", id);
                    status = "EXPIRED";
                } else if (user != null) {
                    jdbc.update("UPDATE uf_assess_agent_draft SET last_seen_time = SYSDATETIME() WHERE id = ?", id);
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("year", row.get("year"));
            item.put("dimensionId", row.get("dimension_id"));
            item.put("status", status);
            item.put("readyTime", row.get("ready_time"));
            try {
                item.put("payload", mapper.readTree(String.valueOf(row.get("payload_json"))));
            } catch (Exception e) {
                item.put("payload", null);
            }
            out.add(item);
        }
        return out;
    }

    /** 前端点击写入 = 确认 + 提交（原子）：过期/重复提交拒绝，数据变化被重校验挡回时保留 READY（方案 §6.3） */
    public Map<String, Object> commitDraft(long draftId) {
        requireEnabled();
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(401, "写入需要登录身份");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, session_id, year, dimension_id, status, payload_json, commit_time" +
                " FROM uf_assess_agent_draft WHERE id = ? AND company_id = ?", draftId, companyId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "交付稿不存在");
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        if ("COMMITTED".equals(status)) {
            throw new BusinessException(409, "该稿已提交，不能重复写入");
        }
        if ("EXPIRED".equals(status) || isExpired(row)) {
            jdbc.update("UPDATE uf_assess_agent_draft SET status='EXPIRED' WHERE id=? AND status='READY'", draftId);
            throw new BusinessException(ERR_DRAFT_EXPIRED, "会话或交付稿已过期，请在对话中重新达成协议");
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(String.valueOf(row.get("payload_json")));
        } catch (Exception e) {
            throw new BusinessException(400, "交付稿损坏");
        }
        long dimensionId = ((Number) row.get("dimension_id")).longValue();

        // 提交前重校验：期间钩子/流程可能改了底层数据（方案 §4 第 4 步）
        ExternalScoreVO.PreviewResult check = withDimension(dimensionId,
                () -> externalScoreService.preview(toImportRequest(payload, ((Number) row.get("year")).intValue())));
        if (!check.isCommitable()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            check.getLines().stream().filter(l -> "BLOCK".equals(l.getStatus())).forEach(l -> {
                Map<String, Object> m = new HashMap<>();
                m.put("lineNo", l.getLineNo());
                m.put("message", l.getMessage());
                blocks.add(m);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rejected", true);
            out.put("reason", "底层数据已变化，重校验未通过");
            out.put("blocks", blocks);
            return out;   // 保持 READY：回对话让 agent 针对变化重议
        }

        ExternalScoreVO.CommitResult result = withDimension(dimensionId,
                () -> externalScoreService.commit(toImportRequest(payload, ((Number) row.get("year")).intValue())));
        boolean anySuccess = result.getPersons().stream().anyMatch(ExternalScoreVO.CommitPersonResult::isSuccess);
        jdbc.update("UPDATE uf_assess_agent_draft SET status=?, commit_time=SYSDATETIME(), commit_by=?," +
                    " commit_result=? WHERE id = ?",
                anySuccess ? "COMMITTED" : "READY", user.resourceId(),
                summarize(result), draftId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rejected", false);
        out.put("result", mapper.convertValue(result, Map.class));
        return out;
    }

    // ==================== 内部 ====================

    private record Resolution(String sessionId, long dimensionId, ExternalScoreDTO.ImportRequest request) {
    }

    /** 载荷结构校验 + 维度解析（歧义/无页面在此阻断，不落库） */
    private Resolution resolveDraft(JsonNode payload) {
        String sessionId = payload.path("sessionId").asText("");
        if (!sessionId.matches("[0-9a-zA-Z_-]{1,64}")) {
            throw new BusinessException(400, "载荷缺少合法 sessionId（应由前端首条消息给出并原样带回）");
        }
        int year = payload.path("year").asInt(0);
        JsonNode rows = payload.path("rows");
        if (year <= 0 || !rows.isArray() || rows.isEmpty()) {
            throw new BusinessException(400, "载荷缺少 year 或 rows");
        }
        Long explicitDim = payload.hasNonNull("dimensionId") ? payload.get("dimensionId").asLong() : null;

        ExternalScoreDTO.ImportRequest req = toImportRequest(payload, year);
        List<ExternalScoreDTO.RatingRow> ratings = req.getRatings() == null ? List.of() : req.getRatings();
        List<ExternalScoreDTO.AnnualRow> annuals = req.getAnnuals() == null ? List.of() : req.getAnnuals();
        List<Long> resources = new ArrayList<>();
        ratings.forEach(r -> { if (!resources.contains(r.getResourceId())) resources.add(r.getResourceId()); });
        annuals.forEach(a -> { if (!resources.contains(a.getResourceId())) resources.add(a.getResourceId()); });
        if (resources.isEmpty()) {
            throw new BusinessException(400, "rows 中没有任何有效行");
        }

        String ph = String.join(",", resources.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(year);
        args.addAll(resources);
        List<Map<String, Object>> sheets = jdbc.queryForList(
                "SELECT s.resource_id, s.id, s.dimension_id FROM uf_assess_sheet s" +
                " JOIN uf_assess_period p ON p.id = s.period_id" +
                " WHERE s.company_id = ? AND p.year = ? AND s.resource_id IN (" + ph + ")",
                args.toArray());

        long resolved = -1;
        for (Long res : resources) {
            List<Map<String, Object>> mine = sheets.stream()
                    .filter(s -> ((Number) s.get("resource_id")).longValue() == res)
                    .filter(s -> explicitDim == null
                            || ((Number) s.get("dimension_id")).longValue() == explicitDim)
                    .toList();
            if (mine.isEmpty()) {
                throw new BusinessException(400, "被测人 " + res + " 在 " + year + " 年度无测评页面"
                        + (explicitDim != null ? "（指定维度下）" : ""));
            }
            long dim = ((Number) mine.get(0).get("dimension_id")).longValue();
            if (mine.size() > 1) {
                throw new BusinessException(400, "被测人 " + res + " 存在多维度页面，请在对话中明确维度后重新交付");
            }
            if (resolved == -1) {
                resolved = dim;
            } else if (resolved != dim) {
                throw new BusinessException(400, "同一稿不允许跨维度");
            }
        }
        return new Resolution(sessionId, resolved, req);
    }

    private ExternalScoreDTO.ImportRequest toImportRequest(JsonNode payload, int year) {
        ExternalScoreDTO.ImportRequest req = new ExternalScoreDTO.ImportRequest();
        req.setYear(year);
        List<ExternalScoreDTO.RatingRow> ratings = new ArrayList<>();
        List<ExternalScoreDTO.AnnualRow> annuals = new ArrayList<>();
        for (JsonNode row : payload.path("rows")) {
            String kind = row.path("kind").asText();
            long resourceId = row.path("resourceId").asLong(0);
            if (resourceId <= 0) {
                continue; // preview 会因"无页面"阻断该来源行；此处宽松保留结构
            }
            if ("RATING".equalsIgnoreCase(kind)) {
                ExternalScoreDTO.RatingRow r = new ExternalScoreDTO.RatingRow();
                r.setResourceId(resourceId);
                r.setElementId(row.path("elementId").asLong(0));
                r.setLevelNo(row.hasNonNull("levelNo") ? row.get("levelNo").asInt() : null);
                r.setOriginalScore(row.hasNonNull("originalScore")
                        ? new BigDecimal(row.get("originalScore").asText()) : null);
                ratings.add(r);
            } else if ("ANNUAL".equalsIgnoreCase(kind)) {
                ExternalScoreDTO.AnnualRow a = new ExternalScoreDTO.AnnualRow();
                a.setResourceId(resourceId);
                a.setAnnualScore(row.hasNonNull("annualScore")
                        ? new BigDecimal(row.get("annualScore").asText()) : null);
                annuals.add(a);
            }
        }
        req.setRatings(ratings);
        req.setAnnuals(annuals);
        return req;
    }

    private long insertDraft(Resolution res, String payloadJson) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uf_assess_agent_draft (session_id, company_id, year, dimension_id, payload_json)" +
                    " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, res.sessionId());
            ps.setLong(2, companyId);
            ps.setInt(3, res.request().getYear());
            ps.setLong(4, res.dimensionId());
            ps.setString(5, payloadJson);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new BusinessException(500, "交付稿落库失败");
        }
        return key.longValue();
    }

    private boolean isExpired(Map<String, Object> row) {
        // 惰性判定：READY 且（距今超过绝对上限 或 last_seen 超过闲置窗口）
        String ready = str(row, "ready_time");
        if (ready == null) {
            return false;
        }
        LocalDateTime readyAt = LocalDateTime.parse(ready.replace(' ', 'T'));
        if (LocalDateTime.now().isAfter(readyAt.plusHours(maxHours))) {
            return true;
        }
        String lastSeen = str(row, "last_seen_time");
        LocalDateTime seen = lastSeen == null ? readyAt : LocalDateTime.parse(lastSeen.replace(' ', 'T'));
        return LocalDateTime.now().isAfter(seen.plusMinutes(idleMinutes));
    }

    private <T> T withDimension(long dimensionId, java.util.function.Supplier<T> action) {
        Long previous = DimensionContext.get();
        DimensionContext.set(dimensionId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                DimensionContext.clear();
            } else {
                DimensionContext.set(previous);
            }
        }
    }

    private String summarize(ExternalScoreVO.CommitResult result) {
        StringBuilder sb = new StringBuilder();
        for (ExternalScoreVO.CommitPersonResult p : result.getPersons()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(p.getResourceId()).append('=').append(p.isSuccess() ? "OK" : "FAIL:" + p.getMessage());
        }
        String s = sb.toString();
        return s.length() > 480 ? s.substring(0, 480) : s;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(503, "智能导入功能未启用（agent.enabled=false）");
        }
    }

    private static String safeId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-zA-Z_-]{1,64}")) {
            throw new BusinessException(400, "会话 ID 不合法");
        }
        return sessionId;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return name.replaceAll("[\"\\\\r\\n]", "_");
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private JsonNode zdCall(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(zdBaseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Cookie", zdCookie);
            if ("POST".equals(method)) {
                rb.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : mapper.writeValueAsString(body)));
            } else {
                rb.GET();
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                throw new BusinessException(ERR_ZDCODE, "zd-code 鉴权失败（检查 agent.zdcode-app-token 配置）");
            }
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException(ERR_ZDCODE, "zd-code " + resp.statusCode() + "：" + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ERR_ZDCODE, "zd-code 不可达：" + e.getMessage());
        }
    }
}
