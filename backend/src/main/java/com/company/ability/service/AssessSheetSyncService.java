package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测评汇总页面的结构同步（只写基础数据，不计算、不写评级和得分）。
 * 评级与得分只能由泛微流程钩子调用 usp_assess_rate 写入。
 *
 * 调用时机：人员能力要求保存（新增或编辑）之后，与保存在同一事务内。
 * 任何校验失败抛出 BusinessException，整个保存回滚。
 */
@Slf4j
@Service
public class AssessSheetSyncService {

    private final JdbcTemplate jdbc;
    private final long companyId;

    public AssessSheetSyncService(JdbcTemplate jdbc,
                                  @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.companyId = companyId;
    }

    /**
     * @param resourceId  被测人
     * @param year        年度（人员能力要求按年）
     * @param syncAssess  编辑时是否同步到已存在的页面；false 时不动页面数据，仅标记 req_changed=1
     */
    public void syncRequirement(long resourceId, int year, boolean syncAssess) {
        long dimensionId = DimensionContext.getOrDefault();

        // 1. 年度必须存在且未关闭（按维度隔离）
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, year, dimensionId);
        if (periods.isEmpty()) {
            throw new BusinessException(year + "年度不存在，请先在当前维度下的年度设置中新增");
        }
        if (num(periods.get(0).get("status")) == 1) {
            throw new BusinessException(year + "年度已关闭，人员能力要求不允许修改");
        }
        long periodId = num(periods.get(0).get("id"));

