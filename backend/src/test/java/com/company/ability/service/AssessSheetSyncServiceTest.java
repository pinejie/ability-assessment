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

import static com.company.ability.h2test.SheetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评页面同步服务测试（重点覆盖「流程中需评价」开关 assess_in_flow 的入页面/停用/残留清理/兜底逻辑）
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评页面同步服务测试")
class AssessSheetSyncServiceTest {

    @Autowired
    private AssessSheetSyncService syncService;

    @Autowired
    private JdbcTemplate jdbc;

    private long categoryId;
    private long elementAssess;   // 需评价要素
    private long elementSkip;     // 默认免评要素（可被用例翻转开关）

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        categoryId = insertCategory(jdbc, "同步类别");
        elementAssess = insertElement(jdbc, "需评价要素", categoryId, 1);
        elementSkip = insertElement(jdbc, "免评要素", categoryId, 0);

        insertPeriod(jdbc, YEAR);
        insertResource(jdbc, RESOURCE_ID);
        insertCategoryWeight(jdbc, categoryId, 100);
        insertLevelScores(jdbc);
    }

    @Test
    @DisplayName("免评要素生成 active=0 页面行（任职标准可见、不进流程），标准分只计需评价要素")
    void skipElementNotInSheet() {
        seedRequirement();

        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        List<Map<String, Object>> rows = activeRows(sheetId);
        assertEquals(1, rows.size(), "进流程的行应只有需评价要素");
        assertEquals(elementAssess, num(rows.get(0).get("element_id")));
        assertEquals(1, num(rows.get(0).get("active")));

        assertEquals(2, allRows(sheetId).size(), "免评要素也应生成页面行（active=0，供汇总/报告展示与外部导入挂靠）");
        Map<Long, Integer> activeByElement = activeByElement(sheetId);
        assertEquals(0, activeByElement.get(elementSkip), "免评要素的页面行应为 active=0");
        Integer skipRequiredLevel = jdbc.queryForObject(
                "SELECT required_level_no FROM uf_assess_sheet_row WHERE sheet_id = ? AND element_id = ?",
                Integer.class, sheetId, elementSkip);
        assertEquals(2, skipRequiredLevel, "免评行必须记录要求等级（任职标准）");
        assertNull(rowScore(sheetId, elementSkip, "standard_score"), "免评行不写标准分");

        // S(e) = 分值(2级=40)/10 × 类别权重100 ÷ n_c(1) = 400；免评要素不占 n_c、不摊权重
        assertClose(400, sheetStandard(sheetId));
    }

    @Test
    @DisplayName("开关从需评价改为免评后重新同步：原页面行置 active=0，不删除，权重被接管")
    void toggledOffDeactivatesExistingRow() {
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);
        seedRequirement();
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
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
    @DisplayName("停用行在同步时清空冗余分值残留（写侧根因），评级历史保留")
    void deactivatedRowScoresClearedOnSync() {
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);
        seedRequirement();
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        // 模拟流程评价回写的冗余列
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = 200, category_weight = 100, " +
                "leader_level_no = 3, leader_score = 150, client_avg_score = 120, self_score = 100 " +
                "WHERE sheet_id = ? AND element_id = ?", sheetId, elementSkip);

        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 0 WHERE id = ?", elementSkip);
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        for (String col : new String[]{"standard_score", "category_weight", "leader_level_no",
                "leader_score", "client_avg_score", "self_score"}) {
            assertNull(rowScore(sheetId, elementSkip, col), "停用后残留列应被清空：" + col);
        }
        Long ratingKept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_sheet_rating WHERE sheet_id = ? AND element_id = ?",
                Long.class, sheetId, elementSkip);
        assertEquals(0, ratingKept, "（前置）本用例未种评级");
    }

    @Test
    @DisplayName("有 EXTERNAL 评级的停用行不清冗余分（recalc v5 计分口径行，清了会造成重算前空白窗口）")
    void externalRatedDeactivatedRowKeepsRedundantScores() {
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);
        seedRequirement();
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = 200, leader_score = 150 " +
                "WHERE sheet_id = ? AND element_id = ?", sheetId, elementSkip);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, level_no, score, source, " +
                "original_score) VALUES (?, ?, 'LEADER', 3, 150, 'EXTERNAL', 75)", sheetId, elementSkip);

        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 0 WHERE id = ?", elementSkip);
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        assertClose(200, rowScore(sheetId, elementSkip, "standard_score"));
        assertClose(150, rowScore(sheetId, elementSkip, "leader_score"));
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
        long reqId = insertRequirement(jdbc, RESOURCE_ID, YEAR);
        addReqItem(jdbc, reqId, elementAssess, categoryId);
        addReqItem(jdbc, reqId, elementSkip, categoryId);
    }

    private List<Map<String, Object>> activeRows(long sheetId) {
        return jdbc.queryForList("SELECT * FROM uf_assess_sheet_row WHERE sheet_id = ? AND active = 1", sheetId);
    }

    private List<Map<String, Object>> allRows(long sheetId) {
        return jdbc.queryForList("SELECT * FROM uf_assess_sheet_row WHERE sheet_id = ?", sheetId);
    }

    private Map<Long, Integer> activeByElement(long sheetId) {
        Map<Long, Integer> m = new HashMap<>();
        for (Map<String, Object> row : allRows(sheetId)) {
            m.put(num(row.get("element_id")), (int) num(row.get("active")));
        }
        return m;
    }

    private BigDecimal rowScore(long sheetId, long elementId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM uf_assess_sheet_row WHERE sheet_id = ? AND element_id = ?",
                BigDecimal.class, sheetId, elementId);
    }

    private BigDecimal sheetStandard(long sheetId) {
        return jdbc.queryForObject("SELECT standard_score FROM uf_assess_sheet WHERE id = ?", BigDecimal.class, sheetId);
    }

    private static void assertClose(double expected, BigDecimal actual) {
        assertNotNull(actual, "数值不应为空");
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)), "expected " + expected + " but was " + actual);
    }

    private static void assertClose(double expected, BigDecimal actual, String message) {
        assertNotNull(actual, message);
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)), message + ": was " + actual);
    }
}
