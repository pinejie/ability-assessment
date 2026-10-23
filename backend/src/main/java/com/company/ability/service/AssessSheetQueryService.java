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

        List<AssessSheetVO.Row> rows = loadDisplayRows(d.getId(), d.getResourceId(), d.getYear());

        for (AssessSheetVO.Row row : rows) {
            Long eid = row.getElementId();
            for (Integer seq : seqs) {
                Map<String, Object> rt = Boolean.TRUE.equals(row.getNonFlow())
                        ? null : byKey.get(eid + "#" + seq);
                AssessSheetVO.Rating c = new AssessSheetVO.Rating();
                c.setSeq(seq);
                if (rt != null) {
                    c.setLevelNo((Integer) rt.get("level_no"));
                    c.setScore((BigDecimal) rt.get("score"));
                }
                row.getClients().add(c);
            }
        }
        // 每个客户在有效要素上的得分合计（合计行直接使用，不在前端加总；未计分的免评行不参与）
        java.util.Set<Long> activeIds = new java.util.HashSet<>();
        for (AssessSheetVO.Row r : rows) {
            if (!Boolean.TRUE.equals(r.getNonFlow())) activeIds.add(r.getElementId());
        }
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

    /**
     * 展示行（按排序）= recalc v5 的计分口径（active=1 ∪ 有 EXTERNAL 评级）
     * ∪ 仍在本人·本年度·本维度人员能力要求中的免评停用行（active=0 且 assess_in_flow=0）：
     * 标记 non_flow，仅作任职标准展示——要求等级与类别权重（取当前配置）可见，
     * 得分/标准分按 null 掩蔽（存量行残留值兜底；同步已在写侧清空停用行冗余列），不进任何合计与报告；
     * flag 改回需评价但未重同步的行不展示（同旧口径），待重同步进流程。
     * 顺序：类别排序号 → 类别ID（同序平局键，保证同类别连续）→ 要素排序号 → 录入序兜底。
     */
    private List<AssessSheetVO.Row> loadDisplayRows(long sheetId, long resourceId, int year) {
        long dimensionId = DimensionContext.getOrDefault();
        List<Long> reqIds = jdbc.queryForList(
                "SELECT ri.element_id FROM uf_user_ability_req_item ri " +
                "JOIN uf_user_ability_req_new rh ON rh.id = ri.req_id " +
                "WHERE rh.resource_id = ? AND rh.assess_year = ? AND rh.dimension_id = ?",
                Long.class, resourceId, year, dimensionId);
        String inClause = reqIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
        // 仅免评要素（assess_in_flow=0）的停用行按任职标准展示（non_flow，得分掩蔽）；
        // flag 改回需评价但未重同步的 active=0 行沿用旧口径不展示——其未计入 recalc 页面头，
        // 以残留值混入展示/合计会造成报告校验不一致，等重同步进流程后自然恢复
        String nonFlowBranch = reqIds.isEmpty() ? "" :
                " OR (r.active = 0 AND COALESCE(e0.assess_in_flow, 1) = 0 AND r.element_id IN (" + inClause + "))";

        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(dimensionId);
        args.add(sheetId);
        args.add(sheetId);
        args.addAll(reqIds);

        String sql =
                "SELECT r.element_id, r.category_name, r.category_weight, r.element_name, r.required_level_no, r.standard_score," +
                "       r.leader_level_no, r.leader_score, r.client_avg_score, r.self_level_no, r.self_score," +
                "       wa.weight AS config_weight," +
                // 外部导入标签：有 EXTERNAL 评级且行不在流程正常在用状态（免评 flag 或已停用）——
                // 与 recalc v5 的 is_ext 判定同向（flag 改回 1 未重同步的 active=1 行也带标签，来源不失真）
                "       CASE WHEN ext.element_id IS NOT NULL AND (COALESCE(e0.assess_in_flow, 1) = 0 OR r.active = 0)" +
                "            THEN 1 ELSE 0 END AS is_external," +
                "       CASE WHEN r.active = 0 AND COALESCE(e0.assess_in_flow, 1) = 0 AND ext.element_id IS NULL THEN 1 ELSE 0 END AS non_flow," +
                "       ext.original_score" +
                " FROM uf_assess_sheet_row r" +
                " LEFT JOIN uf_ability_element e0 ON e0.id = r.element_id" +
                " LEFT JOIN uf_ability_category c0 ON c0.id = r.category_id" +
                " LEFT JOIN uf_assess_weight_ability wa ON wa.category_id = r.category_id" +
                "   AND wa.company_id = ? AND wa.dimension_id = ?" +
                " LEFT JOIN (SELECT element_id, MAX(original_score) AS original_score FROM uf_assess_sheet_rating" +
                "            WHERE sheet_id = ? AND source = 'EXTERNAL' GROUP BY element_id) ext ON ext.element_id = r.element_id" +
                " WHERE r.sheet_id = ? AND (r.active = 1 OR ext.element_id IS NOT NULL" + nonFlowBranch + ")" +
                " ORDER BY COALESCE(c0.sort, 2147483647), r.category_id, COALESCE(e0.sort, 2147483647), r.sort_no, r.id";
        return jdbc.query(sql, AssessSheetQueryService::mapDisplayRow, args.toArray());
    }

    private static AssessSheetVO.Row mapDisplayRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        AssessSheetVO.Row row = new AssessSheetVO.Row();
        boolean nonFlow = rs.getInt("non_flow") == 1;
        row.setNonFlow(nonFlow);
        row.setExternal(rs.getInt("is_external") == 1);
        row.setOriginalScore(rs.getBigDecimal("original_score"));
        row.setElementId(rs.getLong("element_id"));
        row.setCategoryName(rs.getString("category_name"));
        row.setElementName(rs.getString("element_name"));
        row.setRequiredLevelNo(rs.getObject("required_level_no") == null ? null : rs.getInt("required_level_no"));
        if (nonFlow) {
            // 未计分的免评行：分值掩蔽；类别权重取当前配置（行上停用前残留值不可信）
            row.setCategoryWeight(rs.getBigDecimal("config_weight"));
            row.setStandardScore(null);
            row.setLeaderLevelNo(null);
            row.setLeaderScore(null);
            row.setClientAvgScore(null);
            row.setSelfLevelNo(null);
            row.setSelfScore(null);
        } else {
            row.setCategoryWeight(rs.getBigDecimal("category_weight"));
            row.setStandardScore(rs.getBigDecimal("standard_score"));
            row.setLeaderLevelNo(rs.getObject("leader_level_no") == null ? null : rs.getInt("leader_level_no"));
            row.setLeaderScore(rs.getBigDecimal("leader_score"));
            row.setClientAvgScore(rs.getBigDecimal("client_avg_score"));
            row.setSelfLevelNo(rs.getObject("self_level_no") == null ? null : rs.getInt("self_level_no"));
            row.setSelfScore(rs.getBigDecimal("self_score"));
        }
        row.setClients(new ArrayList<>());
        return row;
    }
}
