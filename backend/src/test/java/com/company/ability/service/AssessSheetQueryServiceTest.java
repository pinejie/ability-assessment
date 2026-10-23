package com.company.ability.service;

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
import java.util.List;

import static com.company.ability.h2test.SheetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评汇总明细查询（detail）对「流程中需评价」开关各状态的展示口径：
 * 需评价行进流程；免评且在要求中的行按任职标准展示（nonFlow 标记、得分掩蔽、权重取配置）；
 * 有 EXTERNAL 评级的行带外部导入标签且分值不掩蔽；
 * 不在要求中的停用行、以及 flag 改回需评价但未重同步的停用行都不展示（防残留值泄漏）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评汇总明细查询：免评要素展示口径")
class AssessSheetQueryServiceTest {

    @Autowired
    private AssessSheetQueryService queryService;

    @Autowired
    private AssessSheetSyncService syncService;

    @Autowired
    private JdbcTemplate jdbc;

    private long categoryId;
    private long elementAssess;
    private long elementSkip;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
        categoryId = insertCategory(jdbc, "查询类别");
        elementAssess = insertElement(jdbc, "需评价要素", categoryId, 1);
        elementSkip = insertElement(jdbc, "免评要素", categoryId, 0);

        insertPeriod(jdbc, YEAR);
        insertResource(jdbc, RESOURCE_ID);
        insertCategoryWeight(jdbc, categoryId, 100);
        insertLevelScores(jdbc);
        seedRequirement();
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);
    }

    @Test
    @DisplayName("免评要素在要求中：明细展示该行，nonFlow 标记、要求等级可见、残留得分掩蔽、权重取当前配置")
    void skipRowInRequirementShownMasked() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        // 模拟停用前的残留值（标准分/各类得分），验证展示层掩蔽（存量数据双保险，写侧清理见 Sync 测试）
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = 99, category_weight = 9, " +
                "leader_level_no = 5, leader_score = 99, self_level_no = 5, self_score = 99, client_avg_score = 99 " +
                "WHERE sheet_id = ? AND element_id = ?", sheetId, elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertEquals(2, d.getRows().size(), "明细应包含需评价行与免评行");

        AssessSheetVO.Row skip = findRow(d, elementSkip);
        assertEquals(Boolean.TRUE, skip.getNonFlow());
        assertEquals(Boolean.FALSE, skip.getExternal());
        assertEquals(2, skip.getRequiredLevelNo(), "免评行保留任职标准要求等级");
        assertNull(skip.getStandardScore(), "免评行残留标准分应被掩蔽");
        assertNull(skip.getLeaderScore());
        assertNull(skip.getSelfScore());
        assertNull(skip.getClientAvgScore());
        // 权重非分值：取当前类别配置（setUp 写入 100），不受行上残留 9 影响
        assertEquals(0, skip.getCategoryWeight().compareTo(new BigDecimal("100")));

        AssessSheetVO.Row assess = findRow(d, elementAssess);
        assertEquals(Boolean.FALSE, assess.getNonFlow());
        assertNotNull(assess.getStandardScore());
    }

    @Test
    @DisplayName("不在要求中的停用行不展示（含免评要素被移出要求的情况）")
    void removedRowNotShown() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        long elementGone = insertElement(jdbc, "已移出要求的要素", categoryId, 0);
        jdbc.update("INSERT INTO uf_assess_sheet_row (sheet_id, category_id, category_name, element_id, " +
                "element_name, required_level_no, sort_no, active, dimension_id) " +
                "VALUES (?, ?, '查询类别', ?, '已移出要求的要素', 2, 9, 0, 1)", sheetId, categoryId, elementGone);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertNull(findRowOrNull(d, elementGone), "已不在要求中且无外部评级的行不应出现");
    }

    @Test
    @DisplayName("flag 改回需评价但未重同步（active=0、无外部评级）：不展示——防停用残留值混入合计与报告校验")
    void flagBackToFlowWithoutResyncIsHidden() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("UPDATE uf_assess_sheet_row SET standard_score = 99, leader_score = 99 " +
                "WHERE sheet_id = ? AND element_id = ?", sheetId, elementSkip);
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertNull(findRowOrNull(d, elementSkip), "未重同步的 flag=1 停用行不得以残留分展示（recalc 页面头不含它，会破坏合计一致性）");
        assertEquals(1, d.getRows().size());
    }

    @Test
    @DisplayName("免评要素导入外部评级后：按 external 行展示，得分列不再掩蔽")
    void externalRatedSkipRowShownWithScores() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, client_seq, " +
                "level_no, score, source, original_score) VALUES (?, ?, 'LEADER', NULL, 3, 60, 'EXTERNAL', 7.5)",
                sheetId, elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        AssessSheetVO.Row skip = findRow(d, elementSkip);
        assertEquals(Boolean.FALSE, skip.getNonFlow(), "已导入外部评级的免评行不再是未计分行");
        assertEquals(Boolean.TRUE, skip.getExternal());
        assertEquals(new BigDecimal("7.50"), scale2(skip.getOriginalScore()), "外部导入原始分应可见");
    }

    @Test
    @DisplayName("flag=0 但同步未重跑（行仍 active=1）且有外部评级：与 recalc v5 同向，带外部导入标签（来源不失真）")
    void flowActiveRowWithExternalIsTagged() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("UPDATE uf_assess_sheet_row SET active = 1 WHERE sheet_id = ? AND element_id = ?",
                sheetId, elementSkip);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, level_no, score, source, " +
                "original_score) VALUES (?, ?, 'LEADER', 3, 60, 'EXTERNAL', 75)", sheetId, elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        AssessSheetVO.Row row = findRow(d, elementSkip);
        assertEquals(Boolean.TRUE, row.getExternal(), "v5 按 flag 认定外部口径，标签必须一致");
        assertEquals(Boolean.FALSE, row.getNonFlow());
    }

    @Test
    @DisplayName("要素改回需评价但行未重同步（active=0 且有外部评级）：仍展示并保留外部导入标签")
    void toggledBackFlowFlagKeepsExternalLabel() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, level_no, score, source, " +
                "original_score) VALUES (?, ?, 'LEADER', 3, 60, 'EXTERNAL', 7.5)", sheetId, elementSkip);
        jdbc.update("UPDATE uf_ability_element SET assess_in_flow = 1 WHERE id = ?", elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        AssessSheetVO.Row row = findRow(d, elementSkip);
        assertEquals(Boolean.TRUE, row.getExternal(), "停用行+外部评级 → 外部导入标签不因 flag 翻转丢失");
        assertEquals(Boolean.FALSE, row.getNonFlow());
    }

    @Test
    @DisplayName("客户得分合计只累计计分口径行：免评行的残留 FLOW 客户评级不计入")
    void seqTotalsExcludeNonFlowRow() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, rater_resource_id, " +
                "client_seq, level_no, score) VALUES (?, ?, 'LEADER', NULL, NULL, 3, 50)", sheetId, elementAssess);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, rater_resource_id, " +
                "client_seq, level_no, score) VALUES (?, ?, 'CLIENT', 200, 1, 3, 30)", sheetId, elementAssess);
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, rater_resource_id, " +
                "client_seq, level_no, score) VALUES (?, ?, 'CLIENT', 200, 1, 5, 99)", sheetId, elementSkip);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertEquals(List.of(1), d.getClientSeqs());
        assertEquals(1, d.getClientSeqTotals().size());
        assertEquals(0, d.getClientSeqTotals().get(0).compareTo(new BigDecimal("30")),
                "合计应只含需评价行的 30 分，不含免评行残留的 99 分，实际：" + d.getClientSeqTotals().get(0));

        AssessSheetVO.Row skip = findRow(d, elementSkip);
        assertTrue(skip.getClients().stream().allMatch(c -> c.getLevelNo() == null && c.getScore() == null),
                "免评行不应展示残留的客户评级");
    }

    @Test
    @DisplayName("明细排序：类别排序号在前、要素排序号在后，不随要求录入顺序")
    void orderedByCategoryThenElementSort() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        // 新类别"前置类别"（sort=1）含一个需评价要素；原类别 sort 调为 5，
        // 并把类别内排序号设为 免评=1、需评=2 —— 而要求录入顺序是 需评在前免评在后
        jdbc.update("UPDATE uf_ability_category SET sort = 5 WHERE id = ?", categoryId);
        long catFront = insertCategory(jdbc, "前置类别");
        jdbc.update("UPDATE uf_ability_category SET sort = 1 WHERE id = ?", catFront);
        long elementFront = insertElement(jdbc, "前置要素", catFront, 1);
        jdbc.update("UPDATE uf_ability_element SET sort = 1 WHERE id = ?", elementSkip);
        jdbc.update("UPDATE uf_ability_element SET sort = 2 WHERE id = ?", elementAssess);

        addReqItem(jdbc, reqId(), elementFront, catFront);
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertEquals(3, d.getRows().size());
        assertEquals(List.of(elementFront, elementSkip, elementAssess),
                d.getRows().stream().map(AssessSheetVO.Row::getElementId).toList(),
                "应依次为：类别sort=1 的前置要素 → 类别sort=5 内要素sort=1 的免评要素 → 要素sort=2 的需评要素");
    }

    @Test
    @DisplayName("类别排序号相同时以类别ID定序：同类别行连续，不要素排序号交错拆块")
    void sameCategorySortKeepsCategoryBlockTogether() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        // 新类别（与原类别同为默认 sort=0）含一个需评要素；要素排序号故意交错：
        // 原类别内 免评=1、需评=9；新类别要素=5 —— 若无类别平局键输出会变成 免评、新、需评（拆块）
        long catB = insertCategory(jdbc, "并列类别");
        long elementB = insertElement(jdbc, "并列要素", catB, 1);
        jdbc.update("UPDATE uf_ability_element SET sort = 5 WHERE id = ?", elementB);
        jdbc.update("UPDATE uf_ability_element SET sort = 1 WHERE id = ?", elementSkip);
        jdbc.update("UPDATE uf_ability_element SET sort = 9 WHERE id = ?", elementAssess);
        addReqItem(jdbc, reqId(), elementB, catB);
        syncService.syncRequirement(RESOURCE_ID, YEAR, true);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertEquals(3, d.getRows().size());
        assertEquals(List.of(elementSkip, elementAssess, elementB),
                d.getRows().stream().map(AssessSheetVO.Row::getElementId).toList(),
                "同类别行必须连续（类别ID平局键），类别内再按要素排序号");
    }

    @Test
    @DisplayName("免评行展示限本维度：要求仅在其他维度时不泄漏")
    void requirementMatchIsDimensionScoped() {
        long sheetId = sheetId(jdbc, RESOURCE_ID, YEAR);
        // 维度 2 的同要素要求（不属于该页面维度），其存在不应让本维度明细显示"额外免评要素"行
        long elementOther = insertElement(jdbc, "额外免评要素", categoryId, 0);
        long reqOther = insertReturning(jdbc,
                "INSERT INTO uf_user_ability_req_new (resource_id, assess_year, dimension_id) VALUES ("
                        + RESOURCE_ID + ", " + YEAR + ", 2)",
                "SELECT id FROM uf_user_ability_req_new WHERE resource_id = " + RESOURCE_ID + " AND dimension_id = 2");
        jdbc.update("INSERT INTO uf_user_ability_req_item (req_id, element_id, category_id, level_id) VALUES (?, ?, ?, ?)",
                reqOther, elementOther, categoryId, insertLevel2(jdbc, elementOther));
        jdbc.update("INSERT INTO uf_assess_sheet_row (sheet_id, category_id, category_name, element_id, " +
                "element_name, required_level_no, sort_no, active, dimension_id) " +
                "VALUES (?, ?, '查询类别', ?, '额外免评要素', 2, 9, 0, 1)", sheetId, categoryId, elementOther);

        AssessSheetVO.Detail d = queryService.detail(sheetId);
        assertNull(findRowOrNull(d, elementOther), "要求仅在维度 2 的要素不得出现在本维度明细");
        assertEquals(2, d.getRows().size());
    }

    // ========== 种子与查询辅助 ==========

    private void seedRequirement() {
        long reqId = insertRequirement(jdbc, RESOURCE_ID, YEAR);
        addReqItem(jdbc, reqId, elementAssess, categoryId);
        addReqItem(jdbc, reqId, elementSkip, categoryId);
    }

    private long reqId() {
        return idOf(jdbc, "SELECT id FROM uf_user_ability_req_new WHERE resource_id = " + RESOURCE_ID +
                " AND assess_year = " + YEAR);
    }

    private AssessSheetVO.Row findRow(AssessSheetVO.Detail d, long elementId) {
        AssessSheetVO.Row row = findRowOrNull(d, elementId);
        assertNotNull(row, "明细应包含要素 " + elementId);
        return row;
    }

    private AssessSheetVO.Row findRowOrNull(AssessSheetVO.Detail d, long elementId) {
        return d.getRows().stream().filter(r -> r.getElementId() == elementId).findFirst().orElse(null);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
