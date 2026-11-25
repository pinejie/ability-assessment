package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.support.PaTestData;
import com.company.ability.vo.PaReportVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告里 360 逐人明细与姓名可见性（2026-11-23）。
 *
 * <p>两块口径必须一起锁住，它们是一体的：</p>
 * <ul>
 *   <li><b>逐人展开</b>：360 从「一个平均分」改成一人一列。列的顺序与「谁没评」都必须来自
 *       该页最近一次任务的名单，不能来自评级行 —— 否则「张三没交」会从报告上消失，
 *       而「谁交了谁没交」正是 HR 打开报告要看的第一件事。</li>
 *   <li><b>姓名收口</b>：只有维度管理员（或系统管理员）拿到 {@code raterName}，其余为 null。
 *       判断在服务层，非管理员调接口时姓名不出现在响应里 —— 这不是前端藏起来的。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("报告 360 逐人明细与姓名可见性")
class PaReportRater360Test {

    /** 测试配置里的超管（application-test.yml: assess.bootstrap-admins） */
    private static final String SUPER_ADMIN_LOGINID = "boot-admin1";

    @Autowired
    private PaReportService reportService;
    @Autowired
    private PaSheetService sheetService;
    @Autowired
    private PaScoreService scoreService;
    @Autowired
    private PaPostService postService;
    @Autowired
    private PaModelService modelService;
    @Autowired
    private AssessDimensionService dimensionService;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DimensionIntegrationService integrationService;

