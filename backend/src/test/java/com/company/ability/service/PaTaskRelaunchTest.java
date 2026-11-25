package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.support.PaTestData;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重新发起测试（2026-11-25）。
 *
 * <p>这个功能只有一个风险点：<b>带出来的东西跟原任务不一致，而用户以为一致</b>。
 * 所以每条用例盯的是「预填值是否逐项对得上原任务」与「带不出来的是否说了」，
 * 而不是只看接口返回 200。</p>
 *
 * <p>另一半是状态闸门：只有「已撤回」能重新发起。进行中的再发一次会撞重复发起保护，
 * 已完成的本来就不该重来 —— 这两条必须在预填接口就拒掉，否则用户会一直填到最后一步才失败。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("测评任务重新发起测试")
class PaTaskRelaunchTest {

    @Autowired
    private PaTaskOpService opService;
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
    private long itemLeaderOnly;
    private long item360a;
    private long item360b;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc, integrationService);
        data.reset();
        data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        long cat = data.category(modelService, postId, "通用能力");
        itemLeaderOnly = data.item(modelService, postId, cat, "沟通能力", "40", 1, 0);
        item360a = data.item(modelService, postId, cat, "协作能力", "30", 1, 1);
        item360b = data.item(modelService, postId, cat, "推动能力", "30", 1, 1);
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);
        PaTestData.login(PaTestData.TARGET);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    // ================================================================ 预填

    @Test
    @DisplayName("预填：按项指派的任务，年度/被测人/岗位/每项的人都原样带回")
    void draftRestoresPerItemAssignments() {
        long taskId = createNewStyle();
        opService.voidTask(taskId, voidReq("名单选错了"));

        PaTaskVO.RelaunchDraft draft = opService.relaunchDraft(taskId);

        assertEquals(taskId, draft.getTaskId());
        assertEquals(PaTestData.YEAR, draft.getYear().intValue());
        assertEquals(PaTestData.TARGET, draft.getResourceId().longValue());
        assertEquals("张被测", draft.getTargetName());
        assertEquals(postId, draft.getPostId().longValue());
        assertFalse(draft.getLegacyAllItems(), "新口径任务不是「这批人评全部项」");
        assertTrue(draft.getWarnings().isEmpty(), "配置没变，不该有任何提示：" + draft.getWarnings());

        assertEquals(2, draft.getRater360Items().size(), "只有配了 360 的两项要选人");
        assertEquals(List.of(PaTestData.RATER_A, PaTestData.RATER_B),
                idsOf(draft, item360a), "协作能力：原任务两位测评人");
        assertEquals(List.of(PaTestData.RATER_B), idsOf(draft, item360b), "推动能力：原任务只有一位");
        assertTrue(draft.getRater360Items().stream().noneMatch(i -> i.getItemId() == itemLeaderOnly),
                "只由上级评的项不该出现在 360 选人表里");

        // 人名要带得出来：页面显示「姓名（公司·部门）」，不能退化成裸 ID
        PaTaskVO.RelaunchPerson a = draft.getRater360People().stream()
                .filter(p -> p.getResourceId() == PaTestData.RATER_A).findFirst().orElseThrow();
        assertEquals("王甲", a.getName());
        assertEquals("人力资源部", a.getDepartmentName());
    }

    @Test
    @DisplayName("预填：老口径任务（这批人评全部 360 项）标记 legacyAllItems，每项都带回这批人")
    void draftRestoresLegacyAssignment() {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360ResourceIds(List.of(PaTestData.RATER_A));
        long taskId = taskService.create(req).getTaskId();
        // 库里没有指派行才是「旧口径」：sql/60 之前的任务就是这样。
        // 现在走旧入参也会写指派行（写的是全部 360 项），所以必须在这里手工清掉才模拟得出
        jdbc.update("DELETE FROM uf_assess_task_rater_item WHERE task_id = ?", taskId);
        opService.voidTask(taskId, voidReq("重来一次"));

        PaTaskVO.RelaunchDraft draft = opService.relaunchDraft(taskId);

        assertTrue(draft.getLegacyAllItems(), "本表没有指派行 = 旧口径");
        assertEquals(2, draft.getRater360Items().size());
        assertEquals(List.of(PaTestData.RATER_A), idsOf(draft, item360a));
        assertEquals(List.of(PaTestData.RATER_A), idsOf(draft, item360b), "旧口径下这个人评全部 360 项");
    }

    @Test
    @DisplayName("预填：岗位停用后不预填岗位，并用中文说清楚为什么")
    void draftDropsDisabledPost() {
        long taskId = createNewStyle();
        opService.voidTask(taskId, voidReq("名单选错了"));
        jdbc.update("UPDATE uf_pa_post SET status = 0 WHERE id = ?", postId);

        PaTaskVO.RelaunchDraft draft = opService.relaunchDraft(taskId);

        assertNull(draft.getPostId(), "停用的岗位不能带出来，否则用户以为直接能发");
        assertTrue(draft.getWarnings().stream().anyMatch(w -> w.contains("已停用")),
                "要说清楚为什么岗位没带出来：" + draft.getWarnings());
    }

    @Test
    @DisplayName("预填：年度已关闭时不预填年度，并提示重选")
    void draftDropsClosedPeriod() {
        long taskId = createNewStyle();
        opService.voidTask(taskId, voidReq("名单选错了"));
        jdbc.update("UPDATE uf_assess_period SET status = 1");

        PaTaskVO.RelaunchDraft draft = opService.relaunchDraft(taskId);

        assertNull(draft.getYear());
        assertTrue(draft.getWarnings().stream().anyMatch(w -> w.contains("已关闭")), draft.getWarnings().toString());
    }

    @Test
    @DisplayName("预填：能力项被删掉时说明它没带出来，不静默丢")
    void draftWarnsAboutDeletedItem() {
        long taskId = createNewStyle();
        opService.voidTask(taskId, voidReq("名单选错了"));
        jdbc.update("UPDATE uf_pa_item SET status = 0 WHERE id = ?", item360b);

        PaTaskVO.RelaunchDraft draft = opService.relaunchDraft(taskId);

        assertEquals(1, draft.getRater360Items().size());
        assertTrue(draft.getWarnings().stream().anyMatch(w -> w.contains("推动能力")),
                "停用的项要说出来：" + draft.getWarnings());
    }

    // ================================================================ 状态闸门

    @Test
    @DisplayName("预填：进行中的任务不能重新发起")
    void draftRejectsRunningTask() {
        long taskId = createNewStyle();
        BusinessException e = assertThrows(BusinessException.class, () -> opService.relaunchDraft(taskId));
        assertEquals(40208, e.getCode());
        assertTrue(e.getMessage().contains("已撤回"), e.getMessage());
    }

    @Test
    @DisplayName("重新发起：进行中的任务拒绝，且不留下第二个任务")
    void relaunchRejectsRunningTask() {
        long taskId = createNewStyle();
        int before = count("SELECT COUNT(*) FROM uf_assess_task");

        BusinessException e = assertThrows(BusinessException.class,
                () -> opService.relaunch(taskId, createReqNewStyle()));

        assertEquals(40208, e.getCode());
        assertEquals(before, count("SELECT COUNT(*) FROM uf_assess_task"));
    }

    @Test
    @DisplayName("跨维度：别人的任务按「不存在」处理，不泄露存在性")
    void draftHidesOtherDimensionTask() {
        long taskId = createNewStyle();
        opService.voidTask(taskId, voidReq("名单选错了"));
        DimensionContext.set(PaTestData.DIM + 1);

        BusinessException e = assertThrows(BusinessException.class, () -> opService.relaunchDraft(taskId));
        assertEquals(40201, e.getCode());
        assertEquals("任务不存在", e.getMessage());
    }

    // ================================================================ 真正重发

    @Test
    @DisplayName("重新发起：生成新任务、旧任务回填 replaced_by_task_id、新任务仍走完整校验")
    void relaunchCreatesNewTaskAndLinksOld() {
        long oldTaskId = createNewStyle();
        opService.voidTask(oldTaskId, voidReq("名单选错了"));

        PaTaskVO.CreateResult created = opService.relaunch(oldTaskId, createReqNewStyle());

        assertNotEquals(oldTaskId, created.getTaskId());
        assertEquals(4, created.getRaters().size(), "自评 + 上级 + 两位 360（A 评两项、B 评两项）");
        Long replacedBy = jdbc.queryForObject(
                "SELECT replaced_by_task_id FROM uf_assess_task WHERE id = ?", Long.class, oldTaskId);
        assertEquals(created.getTaskId(), replacedBy, "旧任务要知道自己被谁替代了");
        assertEquals(4, count("SELECT status FROM uf_assess_task WHERE id = " + oldTaskId),
                "旧任务仍然是已撤回，不被改写");

        // 新任务按项指派落库：协作能力 A+B、推动能力 B，共 3 行
        assertEquals(3, count("SELECT COUNT(*) FROM uf_assess_task_rater_item WHERE task_id = "
                + created.getTaskId()));
    }

    @Test
    @DisplayName("重新发起：原任务已被替代过，仍允许再发（页面只提示，不阻断）")
    void relaunchAllowedEvenIfAlreadyReplaced() {
        long oldTaskId = createNewStyle();
        opService.voidTask(oldTaskId, voidReq("名单选错了"));
        PaTaskVO.CreateResult first = opService.relaunch(oldTaskId, createReqNewStyle());
        // 第二次发起会撞「同一页面只能有一个进行中的任务」，这本身是保护，不是 bug
        BusinessException e = assertThrows(BusinessException.class,
                () -> opService.relaunch(oldTaskId, createReqNewStyle()));
        assertEquals(40301, e.getCode(), "重复发起保护仍然生效");

        Long replacedBy = jdbc.queryForObject(
                "SELECT replaced_by_task_id FROM uf_assess_task WHERE id = ?", Long.class, oldTaskId);
        assertEquals(first.getTaskId(), replacedBy, "关系保持第一次的结果");
    }

    @Test
    @DisplayName("重新发起：撤回后换年度也能发（参数以页面提交为准，不是照抄旧任务）")
    void relaunchUsesSubmittedParamsNotOldOnes() {
        long oldTaskId = createNewStyle();
        opService.voidTask(oldTaskId, voidReq("名单选错了"));

        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, 0, ?)",
                PaTestData.YEAR + 1, PaTestData.DIM);
        PaTaskDTO.CreateRequest req = createReqNewStyle();
        req.setYear(PaTestData.YEAR + 1);

        PaTaskVO.CreateResult created = opService.relaunch(oldTaskId, req);

        Integer year = jdbc.queryForObject(
                "SELECT p.year FROM uf_assess_task t JOIN uf_assess_period p ON p.id = t.period_id"
                        + " WHERE t.id = ?", Integer.class, created.getTaskId());
        assertEquals(PaTestData.YEAR + 1, year.intValue());
    }

    // ================================================================ 辅助

    /** 新口径发起：协作能力 A+B，推动能力只有 B */
    private long createNewStyle() {
        return taskService.create(createReqNewStyle()).getTaskId();
    }

    private PaTaskDTO.CreateRequest createReqNewStyle() {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        PaTaskDTO.Rater360Item a = new PaTaskDTO.Rater360Item();
        a.setItemId(item360a);
        a.setResourceIds(List.of(PaTestData.RATER_A, PaTestData.RATER_B));
        PaTaskDTO.Rater360Item b = new PaTaskDTO.Rater360Item();
        b.setItemId(item360b);
        b.setResourceIds(List.of(PaTestData.RATER_B));
        req.setRater360Items(List.of(a, b));
        return req;
    }

    private static List<Long> idsOf(PaTaskVO.RelaunchDraft draft, long itemId) {
        return draft.getRater360Items().stream()
                .filter(i -> i.getItemId() == itemId)
                .findFirst().orElseThrow()
                .getPeople().stream().map(PaTaskVO.RelaunchPerson::getResourceId).toList();
    }

    private static PaTaskOpDTO.VoidRequest voidReq(String reason) {
        PaTaskOpDTO.VoidRequest req = new PaTaskOpDTO.VoidRequest();
        req.setReason(reason);
        req.setForce(true);
        return req;
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
