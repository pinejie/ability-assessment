package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.ExternalScoreVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能导入二期：Agent 交付稿（draft）管理。
 * 会话运行时在 AgentSessionService（测评后端自派生 claude CLI，不复用 ai-chat-app/zd-code）。
 *
 * 职责边界（方案 10 §5）：
 * - cp_server（agent）只能用内部令牌调 submitDraft：校验+落 draft，绝不写业务表；
 * - 写入唯一路径 = 前端点击 → commitDraft → 复用一期 ExternalScoreService（存储过程）；
 * - draft 过期与会话回收同规则（闲置 30 分钟 / 绝对 2 小时，惰性判定）。
 */
@Slf4j
@Service
public class AgentBridgeService {

    static final int ERR_DRAFT_EXPIRED = 40301;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExternalScoreService externalScoreService;
    private final AssessDimensionService dimensionService;
    private final AdminService adminService;

    private final boolean enabled;
    private final String internalToken;
    private final long companyId;
    private final int idleMinutes;
    private final int maxHours;

    public AgentBridgeService(JdbcTemplate jdbc, ObjectMapper mapper,
                              ExternalScoreService externalScoreService,
                              AssessDimensionService dimensionService,
                              AdminService adminService,
                              @Value("${agent.enabled:false}") boolean enabled,
                              @Value("${agent.internal-token:}") String internalToken,
                              @Value("${assess.company-id:1}") long companyId,
                              @Value("${agent.draft-idle-minutes:30}") int idleMinutes,
                              @Value("${agent.draft-max-hours:2}") int maxHours) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.externalScoreService = externalScoreService;
        this.dimensionService = dimensionService;
        this.adminService = adminService;
        this.enabled = enabled;
        this.internalToken = internalToken;
        this.companyId = companyId;
        this.idleMinutes = idleMinutes;
        this.maxHours = maxHours;
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

    /** 会话下最新稿列表（前端轮询=活跃心跳，刷新 last_seen）；仅返回登录用户有权维度的稿 */
    public List<Map<String, Object>> listDrafts(String sessionId) {
        requireEnabled();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TOP 5 id, year, dimension_id, status, payload_json," +
                "       CONVERT(VARCHAR(19), ready_time, 120) AS ready_time," +
                "       CONVERT(VARCHAR(19), last_seen_time, 120) AS last_seen_time" +
                " FROM uf_assess_agent_draft WHERE session_id = ? AND company_id = ? ORDER BY id DESC",
                safeId(sessionId), companyId);
        List<Map<String, Object>> out = new ArrayList<>();
        LoginUser user = UserContext.currentOrNull();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String status = (String) row.get("status");
            // 跨维度隔离：猜 sessionId 也看不到无权维度的稿内容
            if (user != null && !canAccessDimension(user, ((Number) row.get("dimension_id")).longValue())) {
                continue;
            }
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
                "SELECT id, session_id, year, dimension_id, status, payload_json, commit_time," +
                "       CONVERT(VARCHAR(19), ready_time, 120) AS ready_time," +
                "       CONVERT(VARCHAR(19), last_seen_time, 120) AS last_seen_time" +
                " FROM uf_assess_agent_draft WHERE id = ? AND company_id = ?", draftId, companyId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "交付稿不存在");
        }
        Map<String, Object> row = rows.get(0);
        // 越权门：commitDraft 用 withDimension 切上下文绕开了请求头维度校验，
        // 必须在这里补维度授权（管理员豁免），否则 draft_id 可枚举构成跨租户写入
        if (!canAccessDimension(user, ((Number) row.get("dimension_id")).longValue())) {
            throw new BusinessException(403, "无权操作该维度的交付稿");
        }
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
        // 全部成功才作废（COMMITTED）；部分成功保持 READY——upsert 幂等，重试仅补失败者
        boolean allSuccess = !result.getPersons().isEmpty()
                && result.getPersons().stream().allMatch(ExternalScoreVO.CommitPersonResult::isSuccess);
        jdbc.update("UPDATE uf_assess_agent_draft SET status=?, commit_time=SYSDATETIME(), commit_by=?," +
                    " commit_result=? WHERE id = ?",
                allSuccess ? "COMMITTED" : "READY", user.resourceId(),
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

    /** 维度授权判定：管理员放行，其余按维度授权表（与 DimensionContextFilter 同口径） */
    private boolean canAccessDimension(LoginUser user, long dimensionId) {
        if (adminService.isAdmin(user.loginid())) {
            return true;
        }
        return dimensionService.isAuthorizedForUser(dimensionId, user.resourceId());
    }

    private static String safeId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-zA-Z_-]{1,64}")) {
            throw new BusinessException(400, "会话 ID 不合法");
        }
        return sessionId;
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }
}
