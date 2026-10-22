package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageResult;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessSheetVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测评汇总页面的只读查询。不写任何表，不计算得分。
 */
@Service
public class AssessSheetQueryService {

    private final JdbcTemplate jdbc;
    private final long companyId;

    public AssessSheetQueryService(JdbcTemplate jdbc,
                                   @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.companyId = companyId;
    }

    public PageResult<AssessSheetVO.ListItem> page(Integer year, Long resourceId, Long departmentId, String gradeName,
                                                   Integer status, int pageNum, int pageSize) {
        if (year == null) throw new BusinessException("请选择年度");
        if (pageNum < 1 || pageSize < 1 || pageSize > 200) throw new BusinessException("分页参数不合法");

        StringBuilder where = new StringBuilder(
                " FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                " WHERE s.company_id = ? AND p.year = ? AND s.dimension_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(year);
        args.add(DimensionContext.getOrDefault());
        if (resourceId != null) { where.append(" AND s.resource_id = ?"); args.add(resourceId); }
        if (departmentId != null) { where.append(" AND s.department_id = ?"); args.add(departmentId); }
        if (gradeName != null && !gradeName.isBlank()) { where.append(" AND s.grade_name = ?"); args.add(gradeName); }
        if (status != null) { where.append(" AND s.status = ?"); args.add(status); }

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((pageNum - 1) * pageSize);
        pageArgs.add(pageSize);
        List<AssessSheetVO.ListItem> list = jdbc.query(
                "SELECT s.id, p.year, s.resource_id, h.lastname AS resource_name, s.department_id, s.status," +
                "       s.need_recalc, s.req_changed, s.client_count, s.standard_score, s.leader_score," +
                "       s.client_score, s.self_score, s.actual_score, s.score_rate, s.grade_name, s.update_time" +
                where + " ORDER BY s.resource_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> {
                    AssessSheetVO.ListItem it = new AssessSheetVO.ListItem();
                    it.setId(rs.getLong("id"));
                    it.setYear(rs.getInt("year"));
                    it.setResourceId(rs.getLong("resource_id"));
                    it.setResourceName(rs.getString("resource_name"));
                    it.setDepartmentId(rs.getObject("department_id") == null ? null : rs.getLong("department_id"));
                    it.setStatus(rs.getInt("status"));
                    it.setNeedRecalc(rs.getInt("need_recalc") == 1);
                    it.setReqChanged(rs.getInt("req_changed") == 1);
                    it.setClientCount(rs.getInt("client_count"));
                    it.setStandardScore(rs.getBigDecimal("standard_score"));
                    it.setLeaderScore(rs.getBigDecimal("leader_score"));
                    it.setClientScore(rs.getBigDecimal("client_score"));
                    it.setSelfScore(rs.getBigDecimal("self_score"));
                    it.setActualScore(rs.getBigDecimal("actual_score"));
                    it.setScoreRate(rs.getBigDecimal("score_rate"));
                    it.setGradeName(rs.getString("grade_name"));
                    it.setUpdateTime(rs.getTimestamp("update_time") == null ? null
                            : rs.getTimestamp("update_time").toLocalDateTime());
                    return it;
                }, pageArgs.toArray());

        return new PageResult<>(list, total == null ? 0L : total, pageNum, pageSize);
    }

    public AssessSheetVO.Detail detail(long sheetId) {
        List<AssessSheetVO.Detail> heads = jdbc.query(
                "SELECT s.id, p.year, s.resource_id, r.lastname AS resource_name, s.department_id," +
                "       s.leader_resource_id, l.lastname AS leader_name, s.status, s.need_recalc, s.req_changed," +
                "       s.client_count, s.standard_score, s.leader_score, s.client_score, s.self_score," +
                "       s.actual_score, s.score_rate, s.grade_name" +
                " FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id" +
                " LEFT JOIN HrmResource r ON r.id = s.resource_id" +
                " LEFT JOIN HrmResource l ON l.id = s.leader_resource_id" +
                " WHERE s.id = ? AND s.company_id = ? AND s.dimension_id = ?",
                (rs, i) -> {
                    AssessSheetVO.Detail d = new AssessSheetVO.Detail();
                    d.setId(rs.getLong("id"));
                    d.setYear(rs.getInt("year"));
                    d.setResourceId(rs.getLong("resource_id"));
                    d.setResourceName(rs.getString("resource_name"));
                    d.setDepartmentId(rs.getObject("department_id") == null ? null : rs.getLong("department_id"));
                    d.setLeaderResourceId(rs.getObject("leader_resource_id") == null ? null : rs.getLong("leader_resource_id"));
                    d.setLeaderName(rs.getString("leader_name"));
                    d.setStatus(rs.getInt("status"));
                    d.setNeedRecalc(rs.getInt("need_recalc") == 1);
                    d.setReqChanged(rs.getInt("req_changed") == 1);
                    d.setClientCount(rs.getInt("client_count"));
                    d.setStandardScore(rs.getBigDecimal("standard_score"));
                    d.setLeaderScore(rs.getBigDecimal("leader_score"));
                    d.setClientScore(rs.getBigDecimal("client_score"));
                    d.setSelfScore(rs.getBigDecimal("self_score"));
                    d.setActualScore(rs.getBigDecimal("actual_score"));
                    d.setScoreRate(rs.getBigDecimal("score_rate"));
                    d.setGradeName(rs.getString("grade_name"));
                    return d;
                }, sheetId, companyId, DimensionContext.getOrDefault());
        if (heads.isEmpty()) throw new BusinessException(404, "测评页面不存在，ID: " + sheetId);
        AssessSheetVO.Detail d = heads.get(0);

        // 客户序号（按序号排序，决定"客户N"列）
        List<Integer> seqs = jdbc.queryForList(
                "SELECT DISTINCT client_seq FROM uf_assess_sheet_rating WHERE sheet_id = ? AND rater_type = 'CLIENT' ORDER BY client_seq",
                Integer.class, sheetId);
        d.setClientSeqs(seqs);

        // 各评级（按要素与客户序号索引）
        List<Map<String, Object>> ratingRows = jdbc.queryForList(
                "SELECT element_id, client_seq, level_no, score FROM uf_assess_sheet_rating" +
                " WHERE sheet_id = ? AND rater_type = 'CLIENT'", sheetId);
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : ratingRows) {
            byKey.put(r.get("element_id") + "#" + r.get("client_seq"), r);
        }

