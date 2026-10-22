package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.ExternalScoreVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外部评分导入（第一期：手工录入通道；agent 期复用同一 preview/commit）。
 *
 * 口径（project/能力测评/10-外部评分导入与Agent交互方案.md §11）：
 * - 只写非流程要素（assess_in_flow=0）评级，source='EXTERNAL'，展开到当前 领导/自评/全部客户；
 * - 要素级 upsert：只覆盖本批（人×要素），删除走显式 clear；
 * - 允许分批：覆盖不齐 WARN 不阻断；
 * - 全部写库经存储过程（usp_assess_external_score / _clear / usp_assess_annual_score），本服务不直接写业务表；
 * - 年度已关闭拒绝（与钩子一致）；操作人取登录身份，随稿快照入 uf_assess_external_import_log。
 */
@Slf4j
@Service
public class ExternalScoreService {

    static final int ERR_VALIDATION = 40201;
    static final int ERR_PROC = 50002;

    /** usp_assess_annual_score 的流程 ID 入参：外部导入无流程，固定 0 作为标记 */
    private static final long ANNUAL_WORKFLOW_MARK = 0L;

    private static final String SHEETS_BY_YEAR =
            "SELECT s.id AS sheet_id, s.resource_id, h.lastname AS resource_name, p.status AS period_status," +
            "       s.status AS sheet_status, s.actual_score, s.grade_name, s.annual_score" +
            " FROM uf_assess_sheet s" +
            " JOIN uf_assess_period p ON p.id = s.period_id" +
            " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
            " WHERE s.company_id = ? AND p.year = ? AND s.dimension_id = ?";

    private static final String NON_FLOW_CELLS =
            "SELECT r.element_id, r.element_name, r.category_name, r.required_level_no," +
            "       (SELECT MAX(rt.level_no) FROM uf_assess_sheet_rating rt" +
            "         WHERE rt.sheet_id = r.sheet_id AND rt.element_id = r.element_id AND rt.source = 'EXTERNAL') AS current_level_no," +
            "       (SELECT MAX(rt.original_score) FROM uf_assess_sheet_rating rt" +
            "         WHERE rt.sheet_id = r.sheet_id AND rt.element_id = r.element_id AND rt.source = 'EXTERNAL') AS original_score" +
            " FROM uf_assess_sheet_row r" +
            " JOIN uf_ability_element e ON e.id = r.element_id AND e.deleted = 0" +
            " WHERE r.sheet_id = ? AND ISNULL(e.assess_in_flow, 1) = 0" +
            " ORDER BY r.sort_no, r.id";

    private static final String LAST_IMPORT_ITEMS =
            "SELECT TOP 1 items_json FROM uf_assess_external_import_log" +
            " WHERE company_id = ? AND dimension_id = ? AND year = ? AND resource_id = ? AND action <> 'EXTERNAL_CLEAR'" +
            " ORDER BY id DESC";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final long companyId;

