package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessSheetVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static com.company.ability.h2test.SheetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 个人测评报告：免评要素相关口径。
 * - 混合类别：得分只累计计分口径行（免评行不污染）；
 * - 纯免评类别：作为占位类别出现（任职标准可见），分值全 null，且不参与合计一致性核对；
 * - verify 闸门：占位类别存在时页面头与类别合计不一致仍必须拦截。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("个人测评报告：免评要素口径")
class AssessReportServiceTest {

    @Autowired
    private AssessReportService reportService;

    @Autowired
    private AssessSheetSyncService syncService;

    @Autowired
    private JdbcTemplate jdbc;

    private long categoryId;
    private long elementAssess;
    private long elementSkip;       // 与需评要素同类别（混合）
    private long onlySkipCategory;  // 纯免评类别
    private long elementSkipOnly;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        categoryId = insertCategory(jdbc, "同步类别");
        onlySkipCategory = insertCategory(jdbc, "纯免评类别");

        elementAssess = insertElement(jdbc, "需评价要素", categoryId, 1);
        elementSkip = insertElement(jdbc, "免评要素", categoryId, 0);
        elementSkipOnly = insertElement(jdbc, "孤寡免评要素", onlySkipCategory, 0);

        insertPeriod(jdbc, YEAR);
        insertResource(jdbc, RESOURCE_ID);
        insertCategoryWeight(jdbc, categoryId, 100);
        insertCategoryWeight(jdbc, onlySkipCategory, 100);
        insertLevelScores(jdbc);
        jdbc.update("INSERT INTO uf_assess_weight_assessor (company_id, dimension_id, assessor_code, weight) " +
                "VALUES (1, 1, 'LEADER', 40), (1, 1, 'CLIENT', 30), (1, 1, 'SELF', 30)");
        jdbc.update("INSERT INTO uf_assess_grade_level (company_id, dimension_id, level_name, lower_bound, upper_bound, sort_order) " +
                "VALUES (1, 1, '优秀', NULL, 100, 1)");

        long reqId = insertRequirement(jdbc, RESOURCE_ID, YEAR);
        addReqItem(jdbc, reqId, elementAssess, categoryId);
        addReqItem(jdbc, reqId, elementSkip, categoryId);
        addReqItem(jdbc, reqId, elementSkipOnly, onlySkipCategory);

        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        // 模拟 recalc 对计分口径行的回写（H2 无存储过程）：需评行得分 + 页面头合计；
        // 免评行种上残留脏值（写侧清理之外的存量数据兜底验证）
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("UPDATE uf_assess_sheet_row SET leader_level_no = 4, leader_score = 300, " +
                "self_level_no = 4, self_score = 330, client_avg_score = 270 WHERE sheet_id = ? AND element_id = ?",
                sheetId, elementAssess);
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = 99, category_weight = 9, " +
                "leader_score = 99, self_score = 99, client_avg_score = 99 " +
                "WHERE sheet_id = ? AND element_id = ?", sheetId, elementSkip);
        jdbc.update("UPDATE uf_assess_sheet SET standard_score = 400, leader_score = 300, client_score = 270, " +
                "self_score = 330, actual_score = 300, score_rate = 75, grade_name = '优秀' WHERE id = ?", sheetId);
    }

    @Test
    @DisplayName("混合类别只累计计分行；纯免评类别为占位行（分值 null）；核对通过")
    void mixedAndPlaceholderCategories() {
        AssessSheetVO.Report r = reportService.get(sheetId(jdbc, RESOURCE_ID, YEAR));
        assertEquals(2, r.getCategories().size());

        AssessSheetVO.CategoryRow mixed = findCategory(r, "同步类别");
        assertEquals(0, new BigDecimal("400").compareTo(mixed.getStandardScore()), "免评行残留 99 不得混入标准分合计");
        assertEquals(0, new BigDecimal("300").compareTo(mixed.getActualScore()));
        assertEquals("优秀", mixed.getGradeName());

        AssessSheetVO.CategoryRow placeholder = findCategory(r, "纯免评类别");
        assertNull(placeholder.getStandardScore());
        assertNull(placeholder.getLeaderScore());
        assertNull(placeholder.getActualScore());
        assertNull(placeholder.getGradeName());
        assertEquals(Boolean.TRUE, r.getComplete(), "占位类别不影响完成态（合计来自页面头）");
    }

    @Test
    @DisplayName("占位类别存在时 verify 仍生效：页面头与类别合计不一致必须拦截")
    void verifyStillGuardsWithPlaceholderCategory() {
        jdbc.update("UPDATE uf_assess_sheet SET standard_score = 999 WHERE id = ?", sheetId(jdbc, RESOURCE_ID, YEAR));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.get(sheetId(jdbc, RESOURCE_ID, YEAR)));
        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
    }

    private static AssessSheetVO.CategoryRow findCategory(AssessSheetVO.Report r, String name) {
        return r.getCategories().stream().filter(c -> name.equals(c.getCategoryName())).findFirst()
                .orElseThrow(() -> new AssertionError("报告缺少类别：" + name));
    }
}