        // 2. 人员能力要求明细（要素、类别、要求等级、是否需评价）
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT i.element_id, COALESCE(i.category_id, e.category_id) AS category_id, " +
                "       c.category_name, e.element_name, l.level AS level_no, " +
                "       COALESCE(e.assess_in_flow, 1) AS assess_in_flow " +
                "FROM uf_user_ability_req_item i " +
                "JOIN uf_user_ability_req_new h ON h.id = i.req_id " +
                "LEFT JOIN uf_ability_element e ON e.id = i.element_id " +
                "LEFT JOIN uf_ability_category c ON c.id = COALESCE(i.category_id, e.category_id) " +
                "LEFT JOIN uf_ability_element_level l ON l.id = i.level_id " +
                "WHERE h.resource_id = ? AND h.assess_year = ? AND h.dimension_id = ? ORDER BY i.id",
                resourceId, year, dimensionId);

        if (items.isEmpty()) {
            throw new BusinessException("人员能力要求为空，不允许保存（能力要素由岗位派生）");
        }

        // 要求等级必须命中当前维度等级分值配置的等级号（等级数量动态，与归档/存储过程同口径）
        Set<Integer> configuredLevels = new HashSet<>(jdbc.queryForList(
                "SELECT level_no FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                Integer.class, companyId, dimensionId));

        Set<Long> allElementIds = new HashSet<>();
        for (Map<String, Object> m : items) {
            long elementId = num(m.get("element_id"));
            if (m.get("element_name") == null) {
                throw new BusinessException("能力要素不存在，ID: " + elementId);
            }
            if (m.get("category_id") == null || m.get("category_name") == null) {
                throw new BusinessException("要素缺少类别，ID: " + elementId);
            }
            Object lv = m.get("level_no");
            if (lv == null || !configuredLevels.contains((int) num(lv))) {
                throw new BusinessException("要素 " + elementId + " 的要求等级无效：不在当前维度等级分值配置的等级中"
                        + (lv == null ? "（等级缺失）" : "（等级 " + num(lv) + "）"));
            }
            if (!allElementIds.add(elementId)) {
                throw new BusinessException("同一要素重复配置，ID: " + elementId);
            }
        }

        // 2b. 需评价要素（assess_in_flow=1）进入泛微流程（页面行 active=1）；
        //     不需评价的要素也生成页面行但 active=0（任职标准，供测评汇总/个人报告展示与外部评分导入挂靠），
        //     不参与流程评价、标准分与完整性校验；不在要求中的历史行由第 5 步 NOT IN 停用，评级历史保留
        List<Map<String, Object>> assessItems = items.stream()
                .filter(m -> num(m.get("assess_in_flow")) != 0)
                .collect(Collectors.toList());
        if (assessItems.isEmpty()) {
            throw new BusinessException("能力要素均已设置「流程中不评价」，人员能力要求至少需要一个需评价要素");
        }
        Set<Long> elementIds = assessItems.stream()
                .map(m -> num(m.get("element_id")))
                .collect(Collectors.toCollection(HashSet::new));

        // 3. 人员信息（部门、岗位、直接领导）
        List<Map<String, Object>> hrs = jdbc.queryForList(
                "SELECT departmentid, jobtitle, managerid FROM HrmResource WHERE id = ?", resourceId);
        if (hrs.isEmpty()) {
            throw new BusinessException("人员不存在，ID: " + resourceId);
        }
        Map<String, Object> hr = hrs.get(0);

        // 4. 页面头：不存在则生成，存在则同步（或仅标记）
        List<Map<String, Object>> sheets = jdbc.queryForList(
                "SELECT id FROM uf_assess_sheet WHERE period_id = ? AND resource_id = ?", periodId, resourceId);

        long sheetId;
        String action;
        if (sheets.isEmpty()) {
            jdbc.update(
                    "INSERT INTO uf_assess_sheet (company_id, period_id, resource_id, department_id, job_title_id, " +
                    "  leader_resource_id, status, need_recalc, req_changed, client_count, dimension_id, " +
                    "  sync_time, create_time, update_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 0, 1, 0, 0, ?, SYSDATETIME(), SYSDATETIME(), SYSDATETIME())",
                    companyId, periodId, resourceId, hr.get("departmentid"), hr.get("jobtitle"), hr.get("managerid"),
                    dimensionId);
            sheetId = num(jdbc.queryForObject(
                    "SELECT id FROM uf_assess_sheet WHERE period_id = ? AND resource_id = ?", Long.class, periodId, resourceId));
            action = "GENERATE";
        } else {
            sheetId = num(sheets.get(0).get("id"));
            if (!syncAssess) {
                jdbc.update("UPDATE uf_assess_sheet SET req_changed = 1, update_time = SYSDATETIME() WHERE id = ?", sheetId);
                writeLog(sheetId, "SKIP_SYNC", "{\"reason\":\"user_choose_not_sync\"}", dimensionId);
                log.info("人员能力要求已保存，未同步测评页面，sheetId={}", sheetId);
                return;
            }
            jdbc.update(
                    "UPDATE uf_assess_sheet SET department_id = ?, job_title_id = ?, leader_resource_id = ?, " +
                    "  need_recalc = 1, req_changed = 0, sync_time = SYSDATETIME(), update_time = SYSDATETIME() WHERE id = ?",
                    hr.get("departmentid"), hr.get("jobtitle"), hr.get("managerid"), sheetId);
            action = "SYNC";
        }

        // 5. 页面行：要求中的全部要素都新增或更新（结构字段）。需评价 active=1（进流程）；
        //    不需评价 active=0（不进流程，仅作任职标准展示/外部导入挂靠）。不在要求中的要素置为无效（保留已有评级）
        for (int sortNo = 1; sortNo <= items.size(); sortNo++) {
            Map<String, Object> m = items.get(sortNo - 1);
            long elementId = num(m.get("element_id"));
            long categoryId = num(m.get("category_id"));
            String categoryName = (String) m.get("category_name");
            String elementName = (String) m.get("element_name");
            int levelNo = (int) num(m.get("level_no"));
            int active = num(m.get("assess_in_flow")) != 0 ? 1 : 0;

            int updated = jdbc.update(
                    "UPDATE uf_assess_sheet_row SET category_id = ?, category_name = ?, element_name = ?, " +
                    "  required_level_no = ?, sort_no = ?, active = ? WHERE sheet_id = ? AND element_id = ?",
                    categoryId, categoryName, elementName, levelNo, sortNo, active, sheetId, elementId);
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO uf_assess_sheet_row (sheet_id, category_id, category_name, element_id, element_name, " +
                        "  required_level_no, sort_no, active, dimension_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        sheetId, categoryId, categoryName, elementId, elementName, levelNo, sortNo, active, dimensionId);
            }
        }

        String placeholders = allElementIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[allElementIds.size() + 1];
        args[0] = sheetId;
        int k = 1;
        for (Long id : allElementIds) args[k++] = id;
        int deactivated = jdbc.update(
                "UPDATE uf_assess_sheet_row SET active = 0 WHERE sheet_id = ? AND element_id NOT IN (" + placeholders + ")",
                args);

        // 5c. 停用行（免评/已移出要求）冗余分值列在写侧清空，杜绝残留值泄漏到任何读方；
        //     评级历史在 uf_assess_sheet_rating，不受影响。有 EXTERNAL 评级的停用行不清——
        //     它仍属 recalc v5 计分口径，清了会在"同步后、重算前"窗口期把外部导入结果显示成空白
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = NULL, category_weight = NULL, " +
                "leader_level_no = NULL, leader_score = NULL, self_level_no = NULL, self_score = NULL, " +
                "client_avg_score = NULL " +
                "WHERE sheet_id = ? AND active = 0 AND NOT EXISTS (SELECT 1 FROM uf_assess_sheet_rating x" +
                "  WHERE x.sheet_id = uf_assess_sheet_row.sheet_id AND x.element_id = uf_assess_sheet_row.element_id" +
                "    AND x.source = 'EXTERNAL')", sheetId);

        // 5b. 基础数据：类别权重、要素标准分、岗位标准分（由配置与要求等级算出，不依赖评级；仅计需评价要素）
        writeBaseScores(sheetId, assessItems, dimensionId);

        // 6. 日志
        writeLog(sheetId, action, String.format(
                "{\"elements\":%d,\"required\":%d,\"deactivated\":%d}",
                elementIds.size(), items.size(), deactivated), dimensionId);
        log.info("测评页面{}完成，sheetId={}, 需评价要素={}/要求={}, 停用={}",
                action, sheetId, elementIds.size(), items.size(), deactivated);
    }

    /**
     * 删除人员能力要求前的测评侧处理。
     * - 年度已关闭：拒绝删除（只读）。
     * - 页面已有评级（泛微钩子写入过）：拒绝删除，评级是业务记录。
     * - 页面没有任何评级：删除页面（行、页面头、日志）。
     * 调用方应在同一事务中继续删除人员能力要求。
     */
    public void removeRequirement(long resourceId, int year) {
        long dimensionId = DimensionContext.getOrDefault();
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, year, dimensionId);
        if (periods.isEmpty()) {
            return; // 年度不存在，说明没有对应页面
        }
        if (num(periods.get(0).get("status")) == 1) {
            throw new BusinessException(year + "年度已关闭，不允许删除人员能力要求");
        }
        long periodId = num(periods.get(0).get("id"));

        List<Map<String, Object>> sheets = jdbc.queryForList(
                "SELECT id FROM uf_assess_sheet WHERE period_id = ? AND resource_id = ?", periodId, resourceId);
        if (sheets.isEmpty()) {
            return;
        }
        long sheetId = num(sheets.get(0).get("id"));

        Integer ratingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_sheet_rating WHERE sheet_id = ?", Integer.class, sheetId);
        if (ratingCount != null && ratingCount > 0) {
            throw new BusinessException("已有评价数据，不允许删除，如需调整请编辑");
        }

        jdbc.update("DELETE FROM uf_assess_sheet_log WHERE sheet_id = ?", sheetId);
        jdbc.update("DELETE FROM uf_assess_sheet_row WHERE sheet_id = ?", sheetId);
        jdbc.update("DELETE FROM uf_assess_sheet WHERE id = ?", sheetId);
        log.info("删除人员能力要求，同时删除无评价数据的测评页面，sheetId={}", sheetId);
    }

    /**
     * 写入基础数据：类别权重、要素标准分 S(e) 与岗位标准分。
     * S(e) = 等级分值(要求等级) ÷ 10 × 类别权重 ÷ 类别要素数。配置缺失时对应字段置空。
     */
    private void writeBaseScores(long sheetId, List<Map<String, Object>> items, long dimensionId) {
        Map<Long, BigDecimal> catWeight = new java.util.HashMap<>();
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT category_id, weight FROM uf_assess_weight_ability WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            catWeight.put(num(m.get("category_id")), (BigDecimal) m.get("weight"));
        }
        Map<Integer, BigDecimal> levelScore = new java.util.HashMap<>();
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT level_no, score FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            levelScore.put((int) num(m.get("level_no")), (BigDecimal) m.get("score"));
        }
        Map<Long, Long> countByCat = items.stream().collect(
                Collectors.groupingBy(m -> num(m.get("category_id")), Collectors.counting()));

        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (Map<String, Object> m : items) {
            long elementId = num(m.get("element_id"));
            long categoryId = num(m.get("category_id"));
            int levelNo = (int) num(m.get("level_no"));
            BigDecimal weight = catWeight.get(categoryId);
            BigDecimal score = levelScore.get(levelNo);
            BigDecimal standard = null;
            if (weight != null && score != null) {
                standard = score.divide(BigDecimal.TEN, 10, RoundingMode.HALF_UP)
                        .multiply(weight)
                        .divide(BigDecimal.valueOf(countByCat.get(categoryId)), 4, RoundingMode.HALF_UP);
                total = total.add(standard);
            } else {
                complete = false;
            }
            jdbc.update(
                    "UPDATE uf_assess_sheet_row SET category_weight = ?, standard_score = ? WHERE sheet_id = ? AND element_id = ?",
                    weight, standard, sheetId, elementId);
        }
        jdbc.update("UPDATE uf_assess_sheet SET standard_score = ? WHERE id = ?",
                complete ? total.setScale(2, RoundingMode.HALF_UP) : null, sheetId);
    }

    private void writeLog(long sheetId, String action, String detailJson, long dimensionId) {
        jdbc.update(
                "INSERT INTO uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time, dimension_id) " +
                "VALUES (?, ?, NULL, ?, SYSDATETIME(), ?)", sheetId, action, detailJson, dimensionId);
    }

    private static long num(Object o) {
        if (o == null) return 0L;
        return ((Number) o).longValue();
    }
}
