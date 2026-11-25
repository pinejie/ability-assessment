package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaFillDTO;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.support.PaTestData;
import com.company.ability.vo.PaFillPageVO;
import com.company.ability.vo.PaTaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 360 按能力项指派（sql/60，2026-11-23）。
 *
 * <p>改动的要害不是「多选了几个人」，而是<b>一个人只能看到自己被指派的项</b>。
 * 这条一旦松动，症状是：某人提交成功、报告上那一项却有他的分 —— 但他本该只评两项，
 * 多出来的分没人能解释，也没法从界面看出来。所以这里逐条钉住可见范围。</p>
 *
 * <p>另一条是<b>存量任务的兼容</b>：本改造之前发出的任务没有指派行，
 * 当时的口径是「这批人评全部 360 项」。回填指派行会篡改历史，因此填写侧必须能自己回退。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("360 按项指派测试")
class PaRaterItemAssignTest {

    @Autowired
    private PaFillService fillService;
    @Autowired
    private PaTaskService taskService;
    @Autowired
    private PaPostService postService;
    @Autowired
    private PaModelService modelService;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DimensionIntegrationService integrationService;

    private PaTestData data;
    private long postId;
    private long item360a;
    private long item360b;
    private long itemLeaderOnly;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc, integrationService);
        data.reset();
        data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        long cat = data.category(modelService, postId, "通用能力");
        itemLeaderOnly = data.item(modelService, postId, cat, "沟通能力", "30", 1, 0);
        item360a = data.item(modelService, postId, cat, "协作能力", "35", 1, 1);
        item360b = data.item(modelService, postId, cat, "数据分析", "35", 1, 1);
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    @Test
    @DisplayName("按项指派：360 只看到分给自己的项；权重徽标仍按整岗位模型算")
    void rater360SeesOnlyAssignedItems() {
        PaTaskVO.CreateResult created = createAssigned();
        String tokenA = tokenOf(created, PaTestData.RATER_A);

        PaTestData.login(PaTestData.RATER_A);
        PaFillPageVO page = fillService.page(tokenA);

        assertEquals(List.of(item360a), itemIds(page), "王甲只被指派了「协作能力」");
        // 徽标的语义是「岗位模型配得对不对」，不是「我这份占多少」。
        // 按可见项算的话，每个只分到一项的 360 都会看到「不等于 100%」的黄色警告。
        assertEquals(0, new java.math.BigDecimal("100").compareTo(page.getWeightTotal()),
                "权重合计按整份页面算，与本人分到几项无关");
    }

    @Test
    @DisplayName("按项指派：提交只覆盖自己被指派的项，缺项照样拒绝")
    void submitValidatesAgainstAssignedItems() {
        PaTaskVO.CreateResult created = createAssigned();
        String tokenB = tokenOf(created, PaTestData.RATER_B);

        PaTestData.login(PaTestData.RATER_B);
        // 赵乙被指派两项，只交一项 → 拒绝（「还差 1 个能力项」）
        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(tokenB, submitOf(item360a)));
        assertEquals(40404, e.getCode());
        assertTrue(e.getMessage().contains("还差 1"), e.getMessage());

        PaTaskVO.SubmitResult ok = fillService.submit(tokenB, submitOf(item360a, item360b));
        assertTrue(ok.getSubmitted());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_pa_rating WHERE rater_type = 'RATER360' AND rater_resource_id = ?",
                Integer.class, PaTestData.RATER_B));
    }

    @Test
    @DisplayName("按项指派：没被指派的项写不进来（不是靠前端藏，后端也得拒）")
    void rejectsRatingOnUnassignedItem() {
        PaTaskVO.CreateResult created = createAssigned();
        String tokenA = tokenOf(created, PaTestData.RATER_A);

        PaTestData.login(PaTestData.RATER_A);
        // 王甲只被指派 item360a；把两项都交上去 → 多出一项，拒绝
        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(tokenA, submitOf(item360a, item360b)));
        assertEquals(40404, e.getCode());
        assertTrue(e.getMessage().contains("多出 1"), e.getMessage());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_pa_rating WHERE rater_resource_id = ?", Integer.class, PaTestData.RATER_A));
    }

    @Test
    @DisplayName("存量任务：没有指派行时回退到快照口径，360 仍看到全部 360 项")
    void legacyTaskFallsBackToSnapshot() {
        // 旧入参发起 = 这批人评全部 360 项；把指派行删掉模拟 sql/60 之前发出的任务
        PaTaskVO.CreateResult created = legacyCreate();
        jdbc.update("DELETE FROM uf_assess_task_rater_item");
        String tokenA = tokenOf(created, PaTestData.RATER_A);

        PaTestData.login(PaTestData.RATER_A);
        PaFillPageVO page = fillService.page(tokenA);

        assertEquals(List.of(item360a, item360b), itemIds(page),
                "老任务没有指派行，按页面行快照 rater_360=1 展开");
    }

    // ================================================================ 辅助

    private PaTaskVO.CreateResult createAssigned() {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360Items(List.of(
                rater360Item(item360a, PaTestData.RATER_A, PaTestData.RATER_B),
                rater360Item(item360b, PaTestData.RATER_B)));
        return taskService.create(req);
    }

    private PaTaskVO.CreateResult legacyCreate() {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360ResourceIds(List.of(PaTestData.RATER_A));
        return taskService.create(req);
    }

    private static PaTaskDTO.Rater360Item rater360Item(long itemId, Long... resourceIds) {
        PaTaskDTO.Rater360Item row = new PaTaskDTO.Rater360Item();
        row.setItemId(itemId);
        row.setResourceIds(List.of(resourceIds));
        return row;
    }

    private static String tokenOf(PaTaskVO.CreateResult created, long resourceId) {
        return created.getRaters().stream()
                .filter(l -> "RATER360".equals(l.getRaterType()) && l.getRaterResourceId() == resourceId)
                .findFirst().orElseThrow().getAccessToken();
    }

    private List<Long> itemIds(PaFillPageVO page) {
        List<Long> ids = new ArrayList<>();
        page.getItems().forEach(i -> ids.add(i.getItemId()));
        return ids;
    }

    /** 取项的第一档（sort 最小的那档）：与业务无关，只为凑一个合法 levelId */
    private long levelOf(long itemId) {
        return jdbc.queryForObject(
                "SELECT id FROM uf_pa_item_level WHERE item_id = ? ORDER BY sort OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class, itemId);
    }

    private PaFillDTO.SubmitRequest submitOf(long... itemIds) {
        List<PaFillDTO.Rating> ratings = new ArrayList<>();
        for (long itemId : itemIds) {
            PaFillDTO.Rating r = new PaFillDTO.Rating();
            r.setItemId(itemId);
            r.setLevelId(levelOf(itemId));
            ratings.add(r);
        }
        PaFillDTO.SubmitRequest req = new PaFillDTO.SubmitRequest();
        req.setRatings(ratings);
        req.setComment("按项指派测试的评语");
        return req;
    }
}