    public ExternalScoreService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.companyId = companyId;
    }

    // ==================== 页面上下文 ====================

    /** 某年度本维度全部被测人（含外部要素覆盖情况），录入页人员表 */
    public List<ExternalScoreVO.SheetContext> contexts(int year) {
        long dimensionId = DimensionContext.getOrDefault();
        List<Map<String, Object>> heads = jdbc.queryForList(SHEETS_BY_YEAR + " ORDER BY s.resource_id",
                companyId, year, dimensionId);
        List<ExternalScoreVO.SheetContext> result = new ArrayList<>();
        for (Map<String, Object> h : heads) {
            ExternalScoreVO.SheetContext ctx = toContext(h, year);
            ctx.setElements(loadCells(ctx.getSheetId()));
            result.add(ctx);
        }
        return result;
    }

    /** 某人某年度的非流程要素与当前外部值 */
    public ExternalScoreVO.SheetContext context(int year, long resourceId) {
        long dimensionId = DimensionContext.getOrDefault();
        List<Map<String, Object>> heads = jdbc.queryForList(SHEETS_BY_YEAR + " AND s.resource_id = ?",
                companyId, year, dimensionId, resourceId);
        if (heads.isEmpty()) {
            throw new BusinessException(404, "该年度此维度下无测评页面（被测人未配备人员能力要求）");
        }
        ExternalScoreVO.SheetContext ctx = toContext(heads.get(0), year);
        ctx.setElements(loadCells(ctx.getSheetId()));
        return ctx;
    }

    // ==================== preview ====================

    public ExternalScoreVO.PreviewResult preview(ExternalScoreDTO.ImportRequest req) {
        List<ExternalScoreDTO.RatingRow> ratings = orEmpty(req.getRatings());
        List<ExternalScoreDTO.AnnualRow> annuals = orEmpty(req.getAnnuals());
        if (ratings.isEmpty() && annuals.isEmpty()) {
            throw new BusinessException(ERR_VALIDATION, "导入内容为空");
        }

        Set<Long> resources = new HashSet<>();
        ratings.forEach(r -> resources.add(r.getResourceId()));
        annuals.forEach(r -> resources.add(r.getResourceId()));

        // 页面与年度状态
        String placeholders = resources.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(req.getYear());
        args.add(DimensionContext.getOrDefault());
        args.addAll(resources);
        List<Map<String, Object>> sheets = jdbc.queryForList(
                SHEETS_BY_YEAR + " AND s.resource_id IN (" + placeholders + ")", args.toArray());
        Map<Long, Map<String, Object>> sheetByRes = new HashMap<>();
        for (Map<String, Object> s : sheets) {
            sheetByRes.put(num(s, "resource_id"), s);
        }

        // 每页非流程要素白名单（preview 的"要素必须是非流程且在页面上"依据）
        Map<Long, Set<Long>> nonFlowBySheet = new HashMap<>();
        if (!sheets.isEmpty()) {
            String sheetPh = sheets.stream().map(x -> "?").collect(Collectors.joining(","));
            List<Object> sheetIds = sheets.stream()
                    .map(s -> num(s, "sheet_id")).collect(Collectors.toList());
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT r.sheet_id, r.element_id FROM uf_assess_sheet_row r" +
                    " JOIN uf_ability_element e ON e.id = r.element_id AND e.deleted = 0" +
                    " WHERE r.sheet_id IN (" + sheetPh + ") AND ISNULL(e.assess_in_flow, 1) = 0",
                    sheetIds.toArray())) {
                nonFlowBySheet.computeIfAbsent(num(row, "sheet_id"), k -> new HashSet<>())
                        .add(num(row, "element_id"));
            }
        }

        List<ExternalScoreVO.LineResult> lines = new ArrayList<>();
        Map<Long, Set<Long>> newElementsPerRes = new HashMap<>();
        Set<String> seenRating = new HashSet<>();
        Set<Long> seenAnnual = new HashSet<>();
        int lineNo = 0;

        for (ExternalScoreDTO.RatingRow r : ratings) {
            lineNo++;
            String status = "PASS";
            String message = null;
            Map<String, Object> sheet = sheetByRes.get(r.getResourceId());
            if (r.getLevelNo() == null || r.getLevelNo() < 1 || r.getLevelNo() > 5) {
                status = "BLOCK";
                message = "评级必须为 1–5 整数";
            } else if (sheet == null) {
                status = "BLOCK";
                message = "该年度此维度下无测评页面（未配备人员能力要求）";
            } else if (isClosed(sheet)) {
                status = "BLOCK";
                message = "年度已关闭，拒绝写入";
            } else if (!seenRating.add(r.getResourceId() + "#" + r.getElementId())) {
                status = "BLOCK";
                message = "同一人同一要素重复行";
            } else if (!nonFlowBySheet.getOrDefault(num(sheet, "sheet_id"), Set.of()).contains(r.getElementId())) {
                status = "BLOCK";
                message = "要素不在该页面中，或属于流程评价要素（应由泛微流程写入）";
            } else {
                newElementsPerRes.computeIfAbsent(r.getResourceId(), k -> new HashSet<>()).add(r.getElementId());
            }
            lines.add(line(lineNo, "RATING", r.getResourceId(), r.getElementId(), status, message));
        }

        for (ExternalScoreDTO.AnnualRow a : annuals) {
            lineNo++;
            String status = "PASS";
            String message = null;
            Map<String, Object> sheet = sheetByRes.get(a.getResourceId());
            if (a.getAnnualScore() == null
                    || a.getAnnualScore().signum() < 0 || a.getAnnualScore().compareTo(new BigDecimal("100")) > 0) {
                status = "BLOCK";
                message = "年度分应在 0–100 之间";
            } else if (sheet == null) {
                status = "BLOCK";
                message = "该年度此维度下无测评页面（未配备人员能力要求）";
            } else if (isClosed(sheet)) {
                status = "BLOCK";
                message = "年度已关闭，拒绝写入";
            } else if (!seenAnnual.add(a.getResourceId())) {
                status = "BLOCK";
                message = "同一人重复年度分行";
            }
            lines.add(line(lineNo, "ANNUAL", a.getResourceId(), null, status, message));
        }

        // 覆盖摘要（决议 2：分批合法，展示 x/y；不齐只是信息，不阻断）
        List<ExternalScoreVO.Coverage> coverages = new ArrayList<>();
        for (Long res : resources) {
            Map<String, Object> sheet = sheetByRes.get(res);
            if (sheet == null) {
                continue;
            }
            long sheetId = num(sheet, "sheet_id");
            int total = nonFlowBySheet.getOrDefault(sheetId, Set.of()).size();
            Set<Long> after = new HashSet<>(coveredElements(sheetId, nonFlowBySheet));
            after.addAll(newElementsPerRes.getOrDefault(res, Set.of()));
            ExternalScoreVO.Coverage cov = new ExternalScoreVO.Coverage();
            cov.setResourceId(res);
            cov.setResourceName(str(sheet, "resource_name"));
            cov.setExternalTotal(total);
            cov.setExternalCoveredAfter(after.size());
            cov.setSheetStatus(sheet.get("sheet_status") == null ? null : ((Number) sheet.get("sheet_status")).intValue());
            coverages.add(cov);
        }

        ExternalScoreVO.PreviewResult result = new ExternalScoreVO.PreviewResult();
        result.setLines(lines);
        result.setCoverage(coverages);
        result.setCommitable(lines.stream().noneMatch(l -> "BLOCK".equals(l.getStatus())));
        return result;
    }

    // ==================== commit ====================

    public ExternalScoreVO.CommitResult commit(ExternalScoreDTO.ImportRequest req) {
        ExternalScoreVO.PreviewResult check = preview(req);
        if (!check.isCommitable()) {
            String first = check.getLines().stream()
                    .filter(l -> "BLOCK".equals(l.getStatus()))
                    .map(l -> "第" + l.getLineNo() + "行：" + l.getMessage())
                    .findFirst().orElse("校验未通过");
            throw new BusinessException(ERR_VALIDATION, "存在阻断行，已拒绝提交——" + first);
        }

        long operatorId = currentOperatorId();
        long dimensionId = DimensionContext.getOrDefault();
        List<ExternalScoreVO.CommitPersonResult> persons = new ArrayList<>();

        // 按人分组调用 usp_assess_external_score（决议 1：要素级 upsert；按人独立成败）
        Map<Long, List<ExternalScoreDTO.RatingRow>> byRes = orEmpty(req.getRatings()).stream()
                .collect(Collectors.groupingBy(ExternalScoreDTO.RatingRow::getResourceId, LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<Long, List<ExternalScoreDTO.RatingRow>> e : byRes.entrySet()) {
            ExternalScoreVO.CommitPersonResult pr = new ExternalScoreVO.CommitPersonResult();
            pr.setResourceId(e.getKey());
            pr.setAction("IMPORT");
            try {
                String itemsJson = buildItemsJson(e.getValue());
                callExternalScore(e.getKey(), req.getYear(), dimensionId, operatorId, itemsJson,
                        "EXTERNAL_IMPORT", pr);
            } catch (Exception ex) {
                fail(pr, ex);
            }
            persons.add(pr);
        }

        for (ExternalScoreDTO.AnnualRow a : orEmpty(req.getAnnuals())) {
            ExternalScoreVO.CommitPersonResult pr = new ExternalScoreVO.CommitPersonResult();
            pr.setResourceId(a.getResourceId());
            pr.setAction("ANNUAL");
            try {
                List<Map<String, Object>> rs = jdbc.queryForList(
                        "{call usp_assess_annual_score(?,?,?,?,?)}",
                        companyId, req.getYear(), a.getResourceId(), a.getAnnualScore(), ANNUAL_WORKFLOW_MARK);
                pr.setSuccess(true);
                pr.setMessage("年度分已写入：" + a.getAnnualScore());
                if (!rs.isEmpty()) {
                    pr.setSheetId(longOf(rs.get(0).get("sheetId")));
                }
            } catch (Exception ex) {
                fail(pr, ex);
            }
            persons.add(pr);
        }

        ExternalScoreVO.CommitResult result = new ExternalScoreVO.CommitResult();
        result.setPersons(persons);
        return result;
    }

    /** 按上稿重填（决议 3）：取最后成功稿，按当前评价人集合重放 */
    public ExternalScoreVO.CommitResult refill(ExternalScoreDTO.PersonRequest req) {
        long operatorId = currentOperatorId();
        long dimensionId = DimensionContext.getOrDefault();
        List<String> items = jdbc.queryForList(LAST_IMPORT_ITEMS, String.class,
                companyId, dimensionId, req.getYear(), req.getResourceId());
        if (items.isEmpty() || items.get(0) == null) {
            throw new BusinessException(ERR_VALIDATION, "该被测人没有历史导入稿，请先完成一次导入");
        }

        ExternalScoreVO.CommitPersonResult pr = new ExternalScoreVO.CommitPersonResult();
        pr.setResourceId(req.getResourceId());
        pr.setAction("REFILL");
        try {
            callExternalScore(req.getResourceId(), req.getYear(), dimensionId, operatorId,
                    items.get(0), "EXTERNAL_REFILL", pr);
        } catch (Exception ex) {
            fail(pr, ex);
        }
        ExternalScoreVO.CommitResult result = new ExternalScoreVO.CommitResult();
        result.setPersons(List.of(pr));
        return result;
    }

    /** 显式清空某人外部导入（决议 1 配套：删除只能显式） */
    public ExternalScoreVO.CommitResult clear(ExternalScoreDTO.PersonRequest req) {
        long operatorId = currentOperatorId();
        long dimensionId = DimensionContext.getOrDefault();

        ExternalScoreVO.CommitPersonResult pr = new ExternalScoreVO.CommitPersonResult();
        pr.setResourceId(req.getResourceId());
        pr.setAction("CLEAR");
        try {
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "{call usp_assess_external_clear(?,?,?,?,?)}",
                    companyId, req.getYear(), req.getResourceId(), dimensionId, operatorId);
            fillSheetState(pr, rs);
            pr.setSuccess(true);
            pr.setMessage("已清空该被测人全部外部导入评级");
        } catch (Exception ex) {
            fail(pr, ex);
        }
        ExternalScoreVO.CommitResult result = new ExternalScoreVO.CommitResult();
        result.setPersons(List.of(pr));
        return result;
    }

    // ==================== 私有 ====================

    private void callExternalScore(long resourceId, int year, long dimensionId, long operatorId,
                                   String itemsJson, String action, ExternalScoreVO.CommitPersonResult pr) {
        List<Map<String, Object>> rs = jdbc.queryForList(
                "{call usp_assess_external_score(?,?,?,?,?,?,?)}",
                companyId, year, resourceId, dimensionId, operatorId, itemsJson, action);
        fillSheetState(pr, rs);
        pr.setSuccess(true);
        pr.setMessage("评级已写入并重算（覆盖 " + pr.getExternalCovered() + "/" + pr.getExternalTotal() + "）");
    }

    private void fillSheetState(ExternalScoreVO.CommitPersonResult pr, List<Map<String, Object>> rs) {
        if (rs.isEmpty()) {
            return;
        }
        Map<String, Object> row = rs.get(0);
        pr.setSheetId(longOf(row.get("sheetId")));
        pr.setSheetStatus(intOf(row.get("status")));
        pr.setActualScore(decimalOf(row.get("actual_score")));
        pr.setGradeName(row.get("grade_name") == null ? null : row.get("grade_name").toString());
        pr.setExternalTotal(intOf(row.get("externalTotal")));
        pr.setExternalCovered(intOf(row.get("externalCovered")));
    }

    private String buildItemsJson(List<ExternalScoreDTO.RatingRow> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExternalScoreDTO.RatingRow r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("elementId", r.getElementId());
            item.put("levelNo", r.getLevelNo());
            item.put("originalScore", r.getOriginalScore());
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception ex) {
            throw new BusinessException(ERR_VALIDATION, "导入载荷序列化失败");
        }
    }

    private long currentOperatorId() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(401, "外部导入需要登录身份（记录操作人）");
        }
        return user.resourceId();
    }

    private ExternalScoreVO.SheetContext toContext(Map<String, Object> h, int year) {
        ExternalScoreVO.SheetContext ctx = new ExternalScoreVO.SheetContext();
        ctx.setSheetId(num(h, "sheet_id"));
        ctx.setYear(year);
        ctx.setResourceId(num(h, "resource_id"));
        ctx.setResourceName(str(h, "resource_name"));
        ctx.setSheetStatus(intOf(h.get("sheet_status")));
        ctx.setActualScore(decimalOf(h.get("actual_score")));
        ctx.setGradeName(str(h, "grade_name"));
        ctx.setAnnualScore(decimalOf(h.get("annual_score")));
        ctx.setPeriodOpen(!isClosed(h));
        return ctx;
    }

    private List<ExternalScoreVO.ElementCell> loadCells(long sheetId) {
        List<ExternalScoreVO.ElementCell> cells = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(NON_FLOW_CELLS, sheetId)) {
            ExternalScoreVO.ElementCell cell = new ExternalScoreVO.ElementCell();
            cell.setElementId(num(row, "element_id"));
            cell.setElementName(str(row, "element_name"));
            cell.setCategoryName(str(row, "category_name"));
            cell.setRequiredLevelNo(intOf(row.get("required_level_no")));
            cell.setCurrentLevelNo(intOf(row.get("current_level_no")));
            cell.setOriginalScore(decimalOf(row.get("original_score")));
            cells.add(cell);
        }
        return cells;
    }

    /** 该页已覆盖的外部要素集合（与 coveredBySheet 口径一致：非流程白名单 ∩ EXTERNAL 评级） */
    private Set<Long> coveredElements(long sheetId, Map<Long, Set<Long>> nonFlowBySheet) {
        Set<Long> nonFlow = nonFlowBySheet.getOrDefault(sheetId, Set.of());
        if (nonFlow.isEmpty()) {
            return Set.of();
        }
        return jdbc.queryForList(
                        "SELECT DISTINCT rt.element_id FROM uf_assess_sheet_rating rt" +
                        " WHERE rt.sheet_id = ? AND rt.source = 'EXTERNAL'", Long.class, sheetId).stream()
                .filter(nonFlow::contains)
                .collect(Collectors.toSet());
    }

    private boolean isClosed(Map<String, Object> sheet) {
        Object status = sheet.get("period_status");
        return status != null && ((Number) status).intValue() == 1;
    }

    private void fail(ExternalScoreVO.CommitPersonResult pr, Exception ex) {
        pr.setSuccess(false);
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        pr.setMessage(msg.replaceAll("^(SQLServerException|RuntimeException|BusinessException):\\s*", ""));
        log.warn("外部导入提交失败 resourceId={} action={}: {}", pr.getResourceId(), pr.getAction(), msg);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static ExternalScoreVO.LineResult line(int no, String kind, Long res, Long element,
                                                   String status, String message) {
        ExternalScoreVO.LineResult l = new ExternalScoreVO.LineResult();
        l.setLineNo(no);
        l.setKind(kind);
        l.setResourceId(res);
        l.setElementId(element);
        l.setStatus(status);
        l.setMessage(message);
        return l;
    }

    private static Long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : ((Number) v).longValue();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer intOf(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static Long longOf(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static BigDecimal decimalOf(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(v.toString());
    }
}