    private PaTestData data;
    private long periodId;
    private long postId;
    private long sheetId;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc, integrationService);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);

        long cat = data.category(modelService, postId, "通用能力");
        data.item(modelService, postId, cat, "沟通能力", "50", 1, 1);
        data.item(modelService, postId, cat, "协作能力", "50", 1, 1);

        sheetId = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId).getId();
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    @Test
    @DisplayName("逐人明细：按任务名单顺序一人一格，没评的人也在（levelName 为 null）")
    void rowsCarryEveryRaterInTaskOrder() {
        long task = taskWithRaters(PaTestData.RATER_A, PaTestData.RATER_B);
        // 只有甲交了，乙没交 —— 乙仍必须出现在列里，否则「谁没交」就看不见了
        submit(task, PaTestData.RATER_A, "优秀", "待提升");
        scoreService.finish(task);

        PaReportVO.Detail d = reportService.detail(sheetId);
        PaReportVO.Row first = d.getRows().get(0);

        assertEquals(2, first.getRater360s().size(), "名单里 2 个 360，就要有 2 列");
        assertEquals(PaTestData.RATER_A, first.getRater360s().get(0).getResourceId(), "顺序取任务的插入顺序");
        assertEquals(PaTestData.RATER_B, first.getRater360s().get(1).getResourceId());

        PaReportVO.RaterEntry a = first.getRater360s().get(0);
        assertEquals("优秀", a.getLevelName());
        assertEquals(0, new BigDecimal("5").compareTo(a.getScore()));

        PaReportVO.RaterEntry b = first.getRater360s().get(1);
        assertNull(b.getLevelName(), "没评的人是 null，不是空字符串");
        assertNull(b.getScore());

        // 平均分仍按「未评不进分母」算：只有甲交了 5 分
        assertEquals(0, new BigDecimal("5").compareTo(first.getRater360Score()));
    }

    @Test
    @DisplayName("姓名默认不给：未登录（无身份）时 raterName 全是 null")
    void namesHiddenWithoutIdentity() {
        long task = taskWithRaters(PaTestData.RATER_A);
        submit(task, PaTestData.RATER_A, "优秀", "优秀");

        PaTestData.logout();

        PaReportVO.RaterEntry entry = reportService.detail(sheetId).getRows().get(0).getRater360s().get(0);
        assertEquals(PaTestData.RATER_A, entry.getResourceId(), "人和他的评级照给");
        assertEquals("优秀", entry.getLevelName());
        assertNull(entry.getRaterName(), "没有登录身份时连姓名都不查");
    }

    @Test
    @DisplayName("普通评价人看不到姓名，维度管理员与系统管理员能看到")
    void nameVisibilityFollowsDimensionAdmin() {
        long task = taskWithRaters(PaTestData.RATER_A);
        submit(task, PaTestData.RATER_A, "优秀", "优秀");

        // 1. 普通用户（被测评人自己）看不到
        PaTestData.login(PaTestData.TARGET);
        assertNull(raterNameOf(), "不是维度管理员就看不到 360 姓名");
        PaTestData.logout();

        // 2. 配成该维度的管理员之后能看到
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                PaTestData.DIM, PaTestData.TARGET);
        PaTestData.login(PaTestData.TARGET);
        assertEquals("王甲", raterNameOf(), "维度管理员看得到姓名");
        PaTestData.logout();

        // 3. 系统管理员不需要被列进维度管理员
        PaTestData.loginAs(PaTestData.LEADER, SUPER_ADMIN_LOGINID);
        assertEquals("王甲", raterNameOf(), "系统管理员核对报告也要能看见姓名");
        PaTestData.logout();
    }

    @Test
    @DisplayName("别的维度的管理员看不到本维度的姓名")
    void adminOfAnotherDimensionSeesNothing() {
        long task = taskWithRaters(PaTestData.RATER_A);
        submit(task, PaTestData.RATER_A, "优秀", "优秀");

        // 把被测人配成「另一个维度」的管理员：维度隔离必须生效
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                PaTestData.DIM + 1, PaTestData.TARGET);
        PaTestData.login(PaTestData.TARGET);

        assertNull(raterNameOf(), "维度管理员的身份只在被管的那个维度里成立");
    }

    @Test
    @DisplayName("有评级却不在名单里的人追加到末尾，数据不丢")
    void ratingOutsideRosterIsAppended() {
        long task = taskWithRaters(PaTestData.RATER_A);
        // 手工塞一条乙的评级：模拟换过任务 / 改过库的存量异常
        submitOneItem(task, PaTestData.RATER_B, "待提升");

        PaTestData.login(PaTestData.TARGET);
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                PaTestData.DIM, PaTestData.TARGET);

        List<PaReportVO.RaterEntry> raters = reportService.detail(sheetId).getRows().get(0).getRater360s();
        assertEquals(2, raters.size(), "名单 1 人 + 名单外 1 人");
        assertEquals(PaTestData.RATER_A, raters.get(0).getResourceId(), "名单顺序在前");
        assertEquals(PaTestData.RATER_B, raters.get(1).getResourceId(), "名单外的追加在后");
        assertEquals("赵乙", raters.get(1).getRaterName());
    }

    @Test
    @DisplayName("该页没有任务时逐人明细为空列表，不是 null")
    void noTaskMeansEmptyList() {
        List<PaReportVO.RaterEntry> raters = reportService.detail(sheetId).getRows().get(0).getRater360s();
        assertNotNull(raters, "前端 v-for 直接遍历，null 会报错");
        assertTrue(raters.isEmpty());
    }

    // ---------------- 按项指派（sql/60，2026-11-23） ----------------

    @Test
    @DisplayName("按项指派：分到别的项的人在本项是「未分配」，不是「未评」")
    void unassignedRaterShowsNotAssigned() {
        long task = taskWithRaters(PaTestData.RATER_A, PaTestData.RATER_B);
        List<Map<String, Object>> items = items();
        // 甲只评第一项，乙只评第二项
        assign(task, PaTestData.RATER_A, items.get(0).get("item_id"));
        assign(task, PaTestData.RATER_B, items.get(1).get("item_id"));
        submitOneItem(task, PaTestData.RATER_A, "优秀");

        List<PaReportVO.Row> rows = reportService.detail(sheetId).getRows();

        PaReportVO.RaterEntry aOnFirst = rows.get(0).getRater360s().get(0);
        PaReportVO.RaterEntry bOnFirst = rows.get(0).getRater360s().get(1);
        assertEquals(Boolean.TRUE, aOnFirst.getAssigned(), "甲被指派了第一项");
        assertEquals("优秀", aOnFirst.getLevelName());
        assertEquals(Boolean.FALSE, bOnFirst.getAssigned(), "乙没被指派第一项");
        assertNull(bOnFirst.getLevelName());

        PaReportVO.RaterEntry aOnSecond = rows.get(1).getRater360s().get(0);
        assertEquals(Boolean.FALSE, aOnSecond.getAssigned(), "甲没被指派第二项");
        assertEquals(Boolean.TRUE, rows.get(1).getRater360s().get(1).getAssigned());
    }

    @Test
    @DisplayName("存量任务（没有指派行）里所有人都是「已分配但未评」，不能显示成未分配")
    void legacyTaskTreatsEveryoneAsAssigned() {
        long task = taskWithRaters(PaTestData.RATER_A, PaTestData.RATER_B);
        submitOneItem(task, PaTestData.RATER_A, "优秀");

        List<PaReportVO.RaterEntry> raters = reportService.detail(sheetId).getRows().get(0).getRater360s();

        assertTrue(raters.stream().allMatch(r -> Boolean.TRUE.equals(r.getAssigned())),
                "没有指派行 = 老口径「这批人评全部项」，谁的格子都该是「未评」而不是「未分配」");
    }

    @Test
    @DisplayName("按项指派时，不由 360 评的项对所有人都是未分配")
    void itemWithoutRater360ShowsNotAssigned() {
        // 把第二项改成「只由上级评」：快照与岗位配置一起改，模拟这种项
        List<Map<String, Object>> items = items();
        long leaderOnly = ((Number) items.get(1).get("item_id")).longValue();
        jdbc.update("UPDATE uf_pa_sheet_item SET rater_360 = 0 WHERE sheet_id = ? AND item_id = ?", sheetId, leaderOnly);
        jdbc.update("UPDATE uf_pa_item SET rater_360 = 0 WHERE id = ?", leaderOnly);

        long task = taskWithRaters(PaTestData.RATER_A);
        assign(task, PaTestData.RATER_A, items.get(0).get("item_id"));

        List<PaReportVO.RaterEntry> onLeaderOnly = reportService.detail(sheetId).getRows().get(1).getRater360s();
        assertEquals(1, onLeaderOnly.size());
        assertEquals(Boolean.FALSE, onLeaderOnly.get(0).getAssigned(),
                "这一项本来就没让 360 评，写「未评」等于催他做一件不归他的事");
    }

    // ---------------- 夹具 ----------------

    /** 落一条按项指派（uf_assess_task_rater_item） */
    private void assign(long taskId, long raterResourceId, Object itemId) {
        long raterId = jdbc.queryForObject(
                "SELECT id FROM uf_assess_task_rater WHERE task_id = ? AND rater_resource_id = ? AND rater_type = 'RATER360'",
                Long.class, taskId, raterResourceId);
        jdbc.update("INSERT INTO uf_assess_task_rater_item (task_id, rater_id, item_id) VALUES (?, ?, ?)",
                taskId, raterId, ((Number) itemId).longValue());
    }

    /** 建任务并登记 360 名单（顺序即参数顺序，报告列顺序取它） */
    private long taskWithRaters(long... rater360Ids) {
        jdbc.update("INSERT INTO uf_assess_task (company_id, dimension_id, period_id, sheet_id, resource_id," +
                        " department_id, leader_resource_id, status, rating_count, client_count, create_by)" +
                        " VALUES (1, ?, ?, ?, ?, ?, ?, 0, 2, ?, 1)",
                PaTestData.DIM, periodId, sheetId, PaTestData.TARGET, PaTestData.DEPT,
                PaTestData.LEADER, rater360Ids.length);
        long taskId = jdbc.queryForObject("SELECT MAX(id) FROM uf_assess_task", Long.class);
        rater(taskId, "SELF", PaTestData.TARGET);
        rater(taskId, "LEADER", PaTestData.LEADER);
        for (long raterId : rater360Ids) {
            rater(taskId, "RATER360", raterId);
        }
        return taskId;
    }

    private void rater(long taskId, String type, long resourceId) {
        jdbc.update("INSERT INTO uf_assess_task_rater (task_id, rater_resource_id, rater_type, status, access_token)" +
                " VALUES (?, ?, ?, 0, ?)", taskId, resourceId, type, "tok-" + taskId + "-" + type + "-" + resourceId);
    }

    /** 按「第一项 优秀 / 第二项 待提升」以外的组合写满两项评级 */
    private void submit(long taskId, long raterId, String firstLevel, String secondLevel) {
        List<Map<String, Object>> items = items();
        rating(raterId, items.get(0), firstLevel);
        rating(raterId, items.get(1), secondLevel);
    }

    /** 只给第一项写一条评级（用于「名单外的人也有评级」） */
    private void submitOneItem(long taskId, long raterId, String levelName) {
        rating(raterId, items().get(0), levelName);
    }

    private void rating(long raterId, Map<String, Object> item, String levelName) {
        Map<String, Object> level = jdbc.queryForMap(
                "SELECT id, level_name, score FROM uf_pa_item_level WHERE item_id = ? AND level_name = ?",
                item.get("item_id"), levelName);
        jdbc.update("INSERT INTO uf_pa_rating (dimension_id, sheet_id, item_id, rater_type, rater_resource_id," +
                        " level_id, level_name, score) VALUES (?, ?, ?, 'RATER360', ?, ?, ?, ?)",
                PaTestData.DIM, sheetId, item.get("item_id"), raterId,
                level.get("id"), level.get("level_name"), level.get("score"));
    }

    private List<Map<String, Object>> items() {
        return jdbc.queryForList(
                "SELECT item_id FROM uf_pa_sheet_item WHERE sheet_id = ? ORDER BY sort_no", sheetId);
    }

    private String raterNameOf() {
        return reportService.detail(sheetId).getRows().get(0).getRater360s().get(0).getRaterName();
    }
}
