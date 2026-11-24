package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskOpDTO;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务运营测试（方案 17 §7.2）：撤回 / 催办 / 重算 / 删除 / 列表 / 我的评价。
 *
 * <p>撤回是这里最危险的动作 —— 它删评级、清分数、作废链接。用例要同时锁住两件事：
 * <b>回退得干净</b>（没有残留评级或分数），以及<b>不该撤的时候撤不掉</b>
 * （已完成要 force、年度已关闭不许撤、撤回后不许重算）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评任务运营测试")
class PaTaskOpServiceTest {

    @Autowired
    private PaTaskOpService opService;
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

    private PaTestData data;
    private long periodId;
    private long postId;
    private long itemLeaderOnly;
    private long item360Too;

    private long taskId;
    private long sheetId;
    private String selfToken;
    private String leaderToken;
    private String raterToken;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        long cat = data.category(modelService, postId, "通用能力");
        itemLeaderOnly = data.item(modelService, postId, cat, "沟通能力", "50", 1, 0);
        item360Too = data.item(modelService, postId, cat, "协作能力", "50", 1, 1);
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);
        // 运营操作只要求「已登录」；维度/管理员身份由 DimensionContextFilter 在接口层把关
        PaTestData.login(PaTestData.TARGET);

        PaTaskVO.CreateResult created = taskService.create(createReq(PaTestData.TARGET, PaTestData.RATER_A));
        taskId = created.getTaskId();
        sheetId = created.getSheetId();
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

    // ================================================================ 撤回

    @Test
    @DisplayName("撤回：删评级、清分数、作废链接、只通知已提交者")
    void voidRollsEverythingBack() {
        submitAll();
        assertEquals(0, new BigDecimal("5").compareTo(sheetScore()), "撤回前是算过分的任务");
        int noticesBefore = pushCount();

        PaTaskVO.VoidResult r = opService.voidTask(taskId, voidReq("360 名单漏选了人", true));

        assertFalse(r.getAlreadyVoided());
        assertTrue(r.getFullRollback());
        assertEquals(5, r.getVoidedRatingCount().intValue(), "上级 2 + 360 1 + 自评 2");
        assertEquals(3, r.getVoidedRaterCount().intValue(), "三条链接全部作废");
        assertNull(r.getSheetAfter().getTotalScore(), "撤回后页面回到无分数");
        assertNull(r.getSheetAfter().getBandName());
        assertEquals(0, r.getSheetAfter().getStatus().intValue());

        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_rating WHERE sheet_id = " + sheetId));
        assertEquals(0, count("SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = " + taskId
                + " AND status <> 3"), "所有评价人都必须作废");
        assertEquals(4, taskStatus());
        // 页面行得分必须一起清空：只重置页面头会留下「没分数但有明细」的不一致状态
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_sheet_item WHERE sheet_id = " + sheetId
                + " AND (item_score IS NOT NULL OR leader_score IS NOT NULL"
                + " OR rater360_score IS NOT NULL OR self_score IS NOT NULL)"));

        assertTrue(r.getNoticeWritten());
        assertEquals(3, r.getNotifiedRaterCount().intValue(), "三条都交过，都要通知");
        assertEquals(3, pushCount() - noticesBefore, "每个已提交者一条撤回通知");
    }

    @Test
    @DisplayName("撤回：幂等，重复撤回归还 alreadyVoided 且不再动数据")
    void voidIsIdempotent() {
        submitAll();
        opService.voidTask(taskId, voidReq("第一次撤回", true));
        int notices = pushCount();

        PaTaskVO.VoidResult r = opService.voidTask(taskId, voidReq("第二次撤回", true));

        assertTrue(r.getAlreadyVoided());
        assertFalse(r.getFullRollback());
        assertEquals(0, r.getNotifiedRaterCount().intValue());
        assertEquals(notices, pushCount(), "不重复发通知");
    }

    @Test
    @DisplayName("撤回：已完成的任务必须传 force，避免误撤已出分的报告")
    void voidDoneTaskNeedsForce() {
        submitAll();

        BusinessException e = assertThrows(BusinessException.class,
                () -> opService.voidTask(taskId, voidReq("误点撤回按钮", false)));
        assertEquals(40206, e.getCode());
        assertEquals(2, taskStatus(), "被拒时什么都不该变");
        assertEquals(0, new BigDecimal("5").compareTo(sheetScore()));

        PaTaskVO.VoidResult r = opService.voidTask(taskId, voidReq("确认撤回重发", true));
        assertTrue(r.getFullRollback());
        assertEquals(4, taskStatus());
    }

    @Test
    @DisplayName("撤回：年度已关闭后不许撤回（数据已冻结）")
    void voidBlockedByClosedPeriod() {
        jdbc.update("UPDATE uf_assess_period SET status = 1 WHERE id = ?", periodId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> opService.voidTask(taskId, voidReq("年度关了还要撤", true)));
        assertEquals(40203, e.getCode());
        assertEquals(0, taskStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_push_msg WHERE title LIKE '%作废%'"));
    }

    @Test
    @DisplayName("撤回：已取消的任务拒绝撤回，提示直接删除")
    void voidRejectsCancelledTask() {
        jdbc.update("UPDATE uf_assess_task SET status = 3 WHERE id = ?", taskId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> opService.voidTask(taskId, voidReq("取消的任务撤什么", false)));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("删除"), e.getMessage());
    }

    @Test
    @DisplayName("撤回后重发：复用同一页面，旧链接落地只看到撤回页")
    void reissueAfterVoid() {
        submitAll();
        opService.voidTask(taskId, voidReq("名单要重选", true));

        PaTaskVO.CreateResult r2 = taskService.create(
                createReq(PaTestData.TARGET, PaTestData.RATER_A, PaTestData.RATER_B));

        assertEquals(sheetId, r2.getSheetId(), "同一人同一年度复用同一页");
        assertEquals(1, count("SELECT COUNT(*) FROM uf_pa_sheet"), "不该多出第二份同年报告");
        assertNotEquals(taskId, r2.getTaskId());
        assertEquals(4, r2.getRaters().size(), "自评 + 上级 + 两位 360");
        assertEquals(0, count("SELECT COUNT(*) FROM uf_pa_rating"), "新一轮从零开始");
        assertNull(sheetScore());

        // 旧链接：要给「被撤回了」而不是「链接不属于你」——评价人得知道发生了什么
        PaFillPageVO old = fillService.page(leaderToken);
        assertEquals("VOIDED", old.getStatus());
        assertNull(old.getItems(), "撤回后不返回任何能力项");
    }

    // ================================================================ 催办

    @Test
    @DisplayName("催办：只催未提交的人，逐轮递增，已提交者不被记一次催办")
    void remindOnlyPending() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submitBothLive("上级意见"));
        int pushedBefore = pushCount();

        PaTaskVO.RemindResult r = opService.remind(taskId, null);

        assertEquals(2, r.getPendingRaterCount().intValue(), "自评 + 360");
        assertEquals(2, r.getPushedCount().intValue());
        assertEquals(1, r.getRound().intValue());
        assertEquals(2, pushCount() - pushedBefore);

        assertEquals(0, remindCount(leaderToken), "已提交的人不该被记一次催办");
        assertEquals(1, remindCount(selfToken));
        assertEquals(1, remindCount(raterToken));

        assertEquals(2, opService.remind(taskId, null).getRound().intValue(), "第二轮");
    }

    @Test
    @DisplayName("催办：指定人时只催这几个，别的人的催办次数不能涨")
    void remindGivenRaters() {
        PaTaskOpDTO.RemindRequest req = new PaTaskOpDTO.RemindRequest();
        req.setRaterResourceIds(List.of(PaTestData.RATER_A));

        PaTaskVO.RemindResult r = opService.remind(taskId, req);

        assertEquals(1, r.getPendingRaterCount().intValue());
        assertEquals(1, r.getPushedCount().intValue());
        assertEquals(1, remindCount(raterToken));
        assertEquals(0, remindCount(selfToken), "没点名的人不能被记一次催办");
        assertEquals(0, remindCount(leaderToken));
    }

    @Test
    @DisplayName("催办：没有待评人时明确告知「无需催办」")
    void remindWhenNobodyPending() {
        submitAll();
        // 全员已交但任务仍是进行中（历史数据/手工改过状态）：这是「无需催办」唯一的真实成因
        jdbc.update("UPDATE uf_assess_task SET status = 0 WHERE id = ?", taskId);
        int pushedBefore = pushCount();

        PaTaskVO.RemindResult r = opService.remind(taskId, null);

        assertEquals(0, r.getPushedCount().intValue());
        assertTrue(r.getMessage().contains("无需催办"), r.getMessage());
        assertEquals(pushedBefore, pushCount(), "不发任何消息");
    }

    @Test
    @DisplayName("催办：只有进行中的任务可以催")
    void remindRejectsFinished() {
        submitAll();

        BusinessException e = assertThrows(BusinessException.class, () -> opService.remind(taskId, null));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("进行中"), e.getMessage());
    }

    // ================================================================ 重算

    @Test
    @DisplayName("重算：全员齐备时按当前评级重算，不动任务状态")
    void retryRecalculates() {
        submitAll();

        PaTaskVO.RetryResult r = opService.retry(taskId);

        assertEquals(taskId, r.getTaskId());
        assertEquals(2, r.getTaskStatus().intValue());
        assertEquals(0, new BigDecimal("5").compareTo(r.getTotalScore()), "两项都是 5 分");
        assertEquals("优秀", r.getBandName());
    }

    @Test
    @DisplayName("重算：有人未提交时拒绝（未提交前算出来的分数没有意义）")
    void retryRejectsIncomplete() {
        BusinessException e = assertThrows(BusinessException.class, () -> opService.retry(taskId));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("未提交"), e.getMessage());
        assertNull(sheetScore());
    }

    @Test
    @DisplayName("重算：自评未交不阻断（口径 #13，与自动算分同一口径）")
    void retryIgnoresPendingSelf() {
        submitOthersOnly();

        assertEquals(2, taskStatus(), "非自评齐备就该自动收口");
        PaTaskVO.RetryResult r = opService.retry(taskId);
        assertNotNull(r.getTotalScore());
        assertEquals(1, count("SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = " + taskId
                + " AND rater_type = 'SELF' AND status = 0"), "自评仍待交，但不影响出分");
    }

    @Test
    @DisplayName("重算：已撤回的任务拒绝重算（评级已删，重算只会把页面写空）")
    void retryRejectsVoided() {
        submitAll();
        opService.voidTask(taskId, voidReq("撤回后不该重算", true));

        BusinessException e = assertThrows(BusinessException.class, () -> opService.retry(taskId));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("已撤回"), e.getMessage());
        assertNull(sheetScore(), "被拒时页面不该被写成空分数");
    }

    // ================================================================ 删除

    @Test
    @DisplayName("删除：只删任务与评价人，测评页保留")
    void deleteKeepsSheet() {
        submitAll();
        opService.voidTask(taskId, voidReq("删掉这条任务", true));

        PaTaskVO.DeleteResult r = opService.delete(taskId);

        assertEquals(3, r.getDeletedRaterCount().intValue());
        assertTrue(r.getSheetKept(), "测评页恒保留");
        assertEquals(sheetId, r.getSheetId());
        assertEquals(0, count("SELECT COUNT(*) FROM uf_assess_task WHERE id = " + taskId));
        assertEquals(1, count("SELECT COUNT(*) FROM uf_pa_sheet WHERE id = " + sheetId));
    }

    @Test
    @DisplayName("删除：进行中的任务不能删，必须先撤回")
    void deleteRejectsRunning() {
        BusinessException e = assertThrows(BusinessException.class, () -> opService.delete(taskId));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("已撤回"), e.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM uf_assess_task WHERE id = " + taskId));
    }

    // ================================================================ 列表 / 详情

    @Test
    @DisplayName("列表：默认隐藏已撤回任务，带上面包屑字段（岗位/得分/等级说明）")
    void listHidesVoidedByDefault() {
        submitAll();
        // 第二个任务必须换人：同一人同一年度共用一个页面，重新装配会把这页分数清掉
        long otherTaskId = createTaskFor(PaTestData.RATER_A, PaTestData.RATER_B);
        opService.voidTask(otherTaskId, voidReq("删掉第二条", true));

        PaTaskOpDTO.TaskQuery q = new PaTaskOpDTO.TaskQuery();
        Map<String, Object> page = opService.list(q);

        assertEquals(1, page.get("total"));
        PaTaskVO.TaskItem item = firstItem(page);
        assertEquals(taskId, item.getTaskId());
        assertEquals("张被测", item.getTargetName());
        assertEquals("人力资源部", item.getDeptName());
        assertEquals("人事专员", item.getPostName());
        assertEquals(2, item.getItemCount().intValue());
        assertEquals(1, item.getRater360Count().intValue());
        assertEquals("已完成", item.getStatusName());
        assertEquals(0, new BigDecimal("5").compareTo(item.getTotalScore()));
        assertEquals("优秀", item.getBandName());
        assertFalse(item.getSelfPending());
        assertEquals(3, item.getRaterTotal().intValue());
        assertEquals(3, item.getSubmittedCount().intValue());
    }

    @Test
    @DisplayName("列表：自评未交时标出「自评未交」（不阻断出分但要让 HR 看见）")
    void listMarksSelfPending() {
        submitOthersOnly();

        PaTaskOpDTO.TaskQuery q = new PaTaskOpDTO.TaskQuery();
        q.setStatus(2);
        PaTaskVO.TaskItem item = firstItem(opService.list(q));

        assertTrue(item.getSelfPending(), "自评没交，列表要标出来");
        assertEquals(2, item.getSubmittedCount().intValue());
        assertEquals(3, item.getRaterTotal().intValue());
    }

    @Test
    @DisplayName("列表：按状态筛「已撤回」时能看到撤回原因")
    void listVoidedWithReason() {
        submitAll();
        opService.voidTask(taskId, voidReq("名单选错了需要重发", true));

        PaTaskOpDTO.TaskQuery q = new PaTaskOpDTO.TaskQuery();
        q.setStatus(4);
        PaTaskVO.TaskItem item = firstItem(opService.list(q));

        assertEquals("已撤回", item.getStatusName());
        assertEquals("名单选错了需要重发", item.getVoidReason());
    }

    @Test
    @DisplayName("列表：关键字按被测人姓名过滤，查无此人返回空页")
    void listFiltersByKeyword() {
        submitAll();

        PaTaskOpDTO.TaskQuery hit = new PaTaskOpDTO.TaskQuery();
        hit.setKeyword("张被");
        assertEquals(1, hitResult(hit));

        PaTaskOpDTO.TaskQuery miss = new PaTaskOpDTO.TaskQuery();
        miss.setKeyword("查无此人");
        assertEquals(0, hitResult(miss));
    }

    @Test
    @DisplayName("详情：评价人按 自评→上级→360 排序，带状态与草稿标记")
    void detailListsRatersInOrder() {
        PaTestData.login(PaTestData.LEADER);
        fillService.saveDraft(leaderToken, draftOne(itemLeaderOnly, "优秀"));

        PaTaskVO.TaskDetail detail = opService.detail(taskId);

        assertEquals(List.of("SELF", "LEADER", "RATER360"),
                detail.getRaters().stream().map(PaTaskVO.RaterProgress::getRaterType).toList());
        PaTaskVO.RaterProgress leader = detail.getRaters().get(1);
        assertEquals("直接上级", leader.getRaterTypeName());
        assertEquals("待评价", leader.getStatusName());
        assertTrue(leader.getHasDraft());
        assertEquals(0, leader.getRemindCount().intValue());
        assertEquals(0, detail.getTask().getSubmittedCount().intValue());
    }

    // ================================================================ 我的评价

    @Test
    @DisplayName("我的评价：只按登录身份过滤，已撤回的不出现")
    void myTasksFilteredByLogin() {
        long otherTaskId = createTaskFor(PaTestData.RATER_A, PaTestData.RATER_B);

        PaTestData.login(PaTestData.RATER_A);
        List<PaTaskVO.MyTaskItem> mine = opService.myTasks(null);
        assertEquals(2, mine.size(), "自己那条的自评 + 别人那条的 360");
        assertEquals(2, mine.stream().map(PaTaskVO.MyTaskItem::getTaskId).distinct().count());
        assertEquals("待评价", mine.get(0).getStatusText());
        assertTrue(mine.stream().allMatch(m -> m.getPath().startsWith("/pa/assess-fill/")),
                "「我的评价」跳到的是新填写页");
        assertTrue(mine.stream().allMatch(m -> m.getYear() == PaTestData.YEAR));

        PaTestData.login(PaTestData.RATER_B);
        assertEquals(1, opService.myTasks(null).size());

        // 撤回第二条后，它从所有评价人的待办里消失
        opService.voidTask(otherTaskId, voidReq("这条不评了", false));
        assertEquals(0, opService.myTasks(null).size());
    }

    @Test
    @DisplayName("我的评价：已提交但别人未交显示「等待其他评价人」，全员齐备显示「已完成」")
    void myTasksStatusText() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submitBothLive("上级意见"));
        assertEquals("已提交 · 等待其他评价人", opService.myTasks(null).get(0).getStatusText());

        PaTestData.login(PaTestData.RATER_A);
        fillService.submit(raterToken, submitOne(item360Too, "优秀", "360 意见"));
        assertEquals("已完成", opService.myTasks(null).get(0).getStatusText());
    }

    @Test
    @DisplayName("我的评价：未登录直接拒绝，不接受任何 resourceId 参数")
    void myTasksRequiresLogin() {
        PaTestData.logout();

        BusinessException e = assertThrows(BusinessException.class, () -> opService.myTasks(null));
        assertEquals(40207, e.getCode());
    }

    @Test
    @DisplayName("任务不存在：按不存在处理，不泄漏跨维度任务")
    void unknownTask() {
        BusinessException e = assertThrows(BusinessException.class, () -> opService.detail(999999L));
        assertEquals(40201, e.getCode());
        assertEquals("任务不存在", e.getMessage());
    }

    // ================================================================ 辅助

    /** 全员提交：自评先交 —— 任务一旦完成就冻结，后面谁也提交不了 */
    private void submitAll() {
        PaTestData.login(PaTestData.TARGET);
        fillService.submit(selfToken, submitBothLive(null));
        submitOthersOnly();
    }

    /** 上级 + 360 提交，自评留空（不阻断出分，口径 #13） */
    private void submitOthersOnly() {
        PaTestData.login(PaTestData.LEADER);
        fillService.submit(leaderToken, submitBothLive("上级意见"));
        PaTestData.login(PaTestData.RATER_A);
        fillService.submit(raterToken, submitOne(item360Too, "优秀", "360 意见"));
    }

    private PaFillDTO.SubmitRequest submitBothLive(String comment) {
        PaFillDTO.Rating a = new PaFillDTO.Rating();
        a.setItemId(itemLeaderOnly);
        a.setLevelId(levelOf(itemLeaderOnly, "优秀"));
        PaFillDTO.Rating b = new PaFillDTO.Rating();
        b.setItemId(item360Too);
        b.setLevelId(levelOf(item360Too, "优秀"));
        PaFillDTO.SubmitRequest req = new PaFillDTO.SubmitRequest();
        req.setRatings(List.of(a, b));
        req.setComment(comment);
        return req;
    }

    private PaFillDTO.SubmitRequest submitOne(long itemId, String levelName, String comment) {
        PaFillDTO.Rating r = new PaFillDTO.Rating();
        r.setItemId(itemId);
        r.setLevelId(levelOf(itemId, levelName));
        PaFillDTO.SubmitRequest req = new PaFillDTO.SubmitRequest();
        req.setRatings(List.of(r));
        req.setComment(comment);
        return req;
    }

    private long levelOf(long itemId, String levelName) {
        return jdbc.queryForObject("SELECT id FROM uf_pa_item_level WHERE item_id = ? AND level_name = ?",
                Long.class, itemId, levelName);
    }

    private PaFillDTO.DraftRequest draftOne(long itemId, String levelName) {
        PaFillDTO.Rating r = new PaFillDTO.Rating();
        r.setItemId(itemId);
        r.setLevelId(levelOf(itemId, levelName));
        PaFillDTO.DraftRequest req = new PaFillDTO.DraftRequest();
        req.setVersion(0);
        req.setRatings(List.of(r));
        return req;
    }

    private PaTaskDTO.CreateRequest createReq(long resourceId, long... rater360) {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(resourceId);
        req.setPostId(postId);
        req.setRater360ResourceIds(java.util.Arrays.stream(rater360).boxed().toList());
        return req;
    }

    /** 换一个人再发起一条：需要先补「直接上级」 */
    private long createTaskFor(long resourceId, long... rater360) {
        jdbc.update("UPDATE HrmResource SET managerid = ? WHERE id = ?", PaTestData.LEADER, resourceId);
        return taskService.create(createReq(resourceId, rater360)).getTaskId();
    }

    private PaTaskOpDTO.VoidRequest voidReq(String reason, boolean force) {
        PaTaskOpDTO.VoidRequest req = new PaTaskOpDTO.VoidRequest();
        req.setReason(reason);
        req.setForce(force);
        return req;
    }

    private int hitResult(PaTaskOpDTO.TaskQuery q) {
        return ((Number) opService.list(q).get("total")).intValue();
    }

    private BigDecimal sheetScore() {
        return jdbc.queryForObject("SELECT total_score FROM uf_pa_sheet WHERE id = ?", BigDecimal.class, sheetId);
    }

    private int taskStatus() {
        Integer n = jdbc.queryForObject("SELECT status FROM uf_assess_task WHERE id = ?", Integer.class, taskId);
        return n == null ? -1 : n;
    }

    private int remindCount(String token) {
        Integer n = jdbc.queryForObject(
                "SELECT remind_count FROM uf_assess_task_rater WHERE access_token = ?", Integer.class, token);
        return n == null ? 0 : n;
    }

    private int pushCount() {
        return count("SELECT COUNT(*) FROM uf_push_msg");
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    @SuppressWarnings("unchecked")
    private static PaTaskVO.TaskItem firstItem(Map<String, Object> page) {
        List<PaTaskVO.TaskItem> items = (List<PaTaskVO.TaskItem>) page.get("items");
        assertFalse(items.isEmpty(), "列表应有至少一条任务");
        return items.get(0);
    }
}
