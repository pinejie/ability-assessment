package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.entity.PaSheet;
import com.company.ability.exception.BusinessException;
import com.company.ability.support.PaTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测评页装配测试（方案 17 §4.7）。
 *
 * <p>装配是发起链路里唯一读配置的地方，两个失败闸门（没映射 / 岗位没启用项）
 * 决定了「绝不会发出去的测评是空页面」。另外要锁住两条不那么显眼的规则：</p>
 * <ul>
 *   <li><b>一人一年一页</b>：重新发起复用同一页，报告上不会出现两份同年报告；</li>
 *   <li><b>每次发起重建行并清分</b>：旧分数 + 新评级混在一页上比没有分数更误导。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评页装配测试")
class PaSheetServiceTest {

    private static final long DEPT = PaTestData.DEPT;

    @Autowired
    private PaSheetService sheetService;
    @Autowired
    private PaPostService postService;
    @Autowired
    private PaModelService modelService;
    @Autowired
    private JdbcTemplate jdbc;

    private PaTestData data;
    private long periodId;
    private long postId;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        DimensionContext.set(PaTestData.DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    @Test
    @DisplayName("装配：按岗位启用项快照成页面行，停用项不进快照")
    void snapshotsEnabledItemsOnly() {
        long cat = data.category(modelService, postId, "通用能力");
        data.item(modelService, postId, cat, "沟通能力", "50", 1, 0);
        long disabled = data.item(modelService, postId, cat, "停用项", "50", 1, 0);
        jdbc.update("UPDATE uf_pa_item SET status = 0 WHERE id = ?", disabled);

        PaSheet sheet = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId);

        assertEquals(postId, sheet.getPostId());
        assertEquals(DEPT, sheet.getDepartmentId());
        assertEquals("人力资源部", sheet.getDepartmentName());
        assertEquals(1, sheet.getItemCount(), "停用项不该进快照，否则页面会多出一个没人该评的空卡片");
        assertEquals(PaTestData.LEADER, sheet.getLeaderResourceId(), "上级取自 OA 组织关系");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_name, category_name, weight, rater_leader, rater_360 FROM uf_pa_sheet_item" +
                " WHERE sheet_id = ? ORDER BY sort_no", sheet.getId());
        assertEquals(1, rows.size());
        assertEquals("沟通能力", rows.get(0).get("item_name"));
        assertEquals("通用能力", rows.get(0).get("category_name"));
        assertEquals(1, ((Number) rows.get(0).get("rater_leader")).intValue());
        assertEquals(0, ((Number) rows.get(0).get("rater_360")).intValue());
    }

    @Test
    @DisplayName("装配：岗位不存在时拒绝发起，不退回全局能力库")
    void rejectsWhenPostMissing() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, 999999L));
        assertEquals(40602, e.getCode());
        assertTrue(e.getMessage().contains("岗位不存在"), e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_sheet"), "闸门要在写库之前挡住");
    }

    @Test
    @DisplayName("装配：岗位下没有启用项时拒绝发起")
    void rejectsWhenModelEmpty() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId));
        assertEquals(40602, e.getCode());
        assertTrue(e.getMessage().contains("能力项"), e.getMessage());
    }

    @Test
    @DisplayName("装配：年度不存在时给出「先建年度」的提示")
    void rejectsUnknownPeriod() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> sheetService.prepare(1999, PaTestData.TARGET, postId));
        assertEquals(40603, e.getCode());
    }

    @Test
    @DisplayName("一人一年一页：重新发起复用同一页并重建行，不新增第二页")
    void reissueReusesSameSheet() {
        long cat = data.category(modelService, postId, "通用能力");
        long item = data.item(modelService, postId, cat, "沟通能力", "100", 1, 1);

        PaSheet first = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId);
        // 造一个「上一轮已算完」的状态：重发必须把它清干净，否则新一轮会是旧分 + 新评级的混合体
        jdbc.update("UPDATE uf_pa_sheet SET status = 2, total_score = 4.5, band_name = '优秀'," +
                " comment_text = '上一轮评语' WHERE id = ?", first.getId());

        PaSheet second = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId);

        assertEquals(first.getId(), second.getId(), "同一人同一年度只有一页");
        assertEquals(1, count("SELECT COUNT(*) FROM uf_pa_sheet"), "不能新增第二页");
        assertEquals(0, second.getStatus());
        assertNull(second.getTotalScore());
        assertNull(second.getBandName());
        assertNull(second.getCommentText());
        assertEquals(1, count("SELECT COUNT(*) FROM uf_pa_sheet_item WHERE sheet_id = " + first.getId()),
                "行要重建而不是累加");
    }

    @Test
    @DisplayName("换岗位重发：仍复用同一页，但页面上的岗位与能力项整体换成新岗位的")
    void reissueWithAnotherPostSwapsPost() {
        long cat = data.category(modelService, postId, "通用能力");
        data.item(modelService, postId, cat, "沟通能力", "100", 1, 0);
        PaSheet first = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId);

        long otherPost = data.seedPost(postService, modelService, "招聘专员");
        long otherCat = data.category(modelService, otherPost, "专业能力");
        data.item(modelService, otherPost, otherCat, "渠道运营", "100", 1, 1);

        PaSheet second = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, otherPost);

        assertEquals(first.getId(), second.getId(), "换岗位也是同一个人同一年，仍是一页");
        assertEquals(otherPost, second.getPostId());
        assertEquals("招聘专员", second.getPostName());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_name FROM uf_pa_sheet_item WHERE sheet_id = ?", second.getId());
        assertEquals(1, rows.size(), "行要按新岗位重建，不能留下旧岗位的行");
        assertEquals("渠道运营", rows.get(0).get("item_name"));
    }

    @Test
    @DisplayName("快照隔离：装配后改岗位权重，已装配页面不跟着变（口径 #15）")
    void snapshotIsolatedFromLaterPostEdits() {
        long cat = data.category(modelService, postId, "通用能力");
        long item = data.item(modelService, postId, cat, "沟通能力", "40", 1, 0);
        PaSheet sheet = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId);

        jdbc.update("UPDATE uf_pa_item SET weight = 90, item_name = '沟通能力（改）' WHERE id = ?", item);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_name, weight FROM uf_pa_sheet_item WHERE sheet_id = ?", sheet.getId());
        assertEquals("沟通能力", rows.get(0).get("item_name"));
        assertEquals(0, new java.math.BigDecimal("40").compareTo((java.math.BigDecimal) rows.get(0).get("weight")));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
