package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskDTO;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发起测评测试（方案 17 §7.2）。
 *
 * <p>重点在「闸门顺序与闸门内容」——发起是整个链路里唯一会创建链接、写消息的地方，
 * 发出去就收不回来。所以每条拒绝都要断言：错误码对、提示里带人名、
 * 而且<b>没有留下半个任务</b>。</p>
 *
 * <p>另一组是被驳回过很多次的口径：上级不许从入参传（口径 #11）、
 * 360 名单为空后端不拦（口径 #9）、被测人不能当自己的 360。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("发起测评测试")
class PaTaskServiceTest {

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
    private long itemWith360;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc, integrationService);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        long cat = data.category(modelService, postId, "通用能力");
        data.item(modelService, postId, cat, "沟通能力", "50", 1, 0);
        itemWith360 = data.item(modelService, postId, cat, "协作能力", "50", 1, 1);
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    // ================================================================ 正常发起

    @Test
    @DisplayName("发起：生成自评 + 上级 + N 个 360 的链接，任务与评价人落库")
    void createsTaskAndRaters() {
        PaTaskVO.CreateResult r = taskService.create(req(List.of(PaTestData.RATER_A, PaTestData.RATER_B)));

        assertTrue(r.getTaskId() > 0);
        assertEquals(2, r.getItemCount());
        assertEquals(2, r.getRater360Count());
        assertEquals(4, r.getRaters().size(), "自评 1 + 上级 1 + 360 2");

        Map<String, Object> task = jdbc.queryForMap(
                "SELECT status, resource_id, leader_resource_id, sheet_id, client_count FROM uf_assess_task" +
                " WHERE id = ?", r.getTaskId());
        assertEquals(0, ((Number) task.get("status")).intValue());
        assertEquals(PaTestData.TARGET, ((Number) task.get("resource_id")).longValue());
        assertEquals(PaTestData.LEADER, ((Number) task.get("leader_resource_id")).longValue(),
                "上级必须来自 HrmResource.managerid");
        assertEquals(r.getSheetId(), ((Number) task.get("sheet_id")).longValue());

        List<String> types = jdbc.queryForList(
                "SELECT rater_type FROM uf_assess_task_rater WHERE task_id = ? ORDER BY id",
                String.class, r.getTaskId());
        assertEquals(List.of("SELF", "LEADER", "RATER360", "RATER360"), types);
        assertTrue(r.getRaters().stream().allMatch(l -> l.getAccessToken() != null && !l.getAccessToken().isBlank()));
        assertTrue(r.getRaters().stream().allMatch(l -> l.getPath().startsWith("/pa/assess-fill/")),
                "新链条的链接必须落在 /pa/assess-fill/，否则评价人点开是 404");
    }

    @Test
    @DisplayName("发起：邀请消息一人一行写进 uf_push_msg（同人多角色不合并）")
    void writesOneNoticePerRater() {
        PaTaskVO.CreateResult r = taskService.create(req(List.of(PaTestData.RATER_A)));

        assertEquals(3, r.getNoticeCount());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT receiver_id, title, pc_url, mobile_url FROM uf_push_msg ORDER BY id");
        assertEquals(3, rows.size());
        assertEquals(596, jdbc.queryForObject("SELECT msg_type FROM uf_push_msg WHERE id = ?",
                Integer.class, jdbc.queryForObject("SELECT MIN(id) FROM uf_push_msg", Long.class)));
        assertTrue(rows.stream().anyMatch(m -> String.valueOf(m.get("title")).contains("360 评价")),
                "新模型话术要用「360 评价」，不是「客户评价」");
        for (Map<String, Object> row : rows) {
            // 落地页决定评价人能不能打开：写旧前缀会把人送到另一条链路的页面
            assertTrue(String.valueOf(row.get("pc_url")).contains("/pa/assess-fill/"),
                    "pc_url=" + row.get("pc_url"));
            assertTrue(String.valueOf(row.get("mobile_url")).contains("/m/pa/assess-fill/"),
                    "mobile_url=" + row.get("mobile_url"));
        }
    }

    @Test
    @DisplayName("口径 #9：360 名单为空也允许发起（岗位可能没有 360 项）")
    void allowsEmptyRater360List() {
        PaTaskVO.CreateResult r = taskService.create(req(null));

        assertEquals(0, r.getRater360Count());
        assertEquals(2, r.getRaters().size(), "自评 + 上级");
    }

    @Test
    @DisplayName("360 名单去重且剔除 0/负数，重复勾选不会生成两条链接")
    void dedupesRater360() {
        PaTaskVO.CreateResult r = taskService.create(
                req(List.of(PaTestData.RATER_A, PaTestData.RATER_A, 0L, PaTestData.RATER_B)));

        assertEquals(2, r.getRater360Count());
        assertEquals(4, r.getRaters().size());
    }

    // ================================================================ 闸门

    @Test
    @DisplayName("拒绝：被测人当自己的 360（自评是独立链接，重复只会两条都交不上）")
    void rejectsTargetAsRater360() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.TARGET))));
        assertEquals(40306, e.getCode());
        assertNothingWritten();
    }

    @Test
    @DisplayName("拒绝：被测人在 OA 里没有直接上级，提示里要带人名")
    void rejectsWhenLeaderMissing() {
        jdbc.update("UPDATE HrmResource SET managerid = 0 WHERE id = ?", PaTestData.TARGET);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40303, e.getCode());
        assertTrue(e.getMessage().contains("张被测"), e.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("拒绝：360 里有人登不进系统（离职），一次列出全部问题")
    void rejectsIneligibleRater() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A, PaTestData.RESIGNED))));
        assertEquals(40305, e.getCode());
        assertTrue(e.getMessage().contains("孙离职"), e.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("拒绝：360 里有人没有 OA 登录名")
    void rejectsRaterWithoutLoginId() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.NO_LOGIN))));
        assertEquals(40305, e.getCode());
        assertTrue(e.getMessage().contains("周无账号"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：被测人本人登不进系统时，错误指向被测人而不是笼统的「有人有问题」")
    void rejectsIneligibleTarget() {
        jdbc.update("UPDATE HrmResource SET status = 5 WHERE id = ?", PaTestData.TARGET);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40304, e.getCode());
        assertTrue(e.getMessage().contains("张被测"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：年度已关闭")
    void rejectsClosedPeriod() {
        jdbc.update("UPDATE uf_assess_period SET status = 1 WHERE id = ?", periodId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40302, e.getCode());
        assertTrue(e.getMessage().contains("已关闭"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：同一年度已有进行中的任务，必须先撤回")
    void rejectsSecondRunningTask() {
        taskService.create(req(List.of(PaTestData.RATER_A)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40301, e.getCode());
        assertTrue(e.getMessage().contains("撤回"), e.getMessage());
    }

    @Test
    @DisplayName("拒绝：没传岗位（发起页必须当场选岗，不再有「映射」兜底）")
    void rejectsWhenPostMissing() {
        PaTaskDTO.CreateRequest req = req(List.of(PaTestData.RATER_A));
        req.setPostId(null);

        BusinessException e = assertThrows(BusinessException.class, () -> taskService.create(req));
        assertEquals(40307, e.getCode());
        assertNothingWritten();
    }

    @Test
    @DisplayName("拒绝：岗位已停用，不能再用于新的测评")
    void rejectsWhenPostDisabled() {
        jdbc.update("UPDATE uf_pa_post SET status = 0 WHERE id = ?", postId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40307, e.getCode());
        assertTrue(e.getMessage().contains("停用"), e.getMessage());
        assertNothingWritten();
    }

    // ================================================================ 人岗同部门

    @Test
    @DisplayName("拒绝：被测人不属于岗位所在部门（前端按部门收窄了候选，后端兜住绕过前端的调用）")
    void rejectsWhenTargetInAnotherDepartment() {
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (802, '质量科', '质量科', '0')");
        jdbc.update("UPDATE HrmResource SET departmentid = 802 WHERE id = ?", PaTestData.TARGET);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40308, e.getCode());
        assertTrue(e.getMessage().contains("张被测"), e.getMessage());
        assertTrue(e.getMessage().contains("人事专员"), e.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("拒绝：被测人在 OA 里没有部门（departmentid 为 0），不能按部门岗位测评")
    void rejectsWhenTargetHasNoDepartment() {
        jdbc.update("UPDATE HrmResource SET departmentid = 0 WHERE id = ?", PaTestData.TARGET);

        BusinessException e = assertThrows(BusinessException.class,
                () -> taskService.create(req(List.of(PaTestData.RATER_A))));
        assertEquals(40308, e.getCode());
        assertTrue(e.getMessage().contains("没有部门"), e.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("全公司通用岗位：跨部门的人也能发起，报告部门取被测人自己的部门（sql/63）")
    void genericPostAllowsCrossDepartmentTarget() {
        // 通用岗位：部门为 null
        com.company.ability.dto.PaPostCreateDTO post = new com.company.ability.dto.PaPostCreateDTO();
        post.setDepartmentId(null);
        post.setPostName("会计");
        long genericPostId = postService.createPost(post);
        long cat = data.category(modelService, genericPostId, "通用能力");
        data.item(modelService, genericPostId, cat, "核算能力", "100", 1, 0);
        data.bands(modelService, genericPostId);

        // 被测人挪到另一个部门 —— 对通用岗位来说这不是错误
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (802, '质量科', '质量科', '0')");
        jdbc.update("UPDATE HrmResource SET departmentid = 802 WHERE id = ?", PaTestData.TARGET);

        PaTaskDTO.CreateRequest r = req(List.of());
        r.setPostId(genericPostId);
        PaTaskVO.CreateResult result = assertDoesNotThrow(() -> taskService.create(r),
                "通用岗位不限定部门，跨部门组合必须放行");

        Map<String, Object> sheet = jdbc.queryForMap(
                "SELECT department_id, department_name, post_name FROM uf_pa_sheet WHERE id = ?",
                jdbc.queryForObject("SELECT sheet_id FROM uf_assess_task WHERE id = ?",
                        Long.class, result.getTaskId()));
        assertEquals(802L, ((Number) sheet.get("department_id")).longValue(),
                "岗位没有部门，测评页的部门必须取被测人自己的 —— 否则报告上部门是空的");
        assertEquals("质量科", sheet.get("department_name"));
        assertEquals("会计", sheet.get("post_name"));
    }

    @Test
    @DisplayName("普通岗位：测评页部门快照仍取岗位部门（存量行为不变）")
    void departmentPostKeepsSheetDepartmentFromPost() {
        PaTaskVO.CreateResult result = taskService.create(req(List.of(PaTestData.RATER_A)));

        Map<String, Object> sheet = jdbc.queryForMap(
                "SELECT department_id, department_name FROM uf_pa_sheet WHERE id = ?",
                jdbc.queryForObject("SELECT sheet_id FROM uf_assess_task WHERE id = ?",
                        Long.class, result.getTaskId()));
        assertEquals(PaTestData.DEPT, ((Number) sheet.get("department_id")).longValue());
        assertEquals("人力资源部", sheet.get("department_name"));
    }

    // ================================================================ 预检与读取

    @Test
    @DisplayName("预检：全员合格时 ok=true，不写任何数据")
    void precheckOk() {
        PaTaskDTO.PrecheckRequest pre = new PaTaskDTO.PrecheckRequest();
        pre.setYear(PaTestData.YEAR);
        pre.setResourceId(PaTestData.TARGET);
        pre.setRater360ResourceIds(List.of(PaTestData.RATER_A, PaTestData.RATER_B));

        PaTaskVO.PrecheckResult r = taskService.precheck(pre);

        assertTrue(r.isOk());
        assertTrue(r.getProblems().isEmpty());
        assertNothingWritten();
    }

    @Test
    @DisplayName("预检：不合格的人带角色名列出，HR 一次看到全部")
    void precheckListsProblemsWithRoles() {
        PaTaskDTO.PrecheckRequest pre = new PaTaskDTO.PrecheckRequest();
        pre.setYear(PaTestData.YEAR);
        pre.setResourceId(PaTestData.TARGET);
        pre.setRater360ResourceIds(List.of(PaTestData.RESIGNED, PaTestData.NO_LOGIN));

        PaTaskVO.PrecheckResult r = taskService.precheck(pre);

        assertFalse(r.isOk());
        assertEquals(2, r.getProblems().size());
        assertTrue(r.getProblems().stream().allMatch(p -> "360 测评人".equals(p.getRole())));
        assertTrue(r.getProblems().stream().anyMatch(p -> "孙离职".equals(p.getName())));
    }

    @Test
    @DisplayName("岗位信息：带出岗位、能力项数、360 项数与权重合计")
    void resolvesPost() {
        PaTaskVO.PostInfo info = taskService.resolvePost(postId);

        assertEquals(postId, info.getPostId());
        assertEquals("人力资源部", info.getDepartmentName());
        assertEquals("人事专员", info.getPostName());
        assertEquals(2, info.getItemCount());
        assertEquals(1, info.getRater360ItemCount(), "只有「协作能力」配了 360 主体");
        assertEquals(0, new java.math.BigDecimal("100").compareTo(info.getWeightTotal()));
        assertNull(info.getMessage());
    }

    @Test
    @DisplayName("岗位信息：带出需要 360 评的能力项明细，只列 360 项且带类别与评价主体原文")
    void resolvesRater360Items() {
        // 评价主体说明来自模型里的原文，夹具默认不填，这里补上一条带业务含义的写法
        jdbc.update("UPDATE uf_pa_item SET rater_text = '内部客户360＋直接上级' WHERE id = ?", itemWith360);
        PaTaskVO.PostInfo info = taskService.resolvePost(postId);

        assertEquals(1, info.getRater360Items().size(), "只列 360 项，不把只由上级评的项混进来");
        PaTaskVO.Rater360ItemBrief brief = info.getRater360Items().get(0);
        assertEquals("协作能力", brief.getItemName());
        assertEquals("通用能力", brief.getCategoryName());
        assertEquals("内部客户360＋直接上级", brief.getRaterText(),
                "原文照抄：不按 rater_leader/rater_360 反推成「360 测评人」");
    }

    @Test
    @DisplayName("岗位信息：能力项没填评价主体原文时给 null，不凭空编一个")
    void resolvesRater360ItemsWithoutRaterText() {
        PaTaskVO.PostInfo info = taskService.resolvePost(postId);

        assertNull(info.getRater360Items().get(0).getRaterText(),
                "夹具没填原文就该是 null，页面显示「—」");
    }

    @Test
    @DisplayName("岗位信息：没选岗时提示先选，而不是等到点发起才报错")
    void resolvePostExplainsMissingChoice() {
        PaTaskVO.PostInfo info = taskService.resolvePost(null);

        assertNull(info.getPostId());
        assertTrue(info.getMessage().contains("请先选择岗位"), info.getMessage());
    }

    @Test
    @DisplayName("岗位信息：岗位已停用时给出明确原因")
    void resolvePostExplainsDisabled() {
        jdbc.update("UPDATE uf_pa_post SET status = 0 WHERE id = ?", postId);

        PaTaskVO.PostInfo info = taskService.resolvePost(postId);

        assertNull(info.getPostId());
        assertTrue(info.getMessage().contains("停用"), info.getMessage());
    }

    @Test
    @DisplayName("岗位信息：岗位没有启用项时要提前告知，否则发起必失败")
    void resolvePostWarnsWhenNoItems() {
        jdbc.update("UPDATE uf_pa_item SET status = 0 WHERE post_id = ?", postId);

        PaTaskVO.PostInfo info = taskService.resolvePost(postId);

        assertEquals(0, info.getItemCount());
        assertTrue(info.getMessage().contains("岗位能力模型"), info.getMessage());
    }

    @Test
    @DisplayName("直接上级：只读组织关系，带部门名用于区分同名的人")
    void resolvesLeader() {
        PaTaskVO.LeaderInfo info = taskService.resolveLeader(PaTestData.TARGET);

        assertEquals(PaTestData.LEADER, info.getLeaderResourceId());
        assertEquals("李上级", info.getLeaderName());
        assertEquals("人力资源部", info.getDeptName());
    }

    // ================================================================ 按项指派 360（sql/60）

    @Test
    @DisplayName("按项指派：一项一组人，落指派行；同一人评多项只有一条链接")
    void assignsRater360PerItem() {
        PaTaskDTO.CreateRequest req = req(null);
        // 只有 itemWith360 配了 360 主体；再造一个 360 项，让「一项一人」真正可测
        long itemWith360b = data.item(modelService, postId, jdbc.queryForObject(
                "SELECT category_id FROM uf_pa_item WHERE id = ?", Long.class, itemWith360),
                "沟通表达", "0", 0, 1);
        req.setRater360Items(List.of(
                rater360Item(itemWith360, PaTestData.RATER_A, PaTestData.RATER_B),
                rater360Item(itemWith360b, PaTestData.RATER_A)));

        PaTaskVO.CreateResult r = taskService.create(req);

        assertEquals(2, r.getRater360Count(), "两个人，不是三条（同人两项只发一条链接）");
        assertEquals(4, r.getRaters().size(), "自评 + 上级 + 360×2");
        Map<Long, Integer> byRater = new java.util.HashMap<>();
        for (PaTaskVO.RaterLink link : r.getRaters()) {
            if ("RATER360".equals(link.getRaterType())) {
                byRater.put(link.getRaterResourceId(), link.getItemCount());
            }
        }
        assertEquals(2, byRater.get(PaTestData.RATER_A), "王甲被指派两项");
        assertEquals(1, byRater.get(PaTestData.RATER_B), "赵乙只被指派一项");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.rater_resource_id, ri.item_id FROM uf_assess_task_rater_item ri" +
                " JOIN uf_assess_task_rater tr ON tr.id = ri.rater_id ORDER BY tr.rater_resource_id, ri.item_id");
        assertEquals(3, rows.size(), "2 + 1 条指派");
        assertEquals(itemWith360, ((Number) rows.get(0).get("item_id")).longValue());
    }

    @Test
    @DisplayName("按项指派：项不在岗位里 / 没配 360 主体都拒绝，不悄悄忽略")
    void rejectsInvalidAssignedItem() {
        PaTaskDTO.CreateRequest notInPost = req(null);
        notInPost.setRater360Items(List.of(rater360Item(99999L, PaTestData.RATER_A)));
        assertEquals(40305, assertThrows(BusinessException.class,
                () -> taskService.create(notInPost)).getCode());

        long leaderOnly = jdbc.queryForObject(
                "SELECT id FROM uf_pa_item WHERE post_id = ? AND rater_360 = 0", Long.class, postId);
        PaTaskDTO.CreateRequest no360 = req(null);
        no360.setRater360Items(List.of(rater360Item(leaderOnly, PaTestData.RATER_A)));
        BusinessException e = assertThrows(BusinessException.class, () -> taskService.create(no360));
        assertEquals(40305, e.getCode());
        assertTrue(e.getMessage().contains("360 测评人"), e.getMessage());
        assertNothingWritten();
    }

    @Test
    @DisplayName("按项指派：新入参优先于旧入参（合并会带来「谁评哪项」的歧义）")
    void newAssignWinsOverLegacy() {
        PaTaskDTO.CreateRequest req = req(List.of(PaTestData.RATER_B));
        req.setRater360Items(List.of(rater360Item(itemWith360, PaTestData.RATER_A)));

        PaTaskVO.CreateResult r = taskService.create(req);

        assertEquals(1, r.getRater360Count());
        assertEquals(PaTestData.RATER_A, r.getRaters().stream()
                .filter(l -> "RATER360".equals(l.getRaterType()))
                .findFirst().orElseThrow().getRaterResourceId());
    }

    @Test
    @DisplayName("旧入参：这批人评岗位里全部 360 项，指派行恰好是这些项")
    void legacyInputExpandsToAll360Items() {
        PaTaskVO.CreateResult r = taskService.create(req(List.of(PaTestData.RATER_A)));

        List<Long> items = jdbc.queryForList(
                "SELECT ri.item_id FROM uf_assess_task_rater_item ri" +
                " JOIN uf_assess_task_rater tr ON tr.id = ri.rater_id" +
                " WHERE tr.task_id = ? AND tr.rater_type = 'RATER360'", Long.class, r.getTaskId());
        assertEquals(List.of(itemWith360), items);
    }

    @Test
    @DisplayName("预检：前端把逐项选人拍平成一份去重名单，只出现在某一项里的人也要查出来")
    void precheckUsesFlattenedRaterList() {
        PaTaskDTO.PrecheckRequest pre = new PaTaskDTO.PrecheckRequest();
        pre.setYear(PaTestData.YEAR);
        pre.setResourceId(PaTestData.TARGET);
        pre.setRater360ResourceIds(List.of(
                PaTestData.RATER_A, PaTestData.RESIGNED, PaTestData.RATER_A));

        PaTaskVO.PrecheckResult result = taskService.precheck(pre);

        assertFalse(result.isOk());
        assertTrue(result.getProblems().stream()
                        .anyMatch(p -> "孙离职".equals(p.getName())),
                "只出现在某一项里的人也要被查出来");
        assertEquals(1, result.getProblems().size(), "同一个人传两次只报一次");
    }

    // ================================================================ 辅助

    private static PaTaskDTO.Rater360Item rater360Item(long itemId, Long... resourceIds) {
        PaTaskDTO.Rater360Item row = new PaTaskDTO.Rater360Item();
        row.setItemId(itemId);
        row.setResourceIds(List.of(resourceIds));
        return row;
    }

    private PaTaskDTO.CreateRequest req(List<Long> rater360) {
        PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
        req.setYear(PaTestData.YEAR);
        req.setResourceId(PaTestData.TARGET);
        req.setPostId(postId);
        req.setRater360ResourceIds(rater360);
        return req;
    }

    /** 闸门的价值在于「没发出去」：既没任务，也没消息 */
    private void assertNothingWritten() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM uf_assess_task", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM uf_assess_task_rater", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM uf_push_msg", Integer.class));
    }
}
