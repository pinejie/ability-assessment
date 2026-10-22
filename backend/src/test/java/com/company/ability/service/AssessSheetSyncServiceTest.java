package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评页面同步服务测试（重点覆盖「流程中需评价」开关 assess_in_flow 的入页面/停用/兜底逻辑）
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评页面同步服务测试")
class AssessSheetSyncServiceTest {

    private static final long RESOURCE_ID = 100L;
    private static final int YEAR = 2026;

    @Autowired
    private AssessSheetSyncService syncService;

    @Autowired
    private JdbcTemplate jdbc;

    private long categoryId;
    private long elementAssess;   // 需评价要素
    private long elementSkip;     // 默认免评要素（可被用例翻转开关）

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO uf_ability_category (category_name, status, deleted) VALUES ('同步类别', 1, 0)");
        categoryId = idOf("SELECT id FROM uf_ability_category WHERE category_name = '同步类别'");

        elementAssess = insertElement("需评价要素", 1);
        elementSkip = insertElement("免评要素", 0);

        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, 0, 1)", YEAR);
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, departmentid, jobtitle, managerid) " +
                "VALUES (?, '张三', 1, 5, 7, 88)", RESOURCE_ID);
        jdbc.update("INSERT INTO uf_assess_weight_ability (company_id, dimension_id, category_id, weight) " +
                "VALUES (1, 1, ?, 100)", categoryId);
        for (int level = 1; level <= 5; level++) {
            jdbc.update("INSERT INTO uf_assess_level_score (company_id, dimension_id, level_no, score) " +
                    "VALUES (1, 1, ?, ?)", level, level * 20);
        }
    }

    @Test
    @DisplayName("不需评价的要素不生成页面行，标准分只计需评价要素")
    void skipElementNotInSheet() {
        seedRequirement();

        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        Long sheetId = sheetId();
        List<Map<String, Object>> rows = activeRows(sheetId);
        assertEquals(1, rows.size(), "页面应只包含需评价要素");
        assertEquals(elementAssess, num(rows.get(0).get("element_id")));
        assertEquals(1, num(rows.get(0).get("active")));

        // S(e) = 分值(2级=40)/10 × 类别权重100 ÷ n_c(1) = 400；免评要素不占 n_c、不摊权重
        assertClose(400, sheetStandard(sheetId));
    }

    @Test
    @DisplayName("开关从需评价改为免评后重新同步：原页面行置 active=0，不删除，权重被接管")
    void toggledOffDeactivatesExistingRow() {
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);
        seedRequirement();
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);
        Long sheetId = sheetId();
        assertEquals(2, allRows(sheetId).size());
        // 同类 2 要素平摊：标准分 = 200 + 200
        assertClose(400, sheetStandard(sheetId));

        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 0 WHERE id = ?", elementSkip);
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        Map<Long, Integer> activeByElement = activeByElement(sheetId);
        assertEquals(1, activeByElement.get(elementAssess));
        assertEquals(0, activeByElement.get(elementSkip), "免评要素的历史行应被停用而非删除");
        assertClose(400, sheetStandard(sheetId), "唯一需评价要素接管类别权重，页面标准分不变（400）");
    }

    @Test
    @DisplayName("要求中全部要素都免评时，拒绝同步并给出明确提示")
    void allSkippedThrows() {
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 0");
        seedRequirement();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> syncService.syncRequirement(RESOURCE_ID, YEAR, true));
        assertTrue(ex.getMessage().contains("流程中不评价"), ex.getMessage());
    }

    // ========== 种子与查询辅助 ==========

    /** 人员能力要求头 + 两个要素明细（要求等级都指向 2 级；是否进页面由要素开关决定） */
    private void seedRequirement() {
        long reqId = insertReturning("INSERT INTO uf_user_ability_req_new (resource_id, assess_year, dimension_id) " +
                "VALUES (" + RESOURCE_ID + ", " + YEAR + ", 1)",
                "SELECT id FROM uf_user_ability_req_new WHERE resource_id = " + RESOURCE_ID +
                        " AND assess_year = " + YEAR);
        long levelAssess = insertLevel(elementAssess);
        long levelSkip = insertLevel(elementSkip);
        jdbc.update("INSERT INTO uf_user_ability_req_item (req_id, element_id, category_id, level_id) " +
                "VALUES (?, ?, ?, ?)", reqId, elementAssess, categoryId, levelAssess);
        jdbc.update("INSERT INTO uf_user_ability_req_item (req_id, element_id, category_id, level_id) " +
                "VALUES (?, ?, ?, ?)", reqId, elementSkip, categoryId, levelSkip);
    }

    private long insertElement(String name, int assessInFlow) {
        return insertReturning("INSERT INTO uf_ability_element (element_name, category_id, assess_in_flow, " +
                        "allow_client_assess, status, sort, deleted) VALUES ('" + name + "', " + categoryId + ", " +
                        assessInFlow + ", 1, 1, 0, 0)",
                "SELECT id FROM uf_ability_element WHERE element_name = '" + name + "'");
    }

    private long insertLevel(long elementId) {
        jdbc.update("INSERT INTO uf_ability_element_level (element_id, level, level_name, score) " +
                "VALUES (?, 2, '二级', 40)", elementId);
        return idOf("SELECT id FROM uf_ability_element_level WHERE element_id = " + elementId + " AND level = 2");
    }

    private long insertReturning(String insertSql, String selectSql) {
        jdbc.update(insertSql);
        return idOf(selectSql);
    }

    private long idOf(String selectSql) {
        return jdbc.queryForObject(selectSql, Long.class);
    }

    private Long sheetId() {
        return jdbc.queryForObject("SELECT s.id FROM uf_assess_sheet s " +
                "JOIN uf_assess_period p ON p.id = s.period_id " +
                "WHERE p.year = ? AND s.resource_id = ?", Long.class, YEAR, RESOURCE_ID);
    }

    private List<Map<String, Object>> activeRows(long sheetId) {
        return jdbc.queryForList("SELECT * FROM uf_assess_sheet_row WHERE sheet_id = ? AND active = 1", sheetId);
    }

    private List<Map<String, Object>> allRows(long sheetId) {
        return jdbc.queryForList("SELECT * FROM uf_assess_sheet_row WHERE sheet_id = ?", sheetId);
    }

    private Map<Long, Integer> activeByElement(long sheetId) {
        return allRows(sheetId).stream().collect(Collectors.toMap(
                m -> num(m.get("element_id")), m -> (int) num(m.get("active")),
                (a, b) -> a, HashMap::new));
    }

    private BigDecimal sheetStandard(long sheetId) {
        return jdbc.queryForObject("SELECT standard_score FROM uf_assess_sheet WHERE id = ?",
                BigDecimal.class, sheetId);
    }

    private static void assertClose(double expected, BigDecimal actual) {
        assertNotNull(actual, "标准分不应为空");
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)), "expected " + expected + " but was " + actual);
    }

    private static void assertClose(double expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)), message + ": was " + actual);
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private void clean() {
        for (String t : new String[]{"uf_user_ability_req_item", "uf_user_ability_req_new", "uf_assess_sheet_log",
                "uf_assess_sheet_row", "uf_assess_sheet", "uf_assess_period", "uf_assess_level_score",
                "uf_assess_weight_ability", "uf_ability_element_level", "uf_ability_element",
                "uf_ability_category", "HrmResource"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }
}
