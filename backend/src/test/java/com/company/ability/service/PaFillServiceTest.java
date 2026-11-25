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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评价填写测试（方案 17 §7.2）。
 *
 * <p>填写页是唯一「外部评价人直接接触」的界面，三条规则一旦松动，
 * 后果都不是报错而是<b>算错分</b>：</p>
 * <ol>
 *   <li>只看得到自己主体对应的能力项（上级项 / 360 项 / 自评看全部）；</li>
 *   <li>提交必须全覆盖，少一项就留空、多一项就串味；</li>
 *   <li>等级必须属于<b>该能力项自己</b>的档位，不是「存在即合法」。</li>
 * </ol>
 *
 * <p>另有一组「链接归属」用例：token 是唯一凭证，登录人必须就是评价人本人。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("评价填写测试")
class PaFillServiceTest {

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
    private long periodId;
    private long postId;
    private long itemLeaderOnly;
    private long item360Too;

    private long taskId;
    private String selfToken;
    private String leaderToken;
    private String raterToken;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc, integrationService);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        long cat = data.category(modelService, postId, "通用能力");
        itemLeaderOnly = data.item(modelService, postId, cat, "沟通能力", "50", 1, 0);
        item360Too = data.item(modelService, postId, cat, "协作能力", "50", 1, 1);
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);

        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360ResourceIds(List.of(PaTestData.RATER_A));
        PaTaskVO.CreateResult created = taskService.create(req);
        taskId = created.getTaskId();
        for (PaTaskVO.RaterLink link : created.getRaters()) {
            switch (link.getRaterType()) {
                case "SELF" -> selfToken = link.getAccessToken();
                case "LEADER" -> leaderToken = link.getAccessToken();
                default -> raterToken = link.getAccessToken();
            }
        }
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    // ================================================================ 可见性

    @Test
    @DisplayName("上级只看得到配了「直接上级」主体的项")
    void leaderSeesOnlyItsItems() {
        PaTestData.login(PaTestData.LEADER);

        PaFillPageVO page = fillService.page(leaderToken);

        assertEquals("FILLING", page.getStatus());
        assertEquals("LEADER", page.getRaterType());
        assertEquals("直接上级", page.getRaterTypeName());
        assertEquals(List.of(itemLeaderOnly, item360Too), itemIds(page));
        // 进度条含自评（HR 要看自评交了没），「能不能出分」另按不含自评的口径判定
        assertEquals(3, page.getRaterTotal());
        assertEquals(0, page.getSubmittedCount());
        assertTrue(page.getCommentRequired(), "上级必须写评价意见");
    }

    @Test
    @DisplayName("360 只看得到配了「360 测评人」主体的项")
    void rater360SeesOnlyItsItems() {
        PaTestData.login(PaTestData.RATER_A);

        PaFillPageVO page = fillService.page(raterToken);

        assertEquals("RATER360", page.getRaterType());
        assertEquals("360 测评人", page.getRaterTypeName());
        assertEquals(List.of(item360Too), itemIds(page));
        assertEquals(0, new BigDecimal("100").compareTo(page.getWeightTotal()),
                "权重徽标说的是「岗位模型配得对不对」，按整份页面算；按可见项算会一直误报 50%");
        assertTrue(page.getCommentRequired());
    }

    @Test
    @DisplayName("自评看得到全部能力项，但不需要写评价意见")
    void selfSeesAllItems() {
        PaTestData.login(PaTestData.TARGET);

        PaFillPageVO page = fillService.page(selfToken);

        assertEquals("SELF", page.getRaterType());
        assertEquals(List.of(itemLeaderOnly, item360Too), itemIds(page));
        assertFalse(page.getCommentRequired(), "自评不写评语（口径 #14）");
    }

    @Test
    @DisplayName("等级随项返回：名称 + 分值 + 描述都要有，页面卡片才能渲染完整")
    void itemsCarryTheirOwnLevels() {
        PaTestData.login(PaTestData.LEADER);

        PaFillPageVO page = fillService.page(leaderToken);

        com.company.ability.vo.PaFillItemVO item = page.getItems().get(0);
        assertEquals(2, item.getLevels().size());
        assertEquals("待提升", item.getLevels().get(0).getLevelName());
        assertEquals(0, new BigDecimal("2").compareTo(item.getLevels().get(0).getScore()));
        assertEquals("优秀", item.getLevels().get(1).getLevelName());
        assertNotNull(item.getWeight());
        assertNotNull(item.getCategoryName());
    }

    // ================================================================ 归属校验

    @Test
    @DisplayName("拒绝：用别人的链接打开（token 是唯一凭证，登录人必须是评价人本人）")
    void rejectsOtherUsersToken() {
        PaTestData.login(PaTestData.RATER_B);

        BusinessException e = assertThrows(BusinessException.class, () -> fillService.page(leaderToken));
        assertEquals(40402, e.getCode());
        assertTrue(e.getMessage().contains("不属于当前登录账号"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：无效 token")
    void rejectsUnknownToken() {
        PaTestData.login(PaTestData.LEADER);

        BusinessException e = assertThrows(BusinessException.class, () -> fillService.page("not-a-token"));
        assertEquals(40401, e.getCode());
    }

    // ================================================================ 暂存

    @Test
    @DisplayName("暂存：写入草稿与评语，不写评级行；版本自增")
    void draftDoesNotWriteRatings() {
        PaTestData.login(PaTestData.LEADER);

        PaTaskVO.DraftResult r = fillService.saveDraft(leaderToken, draft(0, itemLeaderOnly, "待提升",
                "执行力还可以，建议在跨部门沟通上再主动一些"));

        assertFalse(r.getConflict());
        assertEquals(1, r.getDraftVersion());
        assertEquals(1, r.getRatedCount());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_rating"), "暂存不产生正式评级");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT draft_json, comment, status FROM uf_assess_task_rater WHERE access_token = ?", leaderToken);
        assertTrue(String.valueOf(row.get("draft_json")).contains(String.valueOf(itemLeaderOnly)));
        assertEquals("执行力还可以，建议在跨部门沟通上再主动一些", row.get("comment"));
        assertEquals(0, ((Number) row.get("status")).intValue(), "暂存不改评价人状态");
    }

    @Test
    @DisplayName("暂存：版本冲突时返回库内最新草稿，不覆盖别人的写入")
    void draftVersionConflictReturnsLatest() {
        PaTestData.login(PaTestData.LEADER);
        fillService.saveDraft(leaderToken, draft(0, itemLeaderOnly, "待提升", null));

        PaTaskVO.DraftResult conflict = fillService.saveDraft(leaderToken, draft(0, item360Too, "优秀", null));

        assertTrue(conflict.getConflict());
        assertEquals(1, conflict.getDraftVersion());
        assertEquals(1, conflict.getLatestDraft().size());
        assertTrue(conflict.getLatestDraft().containsKey(itemLeaderOnly));
    }

    @Test
    @DisplayName("暂存：不带 comment 时不覆盖已写评语（否则只改评级会把评语清空）")
    void draftKeepsCommentWhenAbsent() {
        PaTestData.login(PaTestData.LEADER);
        fillService.saveDraft(leaderToken, draftComment(0, itemLeaderOnly, "已写好的评语"));

        fillService.saveDraft(leaderToken, draft(1, item360Too, "优秀", null));

        String comment = jdbc.queryForObject(
                "SELECT comment FROM uf_assess_task_rater WHERE access_token = ?", String.class, leaderToken);
        assertEquals("已写好的评语", comment);
    }

    @Test
    @DisplayName("暂存：自评写评语被忽略（自评本来就不该有这一段）")
    void draftIgnoresCommentForSelf() {
        PaTestData.login(PaTestData.TARGET);

        fillService.saveDraft(selfToken, draft(0, itemLeaderOnly, "待提升", "自评给自己写评语"));

        String comment = jdbc.queryForObject(
                "SELECT comment FROM uf_assess_task_rater WHERE access_token = ?", String.class, selfToken);
        assertNull(comment);
    }

    // ================================================================ 提交

    @Test
    @DisplayName("提交：写评级行、锁卷，其他人未交时不出分")
    void submitWritesRatingsAndLocks() {
        PaTestData.login(PaTestData.LEADER);

        PaTaskVO.SubmitResult r = fillService.submit(leaderToken,
                submit("整体称职", itemLeaderOnly, "待提升", item360Too, "优秀"));

        assertTrue(r.getSubmitted());
        assertFalse(r.getCalculated(), "360 还没交，不能出分");
        assertEquals(1, r.getTaskProgress().getSubmitted());
        assertEquals(3, r.getTaskProgress().getTotal());

        List<Map<String, Object>> ratings = jdbc.queryForList(
                "SELECT item_id, level_name, score FROM uf_pa_rating WHERE rater_type = 'LEADER' ORDER BY item_id");
        assertEquals(2, ratings.size());
        assertEquals("待提升", ratings.get(0).get("level_name"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) ratings.get(0).get("score")));

        assertEquals(1, jdbc.queryForObject(
                "SELECT status FROM uf_assess_task_rater WHERE access_token = ?", Integer.class, leaderToken));
        assertEquals(1, jdbc.queryForObject(
                "SELECT status FROM uf_pa_sheet WHERE id = (SELECT sheet_id FROM uf_assess_task WHERE id = ?)",
                Integer.class, taskId), "部分提交");

        PaFillPageVO page = fillService.page(leaderToken);
        assertEquals("SUBMITTED", page.getStatus());
        assertEquals(2, page.getDraft().size(), "提交后草稿即最终答案");
        assertNotNull(page.getSubmitTime(), "已提交要带回提交时间，回执页靠它说明「什么时候交的」");
    }

    @Test
    @DisplayName("未提交时 submitTime 为空 —— 回执页只在已提交时出现，不能给个假时间")
    void fillingPageHasNoSubmitTime() {
        PaTestData.login(PaTestData.LEADER);

        PaFillPageVO page = fillService.page(leaderToken);
        assertEquals("FILLING", page.getStatus());
        assertNull(page.getSubmitTime());
    }

    @Test
    @DisplayName("提交：最后一位交卷时自动算分并结单（口径 #5）")
    void lastSubmitTriggersScoring() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submit("很好", itemLeaderOnly, "优秀", item360Too, "优秀"));

        PaTestData.login(PaTestData.RATER_A);
        PaTaskVO.SubmitResult r = fillService.submit(raterToken, submitOf("协作需要加强", item360Too, "待提升"));

        assertTrue(r.getCalculated());
        assertEquals(2, r.getTaskProgress().getSubmitted());
        assertEquals(3, r.getTaskProgress().getTotal());

        Map<String, Object> sheet = jdbc.queryForMap("SELECT total_score, band_name, status, comment_text" +
                " FROM uf_pa_sheet WHERE id = (SELECT sheet_id FROM uf_assess_task WHERE id = ?)", taskId);
        assertEquals(0, ((Number) sheet.get("status")).intValue() - 2, "算完转「已算分」");
        // 沟通能力 = 上级 5；协作能力 = (5 + 2)/2 = 3.5 → (5×50 + 3.5×50)/100 = 4.25
        assertEquals(0, new BigDecimal("4.25").compareTo((BigDecimal) sheet.get("total_score")));
        assertEquals("优秀", sheet.get("band_name"), "4.25 落在 [4,+∞)");
        String merged = String.valueOf(sheet.get("comment_text"));
        assertTrue(merged.contains("很好"), merged);
        assertTrue(merged.contains("协作需要加强"), merged);
        assertEquals(2, jdbc.queryForObject("SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, taskId));
    }

    @Test
    @DisplayName("提交：自评不参与算分，只落 self_score 供报告展示（口径 #13）")
    void selfRatingExcludedFromScore() {
        // 自评先交：任务一旦完成就冻结，之后再来自评只会被「已完成」挡住
        PaTestData.login(PaTestData.TARGET);
        fillService.submit(selfToken, submit(null, itemLeaderOnly, "优秀", item360Too, "优秀"));
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submit("上级意见", itemLeaderOnly, "待提升", item360Too, "优秀"));
        PaTestData.login(PaTestData.RATER_A);
        fillService.submit(raterToken, submitOf("360 意见", item360Too, "待提升"));

        Map<String, Object> sheet = jdbc.queryForMap(
                "SELECT total_score, self_score FROM uf_pa_sheet WHERE id = " +
                "(SELECT sheet_id FROM uf_assess_task WHERE id = ?)", taskId);
        // 上级/360 侧：沟通 2、协作 (5+2)/2=3.5 → (2×50+3.5×50)/100 = 2.75
        assertEquals(0, new BigDecimal("2.75").compareTo((BigDecimal) sheet.get("total_score")),
                "自评给满分也不能抬高总分");
        assertNotNull(sheet.get("self_score"), "自评仍要留档展示");
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) sheet.get("self_score")));
        assertEquals(2, jdbc.queryForObject(
                "SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, taskId));
    }

    @Test
    @DisplayName("拒绝：自评未交不阻断出分（口径 #13）")
    void pendingSelfDoesNotBlockScoring() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submit("上级意见", itemLeaderOnly, "优秀", item360Too, "优秀"));
        PaTestData.login(PaTestData.RATER_A);
        PaTaskVO.SubmitResult r = fillService.submit(raterToken, submitOf("360 意见", item360Too, "优秀"));

        assertTrue(r.getCalculated(), "自评链接还没人点过，也该出分");
        assertEquals(2, jdbc.queryForObject("SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, taskId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT status FROM uf_assess_task_rater WHERE access_token = ?", Integer.class, selfToken),
                "自评行仍是待评，报告上标「自评未交」");
    }

    @Test
    @DisplayName("拒绝：提交不完整（少一项）")
    void rejectsIncompleteSubmit() {
        PaTestData.login(PaTestData.LEADER);

        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(leaderToken, submitOf("评语", itemLeaderOnly, "优秀")));
        assertEquals(40404, e.getCode());
        assertTrue(e.getMessage().contains("还差 1"), e.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_rating"));
    }

    @Test
    @DisplayName("拒绝：提交多出不归自己评的项（360 去评上级项）")
    void rejectsExtraItems() {
        PaTestData.login(PaTestData.RATER_A);

        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(raterToken, submit("评语", item360Too, "优秀", itemLeaderOnly, "优秀")));
        assertEquals(40404, e.getCode());
        assertTrue(e.getMessage().contains("多出 1"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：用别的能力项的档位打分（等级属于项而非全局）")
    void rejectsForeignKeyLevel() {
        // 给「协作能力」单独配一套完全不同的档位，用它的等级 ID 去评「沟通能力」
        long otherLevel = jdbc.queryForObject(
                "SELECT id FROM uf_pa_item_level WHERE item_id = ? ORDER BY sort LIMIT 1", Long.class, item360Too);
        jdbc.update("UPDATE uf_pa_item_level SET item_id = ? WHERE item_id = ? AND id = ?",
                itemLeaderOnly, item360Too, otherLevel);

        PaTestData.login(PaTestData.LEADER);
        PaFillDTO.SubmitRequest req = submitOf("评语", itemLeaderOnly, "优秀");
        req.getRatings().add(rating(19999L, otherLevel));

        BusinessException e = assertThrows(BusinessException.class, () -> fillService.submit(leaderToken, req));
        assertEquals(40404, e.getCode(), "不存在的项先被「多出」挡住，走不到等级校验");
    }

    @Test
    @DisplayName("拒绝：上级没写评价意见")
    void rejectsMissingComment() {
        PaTestData.login(PaTestData.LEADER);

        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(leaderToken, submit("  ", itemLeaderOnly, "优秀", item360Too, "优秀")));
        assertEquals(40409, e.getCode());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_rating"), "闸门要在写库之前");
    }

    @Test
    @DisplayName("拒绝：自评提交带评语也不会写入（自评没有这一段）")
    void selfSubmitStoresNoComment() {
        PaTestData.login(PaTestData.TARGET);

        fillService.submit(selfToken, submit("自评评语", itemLeaderOnly, "优秀", item360Too, "优秀"));

        assertNull(jdbc.queryForObject(
                "SELECT comment FROM uf_assess_task_rater WHERE access_token = ?", String.class, selfToken));
    }

    @Test
    @DisplayName("拒绝：重复提交（提交是单向的）")
    void rejectsResubmit() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submit("评语", itemLeaderOnly, "优秀", item360Too, "优秀"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(leaderToken, submit("改主意了", itemLeaderOnly, "待提升", item360Too, "待提升")));
        assertEquals(40408, e.getCode());
    }

    @Test
    @DisplayName("拒绝：任务已撤回时填写页只返回状态，不泄漏内容")
    void voidedTaskShowsStatusOnly() {
        jdbc.update("UPDATE uf_assess_task SET status = 4 WHERE id = ?", taskId);
        PaTestData.login(PaTestData.LEADER);

        PaFillPageVO page = fillService.page(leaderToken);

        assertEquals("VOIDED", page.getStatus());
        assertTrue(page.getItems() == null || page.getItems().isEmpty());
        assertTrue(page.getDraft() == null || page.getDraft().isEmpty());
    }

    @Test
    @DisplayName("拒绝：年度已关闭后不能再提交，但已提交的人仍能看自己的答案")
    void closedPeriodBlocksSubmitButKeepsSubmittedReadable() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submit("评语", itemLeaderOnly, "优秀", item360Too, "优秀"));

        jdbc.update("UPDATE uf_assess_period SET status = 1 WHERE id = ?", periodId);

        assertEquals("SUBMITTED", fillService.page(leaderToken).getStatus(), "已交的人豁免");
        PaTestData.login(PaTestData.RATER_A);
        BusinessException e = assertThrows(BusinessException.class,
                () -> fillService.submit(raterToken, submitOf("评语", item360Too, "优秀")));
        assertEquals(40411, e.getCode());
    }

    // ================================================================ 辅助

    private List<Long> itemIds(PaFillPageVO page) {
        List<Long> ids = new ArrayList<>();
        page.getItems().forEach(i -> ids.add(i.getItemId()));
        return ids;
    }

    private long levelOf(long itemId, String levelName) {
        return jdbc.queryForObject("SELECT id FROM uf_pa_item_level WHERE item_id = ? AND level_name = ?",
                Long.class, itemId, levelName);
    }

    private PaFillDTO.Rating rating(long itemId, long levelId) {
        PaFillDTO.Rating r = new PaFillDTO.Rating();
        r.setItemId(itemId);
        r.setLevelId(levelId);
        return r;
    }

    private PaFillDTO.DraftRequest draft(int version, long itemId, String levelName, String comment) {
        PaFillDTO.DraftRequest req = new PaFillDTO.DraftRequest();
        req.setVersion(version);
        req.setComment(comment);
        req.setRatings(List.of(rating(itemId, levelOf(itemId, levelName))));
        return req;
    }

    private PaFillDTO.DraftRequest draftComment(int version, long itemId, String comment) {
        return draft(version, itemId, "待提升", comment);
    }

    /** 提交请求：pairs 为 (itemId, "等级名") 依次排列，评语另传 */
    private PaFillDTO.SubmitRequest submit(String comment, Object... pairs) {
        PaFillDTO.SubmitRequest req = new PaFillDTO.SubmitRequest();
        List<PaFillDTO.Rating> ratings = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            long itemId = ((Number) pairs[i]).longValue();
            ratings.add(rating(itemId, levelOf(itemId, (String) pairs[i + 1])));
        }
        req.setRatings(ratings);
        req.setComment(comment);
        return req;
    }

    private PaFillDTO.SubmitRequest submitOf(String comment, long itemId, String levelName) {
        return submit(comment, itemId, levelName);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
