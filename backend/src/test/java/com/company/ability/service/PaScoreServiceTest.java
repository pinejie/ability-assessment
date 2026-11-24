package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheet;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.vo.PaSheetScoreVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 算分测试（方案 17 §5.1 / §5.5）。
 *
 * <p>这里是整个重构里唯一「算错了没人看得出来」的地方：报告上只会显示一个数字和一句等级说明，
 * 中间过程不展示。所以每个口径都单独一条用例，且用方案自己的算例做基准。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评算分测试")
class PaScoreServiceTest {

    private static final long DIM = 1L;
    private static final long DEPT = 701L;
    private static final long TARGET = 7101L;
    private static final long LEADER = 7102L;
    private static final long RATER_A = 7103L;
    private static final long RATER_B = 7104L;

    @Autowired
    private PaScoreService scoreService;

    @Autowired
    private PaPostService postService;

    @Autowired
    private PaModelService modelService;


    @Autowired
    private JdbcTemplate jdbc;

    private Long periodId;
    private Long postId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        for (String t : List.of("uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet",
                "uf_pa_band", "uf_pa_item_level", "uf_pa_item", "uf_pa_category", "uf_pa_post",
                "uf_assess_task_rater", "uf_assess_task")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM uf_assess_period");
        jdbc.update("DELETE FROM HrmDepartment");

        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled) VALUES (?, ?, ?, '0')",
                DEPT, "人力资源部", "人力资源部");
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, 2026, 0, ?)", DIM);
        periodId = jdbc.queryForObject("SELECT id FROM uf_assess_period WHERE dimension_id = ?", Long.class, DIM);
        DimensionContext.set(DIM);
        postId = postService.createPost(post());
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    // ================================================================ 算例

    @Test
    @DisplayName("§5.5 算例：上级 4 + 360甲 4 + 360乙 3 → 项得分 3.6667，加权后 3.3889")
    void matchesPlanWorkedExample() {
        // 三项等权 8%：与方案算例一致
        Long item1 = createItem("沟通能力", 8, 1, 1);
        Long item2 = createItem("协作能力", 8, 1, 1);
        Long item3 = createItem("执行能力", 8, 1, 1);
        bands();

        Long sheetId = sheet(item1, item2, item3);
        rate(sheetId, item1, PaRating.TYPE_LEADER, LEADER, 4);
        rate(sheetId, item1, PaRating.TYPE_360, RATER_A, 4);
        rate(sheetId, item1, PaRating.TYPE_360, RATER_B, 3);
        // 另外两项只由上级评，凑成算例里的 3.5 / 3.0
        rate(sheetId, item2, PaRating.TYPE_LEADER, LEADER, 3.5);
        rate(sheetId, item3, PaRating.TYPE_LEADER, LEADER, 3);

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);

        assertEquals(0, dec("3.6667").compareTo(itemScore(sheetId, item1)),
                "项得分 = (4+4+3)/3，上级只有一票");
        assertEquals(0, dec("3.3889").compareTo(vo.getTotalScore()), "总得分 = Σ项×权重 / Σ权重");
        assertEquals("精进/能手", vo.getBandName(), "3.39 落在 [3,4)");
        assertEquals(3, vo.getScoredItemCount());
        assertTrue(vo.getScored());
    }

    @Test
    @DisplayName("算分同时落刻度名快照：按该项自己的等级分值切左闭右开区间")
    void writesItemLevelNameSnapshot() {
        // 等级 1/3/5 切出 [1,3) 末位、[3,5) 合格、[5,+∞) 优秀
        Long item1 = createItem("沟通能力", 50, 1, 0);
        Long item2 = createItem("协作能力", 50, 1, 0);
        bands();
        Long sheetId = sheet(item1, item2);
        rate(sheetId, item1, PaRating.TYPE_LEADER, LEADER, 4);   // [3,5) 合格
        rate(sheetId, item2, PaRating.TYPE_LEADER, LEADER, 2);   // [1,3) 末位

        scoreService.recalculate(sheetId);

        assertEquals("合格", itemLevelName(sheetId, item1), "4 分落在 [3,5)");
        assertEquals("末位", itemLevelName(sheetId, item2), "2 分落在 [1,3)，上界不含");
    }

    @Test
    @DisplayName("刻度名是算分时的快照：算完再改等级名与分值，库里的字不变")
    void itemLevelNameIsFrozenAtScoringTime() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);
        rate(sheetId, item, PaRating.TYPE_LEADER, LEADER, 4);
        scoreService.recalculate(sheetId);
        assertEquals("合格", itemLevelName(sheetId, item));

        // 等级改名 + 改分值：下一次重算才按新配置，已落的快照不回溯
        jdbc.update("UPDATE uf_pa_item_level SET level_name = '卓越', score = 9 WHERE item_id = ? AND score = 3",
                item);
        assertEquals("合格", itemLevelName(sheetId, item),
                "快照已冻结，改配置不该改历史报告读到的字");

        // 重算才跟着新配置走：4 分现在落在 [1,9) 的「末位」之间（最低档）→ 末位
        scoreService.recalculate(sheetId);
        assertEquals("末位", itemLevelName(sheetId, item), "重算按新配置重切");
    }

    @Test
    @DisplayName("没人评的项没有刻度名，不伪造一档")
    void itemLevelNameNullWhenUnrated() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);

        assertTrue(scoreService.recalculate(sheetId).getScored() == false);
        assertNull(itemLevelName(sheetId, item), "没出分就没有刻度名");
    }


    void weightedAverageIgnoresWeightTotal() {
        Long a = createItem("甲", 60, 1, 0);
        Long b = createItem("乙", 50, 1, 0);
        bands();

        Long sheetId = sheet(a, b);
        rate(sheetId, a, PaRating.TYPE_LEADER, LEADER, 4);
        rate(sheetId, b, PaRating.TYPE_LEADER, LEADER, 2);

        // 加权求和 = 4×60 + 2×50 = 340（荒谬）；加权平均 = (240+100)/110 = 3.0909
        assertEquals(0, dec("3.0909").compareTo(scoreService.recalculate(sheetId).getTotalScore()));
    }

    @Test
    @DisplayName("未评的主体不进分母：某 360 没评就是上级与其余 360 的平均")
    void unratedRaterLeavesDenominator() {
        Long item = createItem("沟通能力", 100, 1, 1);
        bands();
        Long sheetId = sheet(item);

        rate(sheetId, item, PaRating.TYPE_LEADER, LEADER, 4);
        rate(sheetId, item, PaRating.TYPE_360, RATER_A, 2);
        // RATER_B 没评

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);
        assertEquals(0, dec("3").compareTo(itemScore(sheetId, item)),
                "只按两个人平均，不能把没评的人按 0 算");
        assertEquals(1, rater360Count(sheetId, item), "360 参与人数应为 1");
        assertNotNull(vo.getTotalScore());
    }

    @Test
    @DisplayName("自评全程不参与：只落 selfScore，项得分与总得分都不受影响（口径 #13）")
    void selfRatingExcluded() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);

        rate(sheetId, item, PaRating.TYPE_LEADER, LEADER, 2);
        rate(sheetId, item, PaRating.TYPE_SELF, TARGET, 5);

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);
        assertEquals(0, dec("2").compareTo(itemScore(sheetId, item)), "自评 5 分不能抬高项得分");
        assertEquals(0, dec("2").compareTo(vo.getTotalScore()));
        assertEquals(0, dec("5").compareTo(vo.getSelfScore()), "自评总分照常展示");
    }

    @Test
    @DisplayName("全员都没评的项不计入分母，不能拉低总分")
    void unscoredItemNotCounted() {
        Long rated = createItem("沟通能力", 50, 1, 0);
        Long nobody = createItem("协作能力", 50, 1, 0);
        bands();
        Long sheetId = sheet(rated, nobody);

        rate(sheetId, rated, PaRating.TYPE_LEADER, LEADER, 4);

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);
        assertEquals(1, vo.getScoredItemCount());
        assertEquals(0, dec("4").compareTo(vo.getTotalScore()), "没评的项不进分母");
    }

    @Test
    @DisplayName("分值区间 [lower, upper)：下界闭上界开；按 2 位小数比对")
    void bandBoundaries() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);

        // 4.0001 展示 4.00，应命中 [4,∞) 而不是 [3,4)
        reRate(sheetId, item, 4);
        assertEquals("卓越/专家", scoreService.recalculate(sheetId).getBandName(), "下界闭：4 命中 [4,∞)");

        reRate(sheetId, item, 5);
        assertEquals("卓越/专家", scoreService.recalculate(sheetId).getBandName());

        reRate(sheetId, item, 3);
        assertEquals("精进/能手", scoreService.recalculate(sheetId).getBandName(), "下界闭：3 命中 [3,4)");

        reRate(sheetId, item, 3.5);
        assertEquals("精进/能手", scoreService.recalculate(sheetId).getBandName());
    }

    @Test
    @DisplayName("没配分值区间时等级说明留空，不抛异常（算分是批量动作）")
    void missingBandsLeavesGradeEmpty() {
        Long item = createItem("沟通能力", 100, 1, 0);
        Long sheetId = sheet(item);
        rate(sheetId, item, PaRating.TYPE_LEADER, LEADER, 4);

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);
        assertNull(vo.getBandName());
        assertNotNull(vo.getTotalScore(), "分数该算还是要算出来");
    }

    @Test
    @DisplayName("全员齐备才出分：finish 把任务置已完成，并合并评语")
    void finishMarksTaskDoneAndMergesComments() {
        Long item1 = createItem("沟通能力", 50, 1, 0);
        Long item2 = createItem("协作能力", 50, 1, 0);
        bands();
        Long sheetId = sheet(item1, item2);
        rate(sheetId, item1, PaRating.TYPE_LEADER, LEADER, 4);
        rate(sheetId, item2, PaRating.TYPE_LEADER, LEADER, 4);

        long taskId = task(sheetId);
        jdbc.update("INSERT INTO uf_assess_task_rater (task_id, rater_resource_id, rater_type, status, comment) " +
                "VALUES (?, ?, 'LEADER', 1, ?)", taskId, LEADER, "沟通还可以，协作需要更主动");
        jdbc.update("INSERT INTO uf_assess_task_rater (task_id, rater_resource_id, rater_type, status, comment) " +
                "VALUES (?, ?, 'RATER360', 1, ?)", taskId, RATER_A, "跨部门配合积极");
        jdbc.update("INSERT INTO uf_assess_task_rater (task_id, rater_resource_id, rater_type, status, comment) " +
                "VALUES (?, ?, 'SELF', 1, ?)", taskId, TARGET, "自评不当评语");

        PaSheetScoreVO vo = scoreService.finish(taskId);

        assertTrue(vo.getScored());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, taskId),
                "全员齐备后任务应收口为已完成");
        String comment = jdbc.queryForObject(
                "SELECT comment_text FROM uf_pa_sheet WHERE id = ?", String.class, sheetId);
        assertNotNull(comment);
        assertTrue(comment.startsWith("沟通还可以"), "上级评语排在最前");
        assertTrue(comment.contains("跨部门配合积极"));
        assertFalse(comment.contains("自评不当评语"), "自评不写评语");
    }

    @Test
    @DisplayName("markPartial 不把已算分的页面降级")
    void markPartialKeepsScoredSheet() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);
        rate(sheetId, item, PaRating.TYPE_LEADER, LEADER, 4);
        scoreService.recalculate(sheetId);

        scoreService.markPartial(sheetId);
        assertEquals(2, jdbc.queryForObject("SELECT status FROM uf_pa_sheet WHERE id = ?", Integer.class, sheetId));
    }

    @Test
    @DisplayName("§5.3 出分前置：每项至少一个主体打分，否则该项不计入分母")
    void itemWithOnlySelfRatingIsNotScored() {
        Long item = createItem("沟通能力", 100, 1, 0);
        bands();
        Long sheetId = sheet(item);
        rate(sheetId, item, PaRating.TYPE_SELF, TARGET, 5);

        PaSheetScoreVO vo = scoreService.recalculate(sheetId);
        assertNull(itemScore(sheetId, item), "只有自评的项没有项得分");
        assertFalse(vo.getScored(), "一个项都算不出时不出总分");
        assertNull(vo.getTotalScore());
    }

    // ================================================================ 辅助

    private com.company.ability.dto.PaPostCreateDTO post() {
        com.company.ability.dto.PaPostCreateDTO dto = new com.company.ability.dto.PaPostCreateDTO();
        dto.setDepartmentId(DEPT);
        dto.setPostName("人事专员");
        return dto;
    }

    private Long createItem(String name, int weight, int leader, int rater360) {
        if (categoryId == null) {
            com.company.ability.dto.PaCategoryCreateDTO cat = new com.company.ability.dto.PaCategoryCreateDTO();
            cat.setCategoryName("通用能力");
            categoryId = modelService.createCategory(postId, cat);
        }

        com.company.ability.dto.PaItemCreateDTO dto = new com.company.ability.dto.PaItemCreateDTO();
        dto.setCategoryId(categoryId);
        dto.setItemName(name);
        dto.setWeight(BigDecimal.valueOf(weight));
        dto.setRaterLeader(leader);
        dto.setRater360(rater360);
        dto.setLevels(List.of(level("末位", "1", 1), level("合格", "3", 2), level("优秀", "5", 3)));
        return modelService.createItem(postId, dto);
    }

    private com.company.ability.dto.PaItemLevelDTO level(String name, String score, int sort) {
        com.company.ability.dto.PaItemLevelDTO dto = new com.company.ability.dto.PaItemLevelDTO();
        dto.setLevelName(name);
        dto.setScore(new BigDecimal(score));
        dto.setSort(sort);
        return dto;
    }

    private void bands() {
        List<PaBandSaveDTO> bands = new ArrayList<>();
        // 最高一档上界留空表示 +∞（服务层强制），否则高于最高分的得分命中不到任何区间
        bands.add(band("不合格", null, "2", 0));
        bands.add(band("待提升", "2", "3", 1));
        bands.add(band("精进/能手", "3", "4", 2));
        bands.add(band("卓越/专家", "4", null, 3));
        modelService.saveBands(postId, bands);
    }

    private PaBandSaveDTO band(String name, String lower, String upper, int sort) {
        PaBandSaveDTO dto = new PaBandSaveDTO();
        dto.setBandName(name);
        dto.setLowerBound(lower == null ? null : new BigDecimal(lower));
        dto.setUpperBound(upper == null ? null : new BigDecimal(upper));
        dto.setSort(sort);
        return dto;
    }

    /** 直接落页面行：跳过发起链路，只测算分 */
    private Long sheet(Long... itemIds) {
        PaSheet sheet = new PaSheet();
        sheet.setCompanyId(1L);
        sheet.setDimensionId(DIM);
        sheet.setPeriodId(periodId);
        sheet.setResourceId(TARGET);
        sheet.setPostId(postId);
        sheet.setDepartmentId(DEPT);
        sheet.setDepartmentName("人力资源部");
        sheet.setPostName("人事专员");
        sheet.setStatus(0);
        sheet.setItemCount(itemIds.length);
        jdbc.update("INSERT INTO uf_pa_sheet (company_id, dimension_id, period_id, resource_id, post_id," +
                        " department_id, department_name, post_name, status, item_count) VALUES (?,?,?,?,?,?,?,?,0,?)",
                1L, DIM, periodId, TARGET, postId, DEPT, "人力资源部", "人事专员", itemIds.length);
        Long sheetId = jdbc.queryForObject(
                "SELECT id FROM uf_pa_sheet WHERE period_id = ? AND resource_id = ?", Long.class, periodId, TARGET);

        int sort = 0;
        for (Long itemId : itemIds) {
            jdbc.update("INSERT INTO uf_pa_sheet_item (dimension_id, sheet_id, item_id, category_id, category_name," +
                            " item_code, item_name, sort_no, weight, rater_leader, rater_360) " +
                            "SELECT ?, ?, ?, i.category_id, c.category_name, i.item_code, i.item_name, ?, i.weight," +
                            " i.rater_leader, i.rater_360 FROM uf_pa_item i" +
                            " LEFT JOIN uf_pa_category c ON c.id = i.category_id WHERE i.id = ?",
                    DIM, sheetId, itemId, sort++, itemId);
        }
        return sheetId;
    }

    /** 重新打分：同一个上级对同一项重复评级时，先清掉旧行（生产库唯一索引会挡住重复插入） */
    private void reRate(Long sheetId, Long itemId, Number score) {
        jdbc.update("DELETE FROM uf_pa_rating WHERE sheet_id = ? AND item_id = ?", sheetId, itemId);
        rate(sheetId, itemId, PaRating.TYPE_LEADER, LEADER, score);
    }

    private void rate(Long sheetId, Long itemId, String type, long rater, Number score) {
        jdbc.update("INSERT INTO uf_pa_rating (dimension_id, sheet_id, item_id, rater_type, rater_resource_id," +
                        " level_id, level_name, score) VALUES (?,?,?,?,?,?,?,?)",
                DIM, sheetId, itemId, type, rater, -1L, "测试档", new BigDecimal(score.toString()));
    }

    private long task(Long sheetId) {
        jdbc.update("INSERT INTO uf_assess_task (company_id, dimension_id, period_id, sheet_id, resource_id, status," +
                        " rating_count, client_count) VALUES (1, ?, ?, ?, ?, 0, 2, 1)",
                DIM, periodId, sheetId, TARGET);
        return jdbc.queryForObject("SELECT id FROM uf_assess_task WHERE sheet_id = ?", Long.class, sheetId);
    }

    private BigDecimal itemScore(Long sheetId, Long itemId) {
        return jdbc.queryForObject("SELECT item_score FROM uf_pa_sheet_item WHERE sheet_id = ? AND item_id = ?",
                BigDecimal.class, sheetId, itemId);
    }

    private String itemLevelName(Long sheetId, Long itemId) {
        return jdbc.queryForObject(
                "SELECT item_level_name FROM uf_pa_sheet_item WHERE sheet_id = ? AND item_id = ?",
                String.class, sheetId, itemId);
    }

    private int rater360Count(Long sheetId, Long itemId) {
        Integer n = jdbc.queryForObject(
                "SELECT rater360_count FROM uf_pa_sheet_item WHERE sheet_id = ? AND item_id = ?",
                Integer.class, sheetId, itemId);
        return n == null ? 0 : n;
    }

    private static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }
}
