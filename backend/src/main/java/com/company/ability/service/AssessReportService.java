package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessSheetVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个人测评报告。
 * - 年度开放：实时计算，读取页面表、评级表、意见表。
 * - 年度关闭：读取冻结快照（关闭时写入 uf_assess_report_snapshot）。
 * - 年度分由泛微钩子写入（usp_assess_annual_score），测评侧只读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessReportService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcTemplate jdbc;
    private final AssessSheetQueryService queryService;
    private final ObjectMapper objectMapper;

    @Value("${assess.company-id:1}")
    private long companyId;

    public AssessSheetVO.Report get(long sheetId) {
        Integer status = periodStatusOfSheet(sheetId);
        if (status == 1) {
            List<String> snap = jdbc.queryForList(
                    "SELECT report_json FROM uf_assess_report_snapshot WHERE sheet_id = ?", String.class, sheetId);
            if (!snap.isEmpty()) {
                AssessSheetVO.Report r = readJson(snap.get(0));
                r.setFrozen(true);
                return r;
            }
        }
        return build(sheetId);
    }

    /**
     * 年度关闭时调用（同一事务）：为该年每个页面写入冻结快照。
     * 某个页面生成失败时只跳过该页面并写日志，不影响整年关闭；
     * 该页面没有快照，之后读取时走实时计算（仍为只读）。
     */
    public void freezePeriod(long periodId) {
        List<Map<String, Object>> sheets = jdbc.queryForList(
                "SELECT id, ISNULL(dimension_id, 1) AS dimension_id FROM uf_assess_sheet WHERE period_id = ?", periodId);
        for (Map<String, Object> sheet : sheets) {
            Long sheetId = ((Number) sheet.get("id")).longValue();
            Long dimensionId = ((Number) sheet.get("dimension_id")).longValue();
            try {
                AssessSheetVO.Report r = build(sheetId);
                r.setClosed(true);
                r.setFrozen(false);
                String json = writeJson(r);
                jdbc.update("DELETE FROM uf_assess_report_snapshot WHERE sheet_id = ?", sheetId);
                jdbc.update("INSERT INTO uf_assess_report_snapshot (sheet_id, report_json, freeze_time, dimension_id) " +
                        "VALUES (?, ?, SYSDATETIME(), ?)", sheetId, json, dimensionId);
            } catch (RuntimeException e) {
                log.warn("年度关闭时报告快照失败，已跳过，sheetId={}，原因：{}", sheetId, e.getMessage());
                jdbc.update("INSERT INTO uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time) " +
                        "VALUES (?, 'FREEZE_SKIP', NULL, ?, SYSDATETIME())",
                        sheetId, "{\"reason\":\"" + jsonEscape(e.getMessage()) + "\"}");
            }
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** 年度重新开启时调用：删除快照，恢复实时计算。 */
    public void unfreezePeriod(long periodId) {
        jdbc.update("DELETE FROM uf_assess_report_snapshot WHERE sheet_id IN " +
                "(SELECT id FROM uf_assess_sheet WHERE period_id = ?)", periodId);
    }

    // ---------------------------------------------------------------- 实时计算

    private AssessSheetVO.Report build(long sheetId) {
        AssessSheetVO.Detail d = queryService.detail(sheetId);

        AssessSheetVO.Report r = new AssessSheetVO.Report();
        r.setSheetId(d.getId());
        r.setYear(d.getYear());
        r.setResourceName(d.getResourceName());
        fillHeader(sheetId, r);

        // 配置按页面所属维度读取（与存储过程 usp_assess_recalc 口径一致）
        Long dimensionId = jdbc.queryForObject(
                "SELECT ISNULL(dimension_id, 1) FROM uf_assess_sheet WHERE id = ?", Long.class, sheetId);

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT assessor_code, weight FROM uf_assess_weight_assessor WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            weights.put((String) m.get("assessor_code"), (BigDecimal) m.get("weight"));
        }
        BigDecimal wLeader = weights.get("LEADER");
        BigDecimal wClient = weights.get("CLIENT");
        BigDecimal wSelf = weights.get("SELF");
        if (wLeader == null || wClient == null || wSelf == null) {
            throw new BusinessException("评分权重配置不完整，无法生成报告（请检查当前维度下的测评配置）");
        }

        List<AssessSheetVO.GradeBand> bands = loadBands(dimensionId);
        r.setGradeBands(bands);

        // 按类别汇总（保持页面的类别顺序）。
        // 未计分的免评要素行（nonFlow）也保留类别（任职标准可见），但得分合计只累计计分口径行
        // （需评价行 + 已导入外部评级的行，与 recalc v5 / 页面头一致）；类别内无计分行时各类分值保持 null。
        Map<String, List<AssessSheetVO.Row>> byCategory = d.getRows().stream()
                .collect(Collectors.groupingBy(AssessSheetVO.Row::getCategoryName, LinkedHashMap::new, Collectors.toList()));
        List<AssessSheetVO.CategoryRow> categories = new ArrayList<>();
        List<AssessSheetVO.CategoryRow> scoredCategories = new ArrayList<>();
        for (Map.Entry<String, List<AssessSheetVO.Row>> e : byCategory.entrySet()) {
            List<AssessSheetVO.Row> scored = e.getValue().stream()
                    .filter(x -> !Boolean.TRUE.equals(x.getNonFlow())).collect(Collectors.toList());
            AssessSheetVO.CategoryRow c = new AssessSheetVO.CategoryRow();
            c.setCategoryName(e.getKey());
            if (!scored.isEmpty()) {
                c.setWeight(scored.get(0).getCategoryWeight());
                c.setStandardScore(AssessReportCalc.sumOrNull(scored.stream().map(AssessSheetVO.Row::getStandardScore).collect(Collectors.toList())));
                c.setLeaderScore(AssessReportCalc.sumOrNull(scored.stream().map(AssessSheetVO.Row::getLeaderScore).collect(Collectors.toList())));
                c.setClientScore(AssessReportCalc.sumOrNull(scored.stream().map(AssessSheetVO.Row::getClientAvgScore).collect(Collectors.toList())));
                c.setSelfScore(AssessReportCalc.sumOrNull(scored.stream().map(AssessSheetVO.Row::getSelfScore).collect(Collectors.toList())));
                if (c.getLeaderScore() != null && c.getClientScore() != null && c.getSelfScore() != null) {
                    c.setActualScore(AssessReportCalc.actual(c.getLeaderScore(), c.getClientScore(), c.getSelfScore(),
                            wLeader, wClient, wSelf));
                }
                c.setScoreRate(AssessReportCalc.rate(c.getActualScore(), c.getStandardScore()));
                c.setGradeName(AssessReportCalc.gradeOf(c.getScoreRate(), bands));
                scoredCategories.add(c);
            }
            categories.add(c);
        }
        r.setCategories(categories);

        // 合计取自页面头，并与类别合计核对
        AssessSheetVO.Total t = new AssessSheetVO.Total();
        t.setStandardScore(d.getStandardScore());
        t.setLeaderScore(d.getLeaderScore());
        t.setClientScore(d.getClientScore());
        t.setSelfScore(d.getSelfScore());
        t.setActualScore(d.getActualScore());
        t.setScoreRate(d.getScoreRate());
        t.setGradeName(d.getGradeName());
        r.setTotal(t);
        r.setComplete(d.getActualScore() != null);
        // 只对含计分要素的类别做一致性核对；纯免评占位类别（无计分行）不参与
        verify(scoredCategories, t);

        // 人工年度分与意见
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT annual_score FROM uf_assess_sheet WHERE id = ?", sheetId);
        r.setAnnualScore((BigDecimal) head.get("annual_score"));
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT comment_type, content FROM uf_assess_comment WHERE sheet_id = ?", sheetId)) {
            if ("LEADER".equals(m.get("comment_type"))) r.setLeaderComment((String) m.get("content"));
            if ("CLIENT".equals(m.get("comment_type"))) r.setClientComment((String) m.get("content"));
        }
        r.setClosed(periodStatusOfSheet(sheetId) == 1);
        r.setFrozen(false);
        return r;
    }

    /** 类别合计与页面合计必须一致（允许 0.01 的舍入差）。入参为含计分要素的类别（build 显式收集） */
    private void verify(List<AssessSheetVO.CategoryRow> scoredCategories, AssessSheetVO.Total t) {
        BigDecimal stdSum = AssessReportCalc.sumOrNull(scoredCategories.stream()
                .map(AssessSheetVO.CategoryRow::getStandardScore).collect(Collectors.toList()));
        if (stdSum != null && t.getStandardScore() != null && stdSum.subtract(t.getStandardScore()).abs().compareTo(TOLERANCE) > 0) {
            throw new BusinessException("报告标准分合计与页面不一致，请联系管理员核对");
        }
        BigDecimal actSum = AssessReportCalc.sumOrNull(scoredCategories.stream()
                .map(AssessSheetVO.CategoryRow::getActualScore).collect(Collectors.toList()));
        if (actSum != null && t.getActualScore() != null && actSum.subtract(t.getActualScore()).abs().compareTo(TOLERANCE) > 0) {
            throw new BusinessException("报告实际得分合计与页面不一致，请联系管理员核对");
        }
    }

    private void fillHeader(long sheetId, AssessSheetVO.Report r) {
        List<AssessSheetVO.Report> rows = jdbc.query(
                // 单位取被测人所在分部（HrmResource.subcompanyid1 → HrmSubCompany）
                "SELECT sc.subcompanyname, dep.departmentname, jt.jobtitlename, h.birthday" +
                " FROM uf_assess_sheet s" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                " LEFT JOIN HrmSubCompany sc ON sc.id = h.subcompanyid1" +
                " LEFT JOIN HrmDepartment dep ON dep.id = s.department_id" +
                " LEFT JOIN HrmJobTitles jt ON jt.id = h.jobtitle" +
                " WHERE s.id = ?",
                (rs, i) -> {
                    AssessSheetVO.Report x = new AssessSheetVO.Report();
                    x.setCompanyName(rs.getString("subcompanyname"));
                    x.setDepartmentName(rs.getString("departmentname"));
                    x.setJobTitleName(rs.getString("jobtitlename"));
                    java.sql.Date birthday = rs.getDate("birthday");
                    if (birthday != null) {
                        x.setAge(Period.between(birthday.toLocalDate(), LocalDate.now()).getYears());
                    }
                    return x;
                }, sheetId);
        if (!rows.isEmpty()) {
            AssessSheetVO.Report x = rows.get(0);
            r.setCompanyName(x.getCompanyName());
            r.setDepartmentName(x.getDepartmentName());
            r.setJobTitleName(x.getJobTitleName());
            r.setAge(x.getAge());
        }
    }

    private List<AssessSheetVO.GradeBand> loadBands(Long dimensionId) {
        return jdbc.query(
                "SELECT level_name, lower_bound, upper_bound FROM uf_assess_grade_level" +
                " WHERE company_id = ? AND dimension_id = ? ORDER BY sort_order",
                (rs, i) -> {
                    AssessSheetVO.GradeBand b = new AssessSheetVO.GradeBand();
                    b.setLevelName(rs.getString("level_name"));
                    b.setLowerBound(rs.getBigDecimal("lower_bound"));
                    b.setUpperBound(rs.getBigDecimal("upper_bound"));
                    return b;
                }, companyId, dimensionId);
    }

    private Integer periodStatusOfSheet(long sheetId) {
        List<Integer> s = jdbc.queryForList(
                "SELECT p.status FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id WHERE s.id = ?",
                Integer.class, sheetId);
        if (s.isEmpty()) throw new BusinessException(404, "测评页面不存在，ID: " + sheetId);
        return s.get(0);
    }

    private String writeJson(AssessSheetVO.Report r) {
        try {
            return objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("报告快照序列化失败", e);
        }
    }

    private AssessSheetVO.Report readJson(String json) {
        try {
            return objectMapper.readValue(json, AssessSheetVO.Report.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("报告快照读取失败", e);
        }
    }
}
