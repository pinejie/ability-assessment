package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AbilityStructureDTO;
import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.AbilityStructureVO;
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
    private final AgentSessionService sessionService;
    private final AbilityStructureImportService structureImportService;

    private final boolean enabled;
    private final String internalToken;
    private final long companyId;
    private final int idleMinutes;
    private final int maxHours;

    public AgentBridgeService(JdbcTemplate jdbc, ObjectMapper mapper,
                              ExternalScoreService externalScoreService,
                              AssessDimensionService dimensionService,
                              AdminService adminService,
                              AgentSessionService sessionService,
                              AbilityStructureImportService structureImportService,
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
        this.sessionService = sessionService;
        this.structureImportService = structureImportService;
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
        checkInternalToken(agentToken);
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new BusinessException(400, "载荷不是合法 JSON");
        }
        // 交付稿类型（sql/33）：缺省为评分导入；结构导入走各自的解析与校验
        String draftKind = payload.path("kind").asText(AgentImportRunService.KIND_EXTERNAL).trim().toUpperCase();
        if (AgentImportRunService.KIND_STRUCTURE.equals(draftKind)) {
            return submitStructureDraft(payload, payloadJson, validateOnly);
        }
        Resolution res = resolveDraft(payload);
        requireBatchDimensionMatch(payload.path("sessionId").asText(""), res.dimensionId);
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

        // 交付前置对账：文件每一行必须交代去向（入稿 coveredRows / 弃用 skipped），杜绝异常数据无痕消失
        String reconciliation = reconcileFileCoverage(payload);
        if (reconciliation != null) {
            out.put("ok", false);
            out.put("ready", false);
            out.put("reconciliation", reconciliation);
            return out;
        }

        long draftId = insertDraft(res, payloadJson, draftKind);
        out.put("ok", true);
        out.put("draftId", draftId);
        out.put("ready", true);
        out.put("expiresAt", LocalDateTime.now().plusMinutes(idleMinutes)
                .isBefore(LocalDateTime.now().plusHours(maxHours))
                ? LocalDateTime.now().plusMinutes(idleMinutes) : LocalDateTime.now().plusHours(maxHours));
        return out;
    }

    /**
     * 能力结构交付稿（方案 14）：解析 → 确定性校验 → 落稿。校验未过不落稿；
     * 行对账沿用评分导入同一套（file_rows 与 skipped/coveredRows）。
     */
    private Map<String, Object> submitStructureDraft(JsonNode payload, String payloadJson, boolean validateOnly) {
        AbilityStructureDTO dto;
        try {
            dto = mapper.treeToValue(payload, AbilityStructureDTO.class);
        } catch (Exception e) {
            throw new BusinessException(400, "结构交付稿格式不合法：" + e.getMessage());
        }
        String sessionId = payload.path("sessionId").asText("");
        if (!sessionId.matches("[0-9a-z-A-Z_-]{1,64}")) {
            throw new BusinessException(400, "载荷缺少合法 sessionId");
        }
        if (!sessionService.isLiveSession(sessionId)) {
            throw new BusinessException(400, "会话不存在或已结束，交付被拒");
        }
        Long explicitDim = dto.getDimensionId();
        long dimensionId = explicitDim == null
                ? com.company.ability.context.DimensionContext.getOrDefault() : explicitDim;
        requireBatchDimensionMatch(sessionId, dimensionId);

        AbilityStructureVO.PreviewResult preview = withDimension(dimensionId,
                () -> structureImportService.preview(dto, sessionId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", preview.getLines());
        out.put("counts", preview.getCounts());
        if (!preview.isCommitable()) {
            out.put("ok", false);
            out.put("ready", false);
            return out;
        }
        // 行对账在 validate 阶段就要过：让模型自己补齐 coveredRows/skipped 再来 deliver，
        // 而不是把"哪一行没交代"甩给用户回答（用户无从作答，只会白烧一轮）
        String reconciliation = reconcileStructureCoverage(payload);
        if (reconciliation != null) {
            out.put("ok", false);
            out.put("ready", false);
            out.put("reconciliation", reconciliation);
            return out;
        }
        if (validateOnly) {
            out.put("ok", true);
            out.put("validateOnly", true);
            return out;
        }
        long draftId = insertDraft(sessionId, preview.getYear(), dimensionId, payloadJson,
                AgentImportRunService.KIND_STRUCTURE);
        out.put("ok", true);
        out.put("draftId", draftId);
        out.put("ready", true);
        return out;
    }

    /** parse_import_file 上报解析数据行数（cp_server 尽力调用，供 deliver 对账）。 */
    public void recordFileStats(String agentToken, String sessionId, Integer fileRows) {
        requireEnabled();
        checkInternalToken(agentToken);
        if (sessionId == null || !sessionId.matches("[0-9a-zA-Z_-]{1,64}")
                || fileRows == null || fileRows < 0 || fileRows > 20000) {
            throw new BusinessException(400, "参数不合法");
        }
        try {
            jdbc.update("UPDATE uf_assess_import_run SET file_rows = ? WHERE session_id = ? AND company_id = ?",
                    fileRows, sessionId, companyId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("记录 file_rows 失败（请确认 sql/29 已应用到库）：{}", e.getMessage());
        }
    }

    /** parse_structure_file 上报各 sheet 的数据行块（cp_server 尽力调用，供结构交付稿对账）。 */
    public void recordStructureStats(String agentToken, String sessionId, JsonNode sheets) {
        requireEnabled();
        checkInternalToken(agentToken);
        if (sessionId == null || !sessionId.matches("[0-9a-zA-Z_-]{1,64}") || sheets == null || !sheets.isArray()
                || sheets.size() > 50) {
            throw new BusinessException(400, "参数不合法");
        }
        int total = 0;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (JsonNode s : sheets) {
            int first = s.path("firstLine").asInt(0);
            int last = s.path("lastLine").asInt(0);
            int count = s.path("rows").asInt(0);
            if (first < 1 || last < first || last > 100000 || count <= 0 || count > 20000) {
                throw new BusinessException(400, "sheet 行块不合法");
            }
            total += count;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", s.path("name").asText("").length() > 100
                    ? s.path("name").asText("").substring(0, 100) : s.path("name").asText(""));
            one.put("kind", s.path("kind").asText("other"));
            one.put("firstLine", first);
            one.put("lastLine", last);
            one.put("rows", count);
            normalized.add(one);
        }
        if (total > 20000) {
            throw new BusinessException(400, "文件行数超出上限");
        }
        try {
            jdbc.update("UPDATE uf_assess_import_run SET structure_file_stats = ? WHERE session_id = ? AND company_id = ?",
                    mapper.writeValueAsString(normalized), sessionId, companyId);
        } catch (Exception e) {
            log.warn("记录 structure_file_rows 失败（请确认 sql/33 已应用到库）：{}", e.getMessage());
        }
    }

    /**
     * 能力结构交付稿行对账：与评分导入（物理行号 2..file_rows+1）不同，结构工作簿有多张 sheet，
     * 行号必须带表名定位。parse_structure_file 上报每张纳入解析的表的数据行块，
     * 稿里的 coveredRows 用「表名!行号」字符串 / skipped[].rowNo 同样带表名，两块必须完全覆盖。
     * 未上报（旧文件/未经 parse）→ 放行；unreconciled（轮次耗尽强制成稿）→ 放行。
     */
    private String reconcileStructureCoverage(JsonNode payload) {
        if (payload.path("unreconciled").asBoolean(false)) {
            return null;
        }
        String sessionId = payload.path("sessionId").asText("");
        String statsJson;
        try {
            statsJson = jdbc.queryForObject(
                    "SELECT structure_file_stats FROM uf_assess_import_run WHERE session_id = ? AND company_id = ?",
                    String.class, sessionId, companyId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("结构交付稿对账跳过（structure_file_stats 查询失败，请确认 sql/33 已应用到库）：{}", e.getMessage());
            return null;
        }
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        JsonNode stats;
        try {
            stats = mapper.readTree(statsJson);
        } catch (Exception e) {
            return null;
        }
        java.util.Set<String> declared = new java.util.TreeSet<>();
        payload.path("coveredRows").forEach(n -> declared.add(n.asText()));
        payload.path("skipped").forEach(n -> declared.add(n.path("rowNo").asText()));
        declared.removeIf(s -> s == null || s.isBlank());
        List<String> missing = new ArrayList<>();
        int total = 0;
        for (JsonNode s : stats) {
            String name = s.path("name").asText("");
            int first = s.path("firstLine").asInt(0);
            int last = s.path("lastLine").asInt(0);
            total += s.path("rows").asInt(0);
            for (int line = first; line <= last; line++) {
                if (!declared.contains(name + "!" + line)) {
                    missing.add(name + "!" + line);
                    if (missing.size() >= 20) {
                        break;
                    }
                }
            }
            if (missing.size() >= 20) {
                break;
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "结构文件共 " + total + " 行数据（多表，行号须写成「表名!行号」）：未交代去向例如 " + missing
                + "；deliver 必须交代每一张结构表的数据行——入稿行放 coveredRows，未导入行放"
                + " skipped[{rowNo:'表名!行号',raw,reason}]（标题/说明行、重复表、无能力要求、查无此人、读不懂的列都算 skipped）；"
                + "信息不足时先 submit_outcome kind=ask 问用户。";
    }

    /**
     * 文件行对账：parse 阶段上报的 file_rows 划定数据行范围（物理行号 2..file_rows+1，首行为表头），
     * deliver 载荷必须携带 coveredRows（稿行覆盖的物理行号）与 skipped[{rowNo,raw,reason}]（未导入行及原因），
     * 两者并集未覆盖全部数据行、或引用了越界行号 → 返回给 agent 的纠错指令（不落稿）；
     * 无需对账（未上报 file_rows：旧会话/未经 parse 的交付）或列不存在（sql/29 未应用）→ 返回 null 放行。
     */
    private String reconcileFileCoverage(JsonNode payload) {
        if (payload.path("unreconciled").asBoolean(false)) {
            return null;   // 轮次耗尽后由流水线强制成稿的豁免标记
        }
        String sessionId = payload.path("sessionId").asText("");
        Integer fileRows;
        try {
            fileRows = jdbc.queryForObject(
                    "SELECT file_rows FROM uf_assess_import_run WHERE session_id = ? AND company_id = ?",
                    Integer.class, sessionId, companyId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("文件行对账跳过（file_rows 查询失败，请确认 sql/29 已应用到库）：{}", e.getMessage());
            return null;
        }
        if (fileRows == null || fileRows <= 0) {
            return null;
        }
        java.util.Set<Integer> declared = new java.util.TreeSet<>();
        payload.path("coveredRows").forEach(n -> declared.add(n.asInt()));
        payload.path("skipped").forEach(n -> declared.add(n.path("rowNo").asInt(0)));
        int firstLine = 2;
        int lastLine = fileRows + 1;
        List<Integer> missing = new ArrayList<>();
        for (int line = firstLine; line <= lastLine; line++) {
            if (!declared.contains(line)) {
                missing.add(line);
            }
        }
        List<Integer> bogus = new ArrayList<>();
        for (int line : declared) {
            if (line < firstLine || line > lastLine) {
                bogus.add(line);
            }
        }
        if (missing.isEmpty() && bogus.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("文件共 " + fileRows + " 行数据（物理行号 " + firstLine + "–" + lastLine + "）：");
        if (!missing.isEmpty()) {
            sb.append("未交代去向的行号 ").append(missing).append("；");
        }
        if (!bogus.isEmpty()) {
            sb.append("越界行号 ").append(bogus).append("；");
        }
        sb.append("deliver 必须交代文件每一行——入稿行号放 coveredRows，未导入行放 skipped[{rowNo,raw,reason}]")
          .append("（超范围列/查无此人/无能力要求/冲突取一等都算 skipped）；信息不足时先 submit_outcome kind=ask 问用户。");
        return sb.toString();
    }

    /**
     * 交付维度硬闸：建批已固化操作人页面当前维度（uf_assess_import_run.dimension_id），
     * 稿件解析出的维度与之不一致 → 拒收。操作人权限再多，也只认发起时的当前维度。
     * 无批次记录（聊天通道旧路径）或 run 未记维度 → 不设闸（维持原歧义阻断语义）。
     */
    private void requireBatchDimensionMatch(String sessionId, long draftDimensionId) {
        Long runDim;
        try {
            runDim = jdbc.queryForObject(
                    "SELECT dimension_id FROM uf_assess_import_run WHERE session_id = ? AND company_id = ?",
                    Long.class, sessionId, companyId);
        } catch (org.springframework.dao.DataAccessException e) {
            return;
        }
        if (runDim != null && runDim.longValue() != draftDimensionId) {
            throw new BusinessException(409, "交付维度（" + draftDimensionId + "）与批次发起维度（" + runDim
                    + "）不一致——导入必须在操作人当前维度内进行");
        }
    }

    private void checkInternalToken(String agentToken) {
        if (internalToken.isBlank() || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                agentToken == null ? new byte[0] : agentToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "内部令牌无效");
        }
    }

    // ==================== draft：查询与写入（前端登录态） ====================

    /** 会话下最新稿列表（前端轮询=活跃心跳，刷新 last_seen）；仅返回登录用户有权维度的稿 */
    public List<Map<String, Object>> listDrafts(String sessionId) {
        requireEnabled();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TOP 5 id, kind, year, dimension_id, status, payload_json," +
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
            item.put("kind", row.get("kind"));
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

    /**
     * 审核页取行级状态：对已落稿的结构交付稿重跑确定性校验（不落库、不改稿）。
     * 交付稿只存原始载荷，行级 CREATE/SKIP/PENDING/BLOCK 是算出来的——审核页与
     * "别名确认后刷新"都走这里，保证与 commit 时同一套规则。
     */
    public Map<String, Object> previewStructureDraft(long draftId) {
        requireEnabled();
        LoginUser user = UserContext.currentOrNull();
        if (user == null) {
            throw new BusinessException(401, "查看交付稿需要登录身份");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, kind, dimension_id, status, payload_json" +
                " FROM uf_assess_agent_draft WHERE id = ? AND company_id = ?", draftId, companyId);
        if (rows.isEmpty()) {
            throw new BusinessException(404, "交付稿不存在");
        }
        Map<String, Object> row = rows.get(0);
        long dimensionId = ((Number) row.get("dimension_id")).longValue();
        if (!canAccessDimension(user, dimensionId)) {
            throw new BusinessException(403, "无权操作该维度的交付稿");
        }
        if (!AgentImportRunService.KIND_STRUCTURE.equals(String.valueOf(row.get("kind")))) {
            throw new BusinessException(400, "只有能力结构交付稿可以逐行预览");
        }
        AbilityStructureDTO dto;
        try {
            dto = mapper.readValue(String.valueOf(row.get("payload_json")), AbilityStructureDTO.class);
        } catch (Exception e) {
            throw new BusinessException(400, "交付稿损坏");
        }
        AbilityStructureVO.PreviewResult preview = withDimension(dimensionId,
                () -> structureImportService.preview(dto, dto.getSessionId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", draftId);
        out.put("status", row.get("status"));
        out.put("dimensionId", dimensionId);
        out.put("lines", preview.getLines());
        out.put("counts", preview.getCounts());
        out.put("commitable", preview.isCommitable());
        out.put("year", preview.getYear());
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
                "SELECT id, session_id, kind, year, dimension_id, status, payload_json, commit_time," +
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
        // 类型路由（sql/33）：结构导入走 AbilityStructureImportService，评分导入维持原路径
        if (AgentImportRunService.KIND_STRUCTURE.equals(String.valueOf(row.get("kind")))) {
            return commitStructureDraft(row, user, dimensionId);
        }

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

    /** 结构交付稿写入：重校验 → 单事务写结构 → 事务外逐人同步汇总 */
    private Map<String, Object> commitStructureDraft(Map<String, Object> row, LoginUser user, long dimensionId) {
        long draftId = ((Number) row.get("id")).longValue();
        AbilityStructureDTO dto;
        try {
            dto = mapper.readValue(String.valueOf(row.get("payload_json")), AbilityStructureDTO.class);
        } catch (Exception e) {
            throw new BusinessException(400, "交付稿损坏");
        }
        Long batchId = jdbc.queryForList(
                "SELECT id FROM uf_assess_import_run WHERE draft_id = ? AND company_id = ?",
                Long.class, draftId, companyId).stream().findFirst().orElse(null);

        AbilityStructureVO.CommitResult result = withDimension(dimensionId,
                () -> structureImportService.commit(dto, String.valueOf(row.get("session_id")), batchId));

        boolean clean = result.getFailed() == null || result.getFailed().isEmpty();
        jdbc.update("UPDATE uf_assess_agent_draft SET status=?, commit_time=SYSDATETIME(), commit_by=?," +
                        " commit_result=? WHERE id = ?",
                clean ? "COMMITTED" : "READY", user.resourceId(), summarizeStructure(result), draftId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rejected", false);
        out.put("result", result);
        return out;
    }

    private String summarizeStructure(AbilityStructureVO.CommitResult result) {
        AbilityStructureVO.Counts c = result.getCounts();
        return String.format("类别新增 %d/跳过 %d，要素新增 %d/跳过 %d，等级 %d，岗位新增 %d/覆盖 %d/保留 %d，人员新增 %d",
                c.getCategoryCreate(), c.getCategorySkip(), c.getElementCreate(), c.getElementSkip(),
                c.getLevelCreate(), c.getPositionCreate(), c.getPositionOverwrite(), c.getPositionKeep(),
                c.getUserCreate());
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
        // 活会话校验（review #2）：CLAUDE.md 承诺"不回传 sessionId 后端拒收"必须真实成立，
        // 否则持内部令牌者可向任意/不存在的会话 id 投毒伪造稿
        if (!sessionService.isLiveSession(sessionId)) {
            throw new BusinessException(400, "会话不存在或已结束，交付被拒——请让用户重新发起会话后再交付");
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

    private long insertDraft(Resolution res, String payloadJson, String kind) {
        return insertDraft(res.sessionId(), res.request().getYear(), res.dimensionId(), payloadJson, kind);
    }

    /** 落稿。结构导入的年度来自载荷（可空），评分导入恒有年度。 */
    private long insertDraft(String sessionId, Integer year, long dimensionId, String payloadJson, String kind) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uf_assess_agent_draft (session_id, company_id, year, dimension_id, payload_json, kind)" +
                    " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setLong(2, companyId);
            if (year == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, year);
            }
            ps.setLong(4, dimensionId);
            ps.setString(5, payloadJson);
            ps.setString(6, kind);
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

    /** cp_server 内部令牌统一校验（draft/outcome 共用），供 run 编排端点复用 */
    public void requireInternalToken(String token) {
        if (!enabled || internalToken.isBlank() || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "内部令牌无效");
        }
    }

    public boolean currentUserIsAdmin() {
        LoginUser user = UserContext.currentOrNull();
        return user != null && adminService.isAdmin(user.loginid());
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