        // 有效要素行（按排序）：流程有效行 + 已获外部导入评级的非流程行（recalc v5 同口径）
        List<AssessSheetVO.Row> rows = jdbc.query(
                "SELECT r.element_id, r.category_name, r.category_weight, r.element_name, r.required_level_no, r.standard_score," +
                "       r.leader_level_no, r.leader_score, r.client_avg_score, r.self_level_no, r.self_score," +
                "       CASE WHEN r.active = 0 THEN 1 ELSE 0 END AS is_external," +
                "       (SELECT MAX(x.original_score) FROM uf_assess_sheet_rating x" +
                "         WHERE x.sheet_id = r.sheet_id AND x.element_id = r.element_id AND x.source = 'EXTERNAL') AS original_score" +
                " FROM uf_assess_sheet_row r" +
                " WHERE r.sheet_id = ? AND (r.active = 1 OR EXISTS (SELECT 1 FROM uf_assess_sheet_rating x2" +
                "        WHERE x2.sheet_id = r.sheet_id AND x2.element_id = r.element_id AND x2.source = 'EXTERNAL'))" +
                " ORDER BY r.sort_no, r.id",
                (rs, i) -> {
                    AssessSheetVO.Row row = new AssessSheetVO.Row();
                    row.setExternal(rs.getInt("is_external") == 1);
                    row.setOriginalScore(rs.getBigDecimal("original_score"));
                    row.setElementId(rs.getLong("element_id"));
                    row.setCategoryName(rs.getString("category_name"));
                    row.setCategoryWeight(rs.getBigDecimal("category_weight"));
                    row.setElementName(rs.getString("element_name"));
                    row.setRequiredLevelNo(rs.getObject("required_level_no") == null ? null : rs.getInt("required_level_no"));
                    row.setStandardScore(rs.getBigDecimal("standard_score"));
                    row.setLeaderLevelNo(rs.getObject("leader_level_no") == null ? null : rs.getInt("leader_level_no"));
                    row.setLeaderScore(rs.getBigDecimal("leader_score"));
                    row.setClientAvgScore(rs.getBigDecimal("client_avg_score"));
                    row.setSelfLevelNo(rs.getObject("self_level_no") == null ? null : rs.getInt("self_level_no"));
                    row.setSelfScore(rs.getBigDecimal("self_score"));
                    row.setClients(new ArrayList<>());
                    return row;
                }, sheetId);

        for (AssessSheetVO.Row row : rows) {
            Long eid = row.getElementId();
            for (Integer seq : seqs) {
                Map<String, Object> rt = byKey.get(eid + "#" + seq);
                AssessSheetVO.Rating c = new AssessSheetVO.Rating();
                c.setSeq(seq);
                if (rt != null) {
                    c.setLevelNo((Integer) rt.get("level_no"));
                    c.setScore((BigDecimal) rt.get("score"));
                }
                row.getClients().add(c);
            }
        }
        // 每个客户在有效要素上的得分合计（合计行直接使用，不在前端加总）
        java.util.Set<Long> activeIds = new java.util.HashSet<>();
        for (AssessSheetVO.Row r : rows) activeIds.add(r.getElementId());
        List<BigDecimal> seqTotals = new ArrayList<>();
        for (Integer seq : seqs) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : ratingRows) {
                if (!seq.equals(r.get("client_seq")) || r.get("score") == null) continue;
                if (!activeIds.contains(((Number) r.get("element_id")).longValue())) continue;
                sum = sum.add((BigDecimal) r.get("score"));
            }
            seqTotals.add(sum.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        d.setClientSeqTotals(seqTotals);
        d.setRows(rows);
        return d;
    }
}
