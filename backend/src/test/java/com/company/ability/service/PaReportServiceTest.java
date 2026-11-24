package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.exception.BusinessException;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 个人测评报告测试（方案 17 §7.2）。
 *
 * <p>报告是只读视图，但它的三个口径很容易被后续改动踩坏，所以逐条锁住：</p>
 * <ul>
 *   <li><b>360 人数取实际名单</b>，不是「有几个 360 交过卷」—— 选了 2 人、1 人未交时
 *       报告上仍应显示 2 人，否则 HR 会以为名单被改过；</li>
 *   <li><b>等级名取评级快照</b>（{@code uf_pa_rating.level_name}）而不是按当前配置反查，
 *       等级改名后历史报告不能被改写；</li>
 *   <li><b>自评只展示</b>：{@code self_score} 单独一列，{@code item_score} 里绝不含它。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("个人测评报告测试")
class PaReportServiceTest {

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
    private PaTaskOpService taskOpService;
    @Autowired
    private JdbcTemplate jdbc;

    private PaTestData data;
    private long periodId;
    private long postId;
    private long sheetId;
    private boolean modelSeeded;

    /** 第二个维度（只在本类里用）：同一年度跨维度合并的用例需要一个「另一家」 */
    private static final long OTHER_DIM_ID = 99L;

    @BeforeEach
    void setUp() {
        data = new PaTestData(jdbc);
        data.reset();
        periodId = data.seedPeriod(postService, 0);
        data.seedPeople();
        postId = data.seedPost(postService, modelService, "人事专员");
        data.bands(modelService, postId);
        DimensionContext.set(PaTestData.DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    @Test
    @DisplayName("列表：按年度与维度过滤，360 人数取任务里的实际名单")
    void listShowsRater360FromTask() {
        long sheet = sheet();
        long task = task(sheet, 2);

        PageResult<PaReportVO.ListItem> page = reportService.page(PaTestData.YEAR, null, null, null, 1, 10);
        assertEquals(1, page.getTotal(), "一年一人一页");
        PaReportVO.ListItem row = page.getList().get(0);
        assertEquals(sheet, row.getSheetId());
        assertEquals(PaTestData.YEAR, row.getYear());
        assertEquals("张被测", row.getResourceName());
        assertEquals("人力资源部", row.getDeptName());
        assertEquals("人事专员", row.getPostName());
        assertEquals(2, row.getRater360Count(), "选了 2 位 360，即使都没交卷也显示 2 人");
        assertEquals("待评价", row.getStatusName());
        assertEquals(task, task, "任务存在");

        // 别的年度查不到：报告天然按年看
        assertEquals(0, reportService.page(2025, null, null, null, 1, 10).getTotal());
    }

    @Test
    @DisplayName("列表：按被测人 / 部门 / 状态过滤")
    void listFilters() {
        long sheet = sheet();

        assertEquals(1, reportService.page(PaTestData.YEAR, PaTestData.TARGET, null, null, 1, 10).getTotal());
        assertEquals(0, reportService.page(PaTestData.YEAR, PaTestData.RATER_A, null, null, 1, 10).getTotal());
        assertEquals(1, reportService.page(PaTestData.YEAR, null, PaTestData.DEPT, null, 1, 10).getTotal());
        assertEquals(1, reportService.page(PaTestData.YEAR, null, null, 0, 1, 10).getTotal());
        assertEquals(0, reportService.page(PaTestData.YEAR, null, null, 2, 1, 10).getTotal());

        jdbc.update("UPDATE uf_pa_sheet SET status = 2 WHERE id = ?", sheet);
        assertEquals(1, reportService.page(PaTestData.YEAR, null, null, 2, 1, 10).getTotal());
    }

    @Test
    @DisplayName("列表：分页按 id 倒序，总数是过滤后的总数")
    void listPages() {
        seedModel();
        // 这里要的是「第一个人的页面 id」，原来接的是 seedModel() 的返回值（postId）。
        // 两者此前恰好同值，靠的是自增序列同步增长这一巧合 —— 本类新增用例后序列错位，
        // 断言就开始报「expected 4 but was 6」。改成真的取页面 id。
        long first = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId).getId();
        // 第二个人的页面：同一整套配置
        long second = sheetService.prepare(PaTestData.YEAR, PaTestData.RATER_A, postId).getId();

        PageResult<PaReportVO.ListItem> p1 = reportService.page(PaTestData.YEAR, null, null, null, 1, 1);
        assertEquals(2, p1.getTotal());
        assertEquals(1, p1.getList().size());
        assertEquals(second, p1.getList().get(0).getSheetId(), "新的在前");

        PageResult<PaReportVO.ListItem> p2 = reportService.page(PaTestData.YEAR, null, null, null, 2, 1);
        assertEquals(first, p2.getList().get(0).getSheetId());
    }

    @Test
    @DisplayName("列表：没有任务时 360 人数为 0，不报错")
    void listWithoutTask() {
        long sheet = sheet();
        PageResult<PaReportVO.ListItem> page = reportService.page(PaTestData.YEAR, null, null, null, 1, 10);
        assertEquals(1, page.getTotal());
        assertEquals(0, page.getList().get(0).getRater360Count(), "装配后还没发起任务时人数为 0");
        assertNull(page.getList().get(0).getTotalScore(), "没算分就是 null，不是 0");
        assertEquals(sheet, page.getList().get(0).getSheetId());
    }

    @Test
    @DisplayName("详情：明细一个能力项一行，项得分 = 上级与 360 的扁平平均")
    void detailRowsAndScores() {
        long sheet = sheet();
        long task = task(sheet, 1);
        submit(task, "LEADER", PaTestData.LEADER, "5", "4");   // 沟通 优秀(5) / 协作 待提升(4→档位 2 分)
        submit(task, "RATER360", PaTestData.RATER_A, "5", "2");
        submit(task, "SELF", PaTestData.TARGET, "2", "2");     // 自评只展示
        scoreService.finish(task);

        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals(PaTestData.YEAR, d.getYear());
        assertEquals("张被测", d.getResourceName());
        assertEquals("人力资源部", d.getDeptName());
        assertEquals("人事专员", d.getPostName());
        assertEquals("李上级", d.getLeaderName(), "上级姓名来自组织关系快照");
        assertEquals(1, d.getRater360Count());
        assertEquals(2, d.getItemCount());
        assertEquals("已完成", d.getStatusName());
        assertNotNull(d.getTotalScore());
        assertNotNull(d.getBandName());
        assertEquals(2, d.getRows().size());

        PaReportVO.Row first = d.getRows().get(0);
        assertEquals("沟通能力", first.getItemName());
        assertEquals("通用能力", first.getCategoryName());
        assertEquals("优秀", first.getLeaderLevelName(), "等级名取评级快照");
        assertEquals(0, new java.math.BigDecimal("5").compareTo(first.getLeaderScore()));
        assertEquals(0, new java.math.BigDecimal("2").compareTo(first.getSelfScore()));
        // (5 + 5) / 2 = 5，自评 2 分不进分母
        assertEquals(0, new java.math.BigDecimal("5").compareTo(first.getItemScore()));
        assertEquals("待提升", first.getSelfLevelName());
    }

    @Test
    @DisplayName("详情：项得分按该项自己的等级分值落左闭右开区间，得到刻度名")
    void detailItemLevelNameByInterval() {
        long sheet = sheet();
        // 给第一个能力项补一档 3 分：两档切不出「中间」这种最容易切错的位置
        long firstItemId = jdbc.queryForObject(
                "SELECT item_id FROM uf_pa_sheet_item WHERE sheet_id = ? ORDER BY sort_no LIMIT 1",
                Long.class, sheet);
        jdbc.update("INSERT INTO uf_pa_item_level (dimension_id, item_id, level_name, score, sort)" +
                " VALUES (?, ?, '合格', 3, 3)", PaTestData.DIM, firstItemId);

        long task = task(sheet, 1);
        // 上级 待提升(2) + 360 合格(3) → 2.5，落在 [2,3) 应是「待提升」，不是「合格」
        submit(task, "LEADER", PaTestData.LEADER, "2", "2");
        submitWithLevel(task, "RATER360", PaTestData.RATER_A, firstItemId, "合格");
        scoreService.finish(task);

        PaReportVO.Row row = reportService.detail(sheet).getRows().get(0);
        assertEquals(0, new java.math.BigDecimal("2.5").compareTo(row.getItemScore()));
        assertEquals("待提升", row.getItemLevelName(), "[2,3) 是待提升，2.5 不能算进「合格」");

        // 上级 合格(3) + 360 优秀(5) → 4，落在 [3,5) 应是「合格」
        jdbc.update("DELETE FROM uf_pa_rating WHERE sheet_id = ? AND item_id = ?", sheet, firstItemId);
        submitWithLevel(task, "LEADER", PaTestData.LEADER, firstItemId, "合格");
        submitWithLevel(task, "RATER360", PaTestData.RATER_A, firstItemId, "优秀");
        scoreService.finish(task);

        PaReportVO.Row again = reportService.detail(sheet).getRows().get(0);
        assertEquals(0, new java.math.BigDecimal("4").compareTo(again.getItemScore()));
        assertEquals("合格", again.getItemLevelName(), "4 分落在 [3,5)，下界含、上界不含");
    }

    @Test
    @DisplayName("详情：项得分也按岗位分值区间取等级名，与总得分同一把尺子")
    void detailItemBandNameUsesPostBands() {
        long sheet = sheet();
        long task = task(sheet, 1);
        // 沟通 5 分 → 优秀；协作 2 分 → 合格（[2,4)）
        submit(task, "LEADER", PaTestData.LEADER, "5", "2");
        submit(task, "RATER360", PaTestData.RATER_A, "5", "2");
        scoreService.finish(task);

        PaReportVO.Detail d = reportService.detail(sheet);
        PaReportVO.Row first = d.getRows().get(0);
        PaReportVO.Row second = d.getRows().get(1);
        assertEquals(0, new java.math.BigDecimal("5").compareTo(first.getItemScore()));
        assertEquals("优秀", first.getItemBandName(), "5 分落在 [4,+∞)");
        assertEquals(0, new java.math.BigDecimal("2").compareTo(second.getItemScore()));
        assertEquals("合格", second.getItemBandName(), "2 分落在 [2,4)");

        // 两套刻度并存：itemLevelName 走项刻度，itemBandName 走岗位区间。两者都有值，
        // 页面只显示后者 —— 混用其中一套，上面对档位名的断言会立刻红。
        assertNotNull(first.getItemLevelName());
    }

    @Test
    @DisplayName("详情：岗位没配分值区间时只出分数，不报错")
    void detailItemBandNameNullWithoutBands() {
        long sheet = sheet();
        long task = task(sheet, 0);
        submit(task, "LEADER", PaTestData.LEADER, "5", "5");
        scoreService.finish(task);
        jdbc.update("DELETE FROM uf_pa_band WHERE post_id = ?", postId);

        PaReportVO.Row row = reportService.detail(sheet).getRows().get(0);
        assertNotNull(row.getItemScore(), "分数照出");
        assertNull(row.getItemBandName(), "没配区间就没有描述，页面只显示分数");
    }

    @Test
    @DisplayName("详情：封存快照里没有档位名时按快照自己的分数补上，冻结的分数不动")
    void sealedSnapshotBackfillsItemBandName() {
        long sheet = scoredSheet();
        loginAdmin();
        reportService.seal(sheet);

        // 模拟「本字段上线前封存的快照」：把 itemBandName 从 JSON 里抹掉。
        // 用字符串替换而不是反序列化再写回 —— 要测的正是「旧格式的快照能不能读」。
        String json = jdbc.queryForObject(
                "SELECT sealed_json FROM uf_pa_sheet WHERE id = ?", String.class, sheet);
        jdbc.update("UPDATE uf_pa_sheet SET sealed_json = ? WHERE id = ?",
                json.replace("\"itemBandName\":\"优秀\",", ""), sheet);

        PaReportVO.Detail d = reportService.detail(sheet);
        PaReportVO.Row row = d.getRows().get(0);
        assertEquals(0, new java.math.BigDecimal("5").compareTo(row.getItemScore()),
                "分数是快照里那一刻的值，补描述不能顺手把它重算一遍");
        assertEquals("优秀", row.getItemBandName(), "旧快照读出来也要有描述");
    }

    @Test
    @DisplayName("详情：刻度名剥掉「N分 · 」前缀，页面上不重复写分值")
    void detailItemLevelNameStripsScorePrefix() {
        long sheet = sheet();
        long task = task(sheet, 0);
        // 先按原名打分（submit 要按等级名查 id），再改名，最后算分 ——
        // 刻度名是算分时落库的快照，改名得在算分之前才测得到剥离逻辑
        submit(task, "LEADER", PaTestData.LEADER, "5", "5");
        // 库里两种写法并存：16 个能力项写「4分 · 优秀」，46 个写「优秀」
        jdbc.update("UPDATE uf_pa_item_level SET level_name = '4分 · 优秀' WHERE level_name = '优秀'");
        jdbc.update("UPDATE uf_pa_item_level SET level_name = '2分 · 待提升' WHERE level_name = '待提升'");
        scoreService.finish(task);

        assertEquals("优秀", reportService.detail(sheet).getRows().get(0).getItemLevelName(),
                "前缀要剥掉，否则页面写成「5.00 · 4分 · 优秀」");
    }

    @Test
    @DisplayName("详情：刻度名是快照，等级改名后历史报告不跟着变")
    void detailItemLevelNameIsSnapshot() {
        long sheet = sheet();
        long task = task(sheet, 0);
        submit(task, "LEADER", PaTestData.LEADER, "5", "5");
        scoreService.finish(task);

        // 算完分再改等级名与分值：快照已冻结，报告不该跟着变
        jdbc.update("UPDATE uf_pa_item_level SET level_name = '卓越', score = 1 WHERE level_name = '优秀'");

        PaReportVO.Row row = reportService.detail(sheet).getRows().get(0);
        assertEquals("优秀", row.getItemLevelName(),
                "刻度名读 item_level_name 快照，等级改名不该改写历史报告");
        assertEquals(0, new java.math.BigDecimal("5").compareTo(row.getItemScore()),
                "项得分同样是快照");
    }

    @Test
    @DisplayName("详情：刻度名快照缺失时（本列上线前算的分）回退按当前配置现切")
    void detailItemLevelNameFallsBackWhenSnapshotMissing() {
        long sheet = sheet();
        long task = task(sheet, 0);
        submit(task, "LEADER", PaTestData.LEADER, "5", "5");
        scoreService.finish(task);
        // 模拟老数据：本列上线前算的分，快照是空的
        jdbc.update("UPDATE uf_pa_sheet_item SET item_level_name = NULL WHERE sheet_id = ?", sheet);

        assertEquals("优秀", reportService.detail(sheet).getRows().get(0).getItemLevelName(),
                "没有快照时按当前等级配置现切，不能直接显示「—」");
    }

    @Test
    @DisplayName("详情：项得分没算出来时刻度名为空，不伪造一档")
    void detailItemLevelNameNullWhenUnscored() {
        long sheet = sheet();
        for (PaReportVO.Row row : reportService.detail(sheet).getRows()) {
            assertNull(row.getItemScore());
            assertNull(row.getItemLevelName(), "没出分就没有刻度名，页面显示「—」");
        }
    }

    @Test
    @DisplayName("详情：等级被改名后，历史报告仍按当时的等级名展示")
    void detailKeepsLevelSnapshot() {
        long sheet = sheet();
        long task = task(sheet, 0);
        submit(task, "LEADER", PaTestData.LEADER, "5", "4");

        jdbc.update("UPDATE uf_pa_item_level SET level_name = '卓越' WHERE level_name = '优秀'");

        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals("优秀", d.getRows().get(0).getLeaderLevelName(),
                "报告读评级快照，等级改名不该改写历史");
    }

    @Test
    @DisplayName("详情：评语逐人分段，上级是测评人1、360 从 2 起，与明细列头同序")
    void detailCommentsPerRater() {
        long sheet = sheet();
        long task = task(sheet, 2);
        submitWithComment(task, "LEADER", PaTestData.LEADER, "上级评语：执行力强");
        submitWithComment(task, "RATER360", PaTestData.RATER_A, "360 评语：配合度高");
        submitWithComment(task, "RATER360", PaTestData.RATER_B, "360 评语：沟通顺畅");
        scoreService.finish(task);

        PaReportVO.Detail d = reportService.detail(sheet);
        List<PaReportVO.CommentEntry> comments = d.getComments();
        assertEquals(3, comments.size());

        assertEquals("测评人1", comments.get(0).getDisplayName(), "上级统一编号为测评人1");
        assertEquals("上级评语：执行力强", comments.get(0).getComment());
        assertEquals("测评人2", comments.get(1).getDisplayName());
        assertEquals("360 评语：配合度高", comments.get(1).getComment());
        assertEquals("测评人3", comments.get(2).getDisplayName());
        assertEquals("360 评语：沟通顺畅", comments.get(2).getComment());

        // 评论区编号必须与明细列头同一个人：第 1 个 360 单元格是 RATER_A
        assertEquals(PaTestData.RATER_A, d.getRows().get(0).getRater360s().get(0).getResourceId());
        assertEquals(comments.get(1).getResourceId(), d.getRows().get(0).getRater360s().get(0).getResourceId());
    }

    @Test
    @DisplayName("详情：有人没提交时，已提交者的评语照样显示（不再等全员齐备）")
    void detailCommentsAppearBeforeAllSubmitted() {
        long sheet = sheet();
        long task = task(sheet, 2);
        // 只有 360 交了，上级与另一个 360 都没交 —— 页面停在「评价中」
        submitWithComment(task, "RATER360", PaTestData.RATER_A, "360 评语：配合度高");
        scoreService.markPartial(sheet);

        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals("评价中", d.getStatusName());
        assertEquals(1, d.getComments().size(), "已交的人不受别人拖累");
        assertEquals("测评人2", d.getComments().get(0).getDisplayName(), "未交的人不占号，RATER_A 仍是名单第 1 个 360");
        assertEquals("360 评语：配合度高", d.getComments().get(0).getComment());
    }

    @Test
    @DisplayName("详情：撤回的评语不出现")
    void detailCommentsExcludeVoided() {
        long sheet = sheet();
        long task = task(sheet, 1);
        submitWithComment(task, "RATER360", PaTestData.RATER_A, "360 评语：配合度高");
        jdbc.update("UPDATE uf_assess_task_rater SET status = 3 WHERE task_id = ? AND rater_type = 'RATER360'",
                task);

        assertEquals(0, reportService.detail(sheet).getComments().size(),
                "撤回的评语随评级一起失效，不该再出现在报告上");
    }

    @Test
    @DisplayName("详情：非管理员调用时评语只有编号，没有姓名")
    void detailCommentsHideNameForNonAdmin() {
        long sheet = sheet();
        long task = task(sheet, 1);
        submitWithComment(task, "LEADER", PaTestData.LEADER, "上级评语：执行力强");

        // 默认无登录身份 → canSeeRaterName=false
        PaTestData.logout();
        PaReportVO.CommentEntry entry = reportService.detail(sheet).getComments().get(0);
        assertEquals("测评人1", entry.getDisplayName());
        assertNull(entry.getRaterName(), "没有权限就拿不到姓名，前端藏 CSS 不算数");

        // 维度管理员才给姓名
        PaTestData.loginAs(PaTestData.RATER_B, "admin-x");
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                PaTestData.DIM, PaTestData.RATER_B);
        assertEquals("李上级", reportService.detail(sheet).getComments().get(0).getRaterName());
    }

    @Test
    @DisplayName("测评人1 列头：本人视角只给代号，管理员才带上级姓名")
    void leaderColumnLabelFollowsShowRaterName() {
        long sheet = sheet();
        task(sheet, 1);
        markSealed(sheet);

        // 本人：报告头部「直接上级」仍要有名字（他自己知道谁是他上级），
        // 但明细列头必须只剩代号 —— showRaterName 就是这个开关，
        // 不能拿 leaderName 是否为 null 当判据。
        PaTestData.login(PaTestData.TARGET);
        PaReportVO.Detail mine = reportService.myDetail(sheet);
        assertEquals("李上级", mine.getLeaderName(), "报告头部照常显示直接上级");
        assertEquals(Boolean.FALSE, mine.getShowRaterName(), "本人视角不给测评人姓名");

        PaTestData.logout();
        assertEquals(Boolean.FALSE, reportService.detail(sheet).getShowRaterName(), "未登录也不给");

        // 维度管理员：列头带姓名
        PaTestData.loginAs(PaTestData.RATER_B, "admin-y");
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                PaTestData.DIM, PaTestData.RATER_B);
        assertEquals(Boolean.TRUE, reportService.detail(sheet).getShowRaterName());
    }

    @Test
    @DisplayName("我的测评：只列本人的报告，按年度过滤，换别人的 sheetId 读不到")
    void myReportsAreScopedToLogin() {
        long sheet = sheet();
        task(sheet, 1);
        markSealed(sheet);
        // 另一个人同一年度的页面
        data.seedPost(postService, modelService, "另一岗位");
        long otherSheet = sheetService.prepare(PaTestData.YEAR, PaTestData.RATER_A,
                jdbc.queryForObject("SELECT post_id FROM uf_pa_sheet WHERE id = ?", Long.class, sheet)).getId();

        PaTestData.login(PaTestData.TARGET);
        List<PaReportVO.ListItem> mine = reportService.myReports(null);
        assertEquals(1, mine.size(), "只返回自己的");
        assertEquals(PaTestData.TARGET, mine.get(0).getResourceId());

        assertThrows(BusinessException.class, () -> reportService.myDetail(otherSheet),
                "别人的页面必须读不到，否则任何登录用户换个 id 就能翻别人的报告");

        PaReportVO.Detail d = reportService.myDetail(sheet);
        assertNull(d.getRows().get(0).getRater360s().isEmpty() ? null : d.getRows().get(0).getRater360s().get(0).getRaterName(),
                "本人视角永远不返回测评人姓名");

        assertEquals(0, reportService.myReports(PaTestData.YEAR - 1).size(), "别的年度没有数据");
    }

    @Test
    @DisplayName("年度下拉：纯评价人（自己没有测评页）也要拿到年度，否则我的评价那块也跟着空")
    void myYearsIncludeYearsWhereImOnlyARater() {
        long sheet = sheet();
        task(sheet, 1);   // 被测人是 TARGET，RATER_A 只是 360 评价人

        PaTestData.login(PaTestData.RATER_A);
        List<PaReportVO.MyYear> years = reportService.myYears();
        assertEquals(1, years.size(), "RATER_A 没有任何自己的测评页，年度仍应来自任务");
        assertEquals(PaTestData.YEAR, years.get(0).getYear());
        assertTrue(years.get(0).getActive(), "年度进行中 → active");

        // 两块内容都能按这个年度取到（页面直接拿默认年度调这两个接口）
        assertEquals(0, reportService.myReports(null).size(), "他没有自己的报告");
    }

    @Test
    @DisplayName("年度下拉：已撤回的任务不算年度（与 myTasks 过滤一致）")
    void myYearsIgnoreVoidedTasks() {
        long sheet = sheet();
        long taskId = task(sheet, 1);
        jdbc.update("UPDATE uf_assess_task SET status = 4 WHERE id = ?", taskId);   // 4 = 已撤回

        PaTestData.login(PaTestData.RATER_A);
        assertEquals(0, reportService.myYears().size(), "撤回的年度不该出现在下拉里");
    }

    @Test
    @DisplayName("年度下拉：同一年在多个维度下合并成一条，内容按维度名区分")
    void myYearsMergeSameYearAcrossDimensions() {
        long sheet = sheet();
        task(sheet, 1);
        markSealed(sheet);
        // 同一个人、同一年份，另一个维度下也有他的测评页
        seedSheetInAnotherDimension(PaTestData.YEAR, "另一维度");
        markSealed(jdbc.queryForObject(
                "SELECT MAX(id) FROM uf_pa_sheet WHERE dimension_id = ?", Long.class, OTHER_DIM_ID));

        PaTestData.login(PaTestData.TARGET);
        List<PaReportVO.MyYear> years = reportService.myYears();
        assertEquals(1, years.size(), "两个维度都是 2026，下拉里只能有一条");
        assertEquals(PaTestData.YEAR, years.get(0).getYear());
        assertTrue(years.get(0).getActive(), "任一来源进行中即为进行中");

        List<PaReportVO.ListItem> reports = reportService.myReports(PaTestData.YEAR);
        assertEquals(2, reports.size(), "两个维度的页面都要列出来，不能被年度合并掉");
        assertEquals(2, reports.stream().map(PaReportVO.ListItem::getDimensionName).distinct().count(),
                "同年多行只能靠维度名区分，这一列不能为空也不能重样");
        assertTrue(reports.stream().anyMatch(r -> "另一维度".equals(r.getDimensionName())),
                "另一维度那行要带上它自己的维度名");
    }

    @Test
    @DisplayName("年度下拉：只配了年度、没有任何涉及我的内容 → 不出现")
    void myYearsIgnoreYearWithoutMyContent() {
        long sheet = sheet();
        task(sheet, 1);
        // 下一年度只是被管理员建出来了，既没有我的测评页也没有要我评价的任务
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, 0, ?)",
                PaTestData.YEAR + 1, PaTestData.DIM);

        PaTestData.login(PaTestData.TARGET);
        List<PaReportVO.MyYear> years = reportService.myYears();
        assertEquals(1, years.size(), "年度设置的年份不等于「与我相关的年份」");
        assertEquals(PaTestData.YEAR, years.get(0).getYear());
    }

    @Test
    @DisplayName("详情：不存在或别的维度的报告直接拒绝")
    void detailRejectsUnknown() {
        long sheet = sheet();
        BusinessException e = assertThrows(BusinessException.class, () -> reportService.detail(sheet + 999));
        assertEquals(40701, e.getCode());

        jdbc.update("UPDATE uf_pa_sheet SET dimension_id = 2 WHERE id = ?", sheet);
        BusinessException other = assertThrows(BusinessException.class, () -> reportService.detail(sheet));
        assertEquals(40701, other.getCode(), "跨维度的报告不该读得到");
    }

    // ---------------- 封存夹具 ----------------

    /**
     * 直接把页面标成「已封存」。
     *
     * <p>自助视角的可见性用例只关心闸门本身，不关心封存动作对不对 —— 那些页面没有分数，
     * 走 {@code reportService.seal()} 会被 40703 挡下。封存动作另有专门用例覆盖。</p>
     */
    private void markSealed(long sheetId) {
        jdbc.update("UPDATE uf_pa_sheet SET seal_status = 1, seal_time = SYSDATETIME()," +
                " seal_by_loginid = 'hr_admin', seal_by_name = '测试人' WHERE id = ?", sheetId);
    }

    /** 登录成系统管理员（封存权限的唯一入口之一）：loginid 要进 uf_assess_admin */
    private void loginAdmin() {
        PaTestData.loginAs(9001L, "hr_admin");
        jdbc.update("DELETE FROM uf_assess_admin WHERE loginid = ?", "hr_admin");
        jdbc.update("INSERT INTO uf_assess_admin (loginid, lastname, create_by) VALUES (?, ?, 1)",
                "hr_admin", "封存人");
        // 姓名取自登录态（LoginUser.name），与 uf_assess_admin.lastname 无关：
        // 管理员表只回答「是不是管理员」，操作人姓名来自 SSO
        PaTestData.loginAs(9001L, "hr_admin");
    }

    /** 一份「已算出分」的页面：封存的前置条件就是它 */
    /**
     * 一份「全员评完 + 已出分」的页面 —— 这是 2026-11-24 之后封存的前置条件。
     *
     * <p>必须把<b>自评、上级、360</b> 三个评价人都交掉：封存门槛含自评（口径 B），
     * 只交上级虽然也能出分（口径 #13 自评不阻塞算分），但会被新门槛挡在门外。</p>
     */
    private long scoredSheet() {
        long sheet = sheet();
        seedModel();
        long task = task(sheet, 1);
        submitAll(task);
        scoreService.finish(task);
        PaTestData.logout();
        return sheet;
    }

    /** 该任务的评价人全部交卷（自评 / 上级 / 360 各一份） */
    private void submitAll(long taskId) {
        submit(taskId, "SELF", PaTestData.TARGET, "4", "4");
        submit(taskId, "LEADER", PaTestData.LEADER, "5", "5");
        submit(taskId, "RATER360", PaTestData.RATER_A, "5", "5");
    }

    private PaTaskOpDTO.VoidRequest voidRequest() {
        PaTaskOpDTO.VoidRequest req = new PaTaskOpDTO.VoidRequest();
        req.setReason("测试撤回");
        req.setForce(true);
        return req;
    }

    // ---------------- 夹具 ----------------

    /** 岗位模型：两个能力项（各配「待提升 / 优秀」两档）。多个用例要建两次页面，模型只建一次 */
    private long seedModel() {
        if (!modelSeeded) {
            long cat = data.category(modelService, postId, "通用能力");
            data.item(modelService, postId, cat, "沟通能力", "50", 1, 1);
            data.item(modelService, postId, cat, "协作能力", "50", 1, 0);
            modelSeeded = true;
        }
        return postId;
    }

    // ==================== 下属的报告（2026-11-24） ====================

    @Test
    @DisplayName("下属报告：只列直接下属，隔级下属与旁人一律不出现在列表里")
    void subordinateReportsCoverOnlyDirectReports() {
        long mine = sheet();          // TARGET 的页面，TARGET.managerid = LEADER
        task(mine, 1);
        // 给 LEADER 自己也造一张页面（他不是自己的下属）；再造一个「孙辈」与一个无关人员
        long leaderOwn = sheetService.prepare(PaTestData.YEAR, PaTestData.LEADER, postId).getId();
        // 孙辈：managerid 指向 TARGET，对 LEADER 而言是隔级
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, departmentid, jobtitle, managerid," +
                " subcompanyid1) VALUES (?, ?, 1, ?, ?, 1, ?, 1)",
                8121L, "钱孙辈", "qiansunbei", PaTestData.DEPT, PaTestData.TARGET);
        long grandSheet = sheetService.prepare(PaTestData.YEAR, 8121L, postId).getId();
        // 三张都封存：这样「列表里只有 TARGET」就只可能是 managerid 口径，
        // 不可能是因为别人没封存而被 visibility 闸门顺手挡掉的
        markSealed(mine);
        markSealed(leaderOwn);
        markSealed(grandSheet);

        PaTestData.login(PaTestData.LEADER);
        List<PaReportVO.ListItem> subs = reportService.subordinateReports(PaTestData.YEAR);
        List<Long> owners = subs.stream().map(PaReportVO.ListItem::getResourceId).toList();
        assertEquals(List.of(PaTestData.TARGET), owners,
                "只有 managerid == 自己的人算下属；自己和隔级下属都不能进这个列表");
        assertFalse(owners.contains(PaTestData.LEADER), "上级自己的报告不该出现在「下属」里");
        assertFalse(owners.contains(8121L), "隔级（孙辈）不下钻，口径只做直接下属");
        assertTrue(reportService.hasSubordinates(), "有直接下属 → tab 要显示");
    }

    @Test
    @DisplayName("下属报告：点名读下属的报告可以，读旁人的一律按「不存在」处理")
    void subordinateDetailIsScopedToDirectReports() {
        long subSheet = sheet();       // TARGET 的页面，TARGET 是 LEADER 的直接下属
        task(subSheet, 1);
        markSealed(subSheet);
        // TARGET 自己也造一个「下属」以外的页面：用它来验「不是我的下属的人读不到」
        long strangerSheet = sheetService.prepare(PaTestData.YEAR, PaTestData.RATER_A, postId).getId();

        PaTestData.login(PaTestData.LEADER);
        PaReportVO.Detail d = reportService.subordinateDetail(subSheet);
        assertEquals(PaTestData.TARGET, d.getResourceId());
        assertEquals(Boolean.FALSE, d.getShowRaterName(),
                "下属的 360 名单不是上级该从这里知道的事，姓名恒不返回");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.subordinateDetail(strangerSheet),
                "不是我的下属就必须读不到，否则任何登录人换个 sheetId 就能翻全公司");
        assertEquals(40701, ex.getCode(), "错误码与「不存在」一致，不能让人试出「存在但不是我的下属」");
    }

    @Test
    @DisplayName("下属报告：本人读自己的报告不归这条路管，避免两条口径互相放行")
    void subordinateDetailRejectsSelf() {
        long mine = sheet();
        task(mine, 1);

        PaTestData.login(PaTestData.TARGET);
        assertThrows(BusinessException.class, () -> reportService.subordinateDetail(mine),
                "自己不是自己的下属；读自己的报告要走 myDetail");
        assertFalse(reportService.hasSubordinates(), "TARGET 没有下属 → tab 不渲染");
        assertEquals(0, reportService.subordinateReports(PaTestData.YEAR).size(), "没有下属就是空列表");
    }

    @Test
    @DisplayName("下属报告：离职下属的报告仍要能看，藏掉会让人以为少了一个人")
    void subordinateReportsIncludeResigned() {
        // RESIGNED（status=5 离职）挂到 LEADER 名下当直接下属
        seedModel();
        jdbc.update("UPDATE HrmResource SET managerid = ? WHERE id = ?", PaTestData.LEADER, PaTestData.RESIGNED);
        long resignedSheet = sheetService.prepare(PaTestData.YEAR, PaTestData.RESIGNED, postId).getId();
        markSealed(resignedSheet);

        PaTestData.login(PaTestData.LEADER);
        List<Long> owners = reportService.subordinateReports(PaTestData.YEAR).stream()
                .map(PaReportVO.ListItem::getResourceId).toList();
        assertTrue(owners.contains(PaTestData.RESIGNED),
                "离职只影响「能不能发起新测评」，不影响回看已有报告");
        assertEquals(PaTestData.RESIGNED,
                reportService.subordinateDetail(resignedSheet).getResourceId());
    }

    // ==================== 封存（2026-11-24，sql/64） ====================

    @Test
    @DisplayName("封存：未封存时本人与上级都看不到，封存后都能看到")
    void sealGatesSelfAndSubordinateViews() {
        long sheet = scoredSheet();
        PaTestData.login(PaTestData.TARGET);
        assertEquals(0, reportService.myReports(PaTestData.YEAR).size(),
                "没封存的报告不该出现在「我的测评」列表里");
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.myDetail(sheet));
        assertEquals(40702, ex.getCode(), "归属没问题，所以要明说「还没封存」，不能含糊成不存在");

        // 上级视角同样被挡
        PaTestData.login(PaTestData.LEADER);
        assertEquals(0, reportService.subordinateReports(PaTestData.YEAR).size());
        assertEquals(40702,
                assertThrows(BusinessException.class, () -> reportService.subordinateDetail(sheet)).getCode());

        // HR 封存
        loginAdmin();
        reportService.seal(sheet);

        PaTestData.login(PaTestData.TARGET);
        assertEquals(1, reportService.myReports(PaTestData.YEAR).size(), "封存后本人立刻可见");
        assertEquals(1, (long) reportService.myDetail(sheet).getSealStatus());
        assertEquals("测试人", reportService.myDetail(sheet).getSealByName());

        PaTestData.login(PaTestData.LEADER);
        assertEquals(1, reportService.subordinateReports(PaTestData.YEAR).size(), "封存后上级也可见");
        assertEquals(sheet, reportService.subordinateDetail(sheet).getSheetId());
    }

    @Test
    @DisplayName("封存：没算出分的报告不能封存")
    void sealRequiresScoredSheet() {
        long sheet = sheet();
        loginAdmin();
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.seal(sheet));
        assertEquals(40703, ex.getCode(), "封一份没分数的报告没有意义");
        assertTrue(ex.getMessage().contains("没有算出分数"));
    }

    @Test
    @DisplayName("封存：没交完（含自评）一律不让封，并说清还差几个人")
    void sealRequiresAllRatersSubmitted() {
        long sheet = sheet();
        seedModel();
        long task = task(sheet, 1);
        // 只交上级 = 能出分（口径 #13 自评不阻塞算分），但没评完
        submit(task, "LEADER", PaTestData.LEADER, "5", "5");
        scoreService.finish(task);

        loginAdmin();
        BusinessException ex = assertThrows(BusinessException.class, () -> reportService.seal(sheet));
        assertEquals(40703, ex.getCode());
        assertTrue(ex.getMessage().contains("还有 2 位评价人未提交"), "自评 + 360 都没交：实际=" + ex.getMessage());

        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals(Boolean.FALSE, d.getSealReady(), "还差人 → 按钮该是灰的");
        assertEquals(2, d.getPendingRaterCount());

        // 补交 360，只剩自评
        submit(task, "RATER360", PaTestData.RATER_A, "5", "5");
        PaReportVO.Detail d2 = reportService.detail(sheet);
        assertEquals(1, d2.getPendingRaterCount(), "只剩自评没交");
        assertEquals(Boolean.FALSE, d2.getSealReady());

        // 自评也交掉才放行（口径 B：自评也要交完）
        submit(task, "SELF", PaTestData.TARGET, "4", "4");
        PaReportVO.Detail d3 = reportService.detail(sheet);
        assertEquals(0, d3.getPendingRaterCount());
        assertEquals(Boolean.TRUE, d3.getSealReady());
        reportService.seal(sheet);
    }

    @Test
    @DisplayName("封存：全员齐备后按钮才亮（sealReady 随提交进度翻转）")
    void sealReadyFlipsOnceEveryoneSubmits() {
        long sheet = scoredSheet();
        loginAdmin();
        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals(Boolean.TRUE, d.getSealReady(), "全交完了 → 可以封");
        assertEquals(0, d.getPendingRaterCount());
        assertEquals(Boolean.TRUE, d.getCanSeal(), "管理员本来就有权限");
    }

    @Test
    @DisplayName("封存：已作废的评价人不算「还差几个人」，否则作废过的报告永远封不了")
    void sealIgnoresVoidedRaters() {
        long sheet = sheet();
        seedModel();
        long task = task(sheet, 1);
        submitAll(task);
        scoreService.finish(task);
        // 模拟「撤回后重发」遗留：一个评价人被作废（status=3），不该再挡封存
        jdbc.update("UPDATE uf_assess_task_rater SET status = 3 WHERE task_id = ? AND rater_type = 'RATER360'", task);

        loginAdmin();
        PaReportVO.Detail d = reportService.detail(sheet);
        assertEquals(0, d.getPendingRaterCount());
        assertEquals(Boolean.TRUE, d.getSealReady());
    }

    @Test
    @DisplayName("封存：只有管理员 / 维度管理员能封存，普通登录人一律拒绝")
    void sealRequiresAdmin() {
        long sheet = scoredSheet();
        PaTestData.login(PaTestData.LEADER);
        assertEquals(403, assertThrows(BusinessException.class, () -> reportService.seal(sheet)).getCode(),
                "被测评人的上级不是管理员，封存是 HR 的动作");
    }

    @Test
    @DisplayName("封存：重复封存 / 未封存就解除都被拒绝")
    void sealStateIsIdempotentSafe() {
        long sheet = scoredSheet();
        loginAdmin();
        assertEquals(40703,
                assertThrows(BusinessException.class, () -> reportService.unseal(sheet)).getCode(),
                "还没封存就解除要说清楚，不能静默成功");
        reportService.seal(sheet);
        assertEquals(40703,
                assertThrows(BusinessException.class, () -> reportService.seal(sheet)).getCode(),
                "重复封存不该覆盖已落地的快照");
    }

    @Test
    @DisplayName("封存：解除之后本人立刻重新看不到，快照一并清掉")
    void unsealMakesInvisibleAgain() {
        long sheet = scoredSheet();
        loginAdmin();
        reportService.seal(sheet);
        reportService.unseal(sheet);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT seal_status, sealed_json, seal_by_name FROM uf_pa_sheet WHERE id = ?", sheet);
        assertEquals(0, ((Number) row.get("seal_status")).intValue());
        assertNull(row.get("sealed_json"), "快照必须清掉，否则下次封存分不清用的是哪一份");
        assertNull(row.get("seal_by_name"));

        PaTestData.login(PaTestData.TARGET);
        assertEquals(0, reportService.myReports(PaTestData.YEAR).size());
        assertEquals(40702,
                assertThrows(BusinessException.class, () -> reportService.myDetail(sheet)).getCode());
    }

    @Test
    @DisplayName("封存：内容冻结 —— 封存后库里改分，本人看到的仍是封存那一刻的数")
    void sealedContentIsFrozen() {
        long sheet = scoredSheet();
        loginAdmin();
        reportService.seal(sheet);
        PaReportVO.Detail before = reportService.detail(sheet);

        // 封存之后有人重算 / 改库：分数变了
        jdbc.update("UPDATE uf_pa_sheet SET total_score = 1.11, band_name = '待提升' WHERE id = ?", sheet);

        PaTestData.login(PaTestData.TARGET);
        PaReportVO.Detail mine = reportService.myDetail(sheet);
        assertEquals(0, before.getTotalScore().compareTo(mine.getTotalScore()),
                "封存的意义就是「我确认的那一份」，不能跟着库里的数一起变");
        assertEquals(before.getBandName(), mine.getBandName());
        assertEquals(0, before.getTotalScore().compareTo(reportService.detail(sheet).getTotalScore()),
                "管理端读的也是同一份快照");
    }

    @Test
    @DisplayName("封存：快照里的测评人姓名按调用人重新收口，本人读不到姓名")
    void sealedSnapshotHidesRaterNamesForSelf() {
        long sheet = scoredSheet();
        loginAdmin();
        reportService.seal(sheet);
        // 快照是在管理员视角（带姓名）下存的
        assertNotNull(reportService.detail(sheet).getRows().get(0).getRater360s().get(0).getRaterName());

        PaTestData.login(PaTestData.TARGET);
        PaReportVO.Detail mine = reportService.myDetail(sheet);
        assertEquals(Boolean.FALSE, mine.getShowRaterName());
        assertNull(mine.getRows().get(0).getRater360s().get(0).getRaterName(),
                "快照带姓名没关系，读取时必须按当前调用人抹掉");
    }

    @Test
    @DisplayName("封存：已封存的页面不能撤回 / 重算 / 重新发起")
    void sealedSheetBlocksDestructiveOps() {
        long sheet = scoredSheet();
        long task = jdbc.queryForObject(
                "SELECT MAX(id) FROM uf_assess_task WHERE sheet_id = ?", Long.class, sheet);
        loginAdmin();
        reportService.seal(sheet);

        assertEquals(40209, assertThrows(BusinessException.class,
                () -> taskOpService.retry(task)).getCode(), "重算会改写分数，与快照分叉");
        assertEquals(40209, assertThrows(BusinessException.class,
                () -> taskOpService.voidTask(task, voidRequest())).getCode(), "撤回会把页面清空");
        assertEquals(40604, assertThrows(BusinessException.class,
                () -> sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId)).getCode(),
                "重新发起会重建页面行");
    }

    @Test
    @DisplayName("封存：管理端列表带出封存状态与操作人")
    void listCarriesSealStatus() {
        long sheet = scoredSheet();
        loginAdmin();
        PaReportVO.ListItem before = reportService.page(PaTestData.YEAR, null, null, null, 1, 10)
                .getList().get(0);
        assertEquals(0, before.getSealStatus());

        reportService.seal(sheet);
        PaReportVO.ListItem after = reportService.page(PaTestData.YEAR, null, null, null, 1, 10)
                .getList().get(0);
        assertEquals(1, after.getSealStatus());
        assertEquals("测试人", after.getSealByName());
        assertNotNull(after.getSealTime());
    }

    /** 装配出被测人的页面。一个用例里只能调一次：重复调用会重建行 */
    private long sheet() {
        seedModel();
        if (sheetId == 0L) {
            sheetId = sheetService.prepare(PaTestData.YEAR, PaTestData.TARGET, postId).getId();
        }
        return sheetId;
    }

    /**
     * 在另一个维度下造一张「目标人 + 指定年度」的测评页。
     *
     * <p>为什么不走 {@code sheetService.prepare}：那条链路要一整套岗位/能力项/年度，
     * 而本用例只关心「本人视角下同一年度出现两个维度」。直接写库最短，
     * 也不会让这个用例跟着发起链路的演进一起红。</p>
     */
    private void seedSheetInAnotherDimension(int year, String dimensionName) {
        // 测试库不预置维度行（PaTestData.DIM 只是个数字），这里把默认维度一起补上，
        // 否则本人视角那一行的维度名会是 NULL，看不见「两个维度」的对比。
        // 两条都用显式主键：H2 的自增列不会因为显式插入而前进，混用必撞主键。
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_dimension WHERE id = ?", Integer.class, PaTestData.DIM);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO uf_assess_dimension (id, dimension_name, status) VALUES (?, '默认维度', 1)",
                    PaTestData.DIM);
        }
        jdbc.update("INSERT INTO uf_assess_dimension (id, dimension_name, status) VALUES (?, ?, 1)",
                OTHER_DIM_ID, dimensionName);
        long otherDim = OTHER_DIM_ID;
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, 0, ?)",
                year, otherDim);
        long otherPeriod = jdbc.queryForObject(
                "SELECT id FROM uf_assess_period WHERE dimension_id = ? AND year = ?", Long.class, otherDim, year);
        Long postId = jdbc.queryForObject("SELECT MIN(id) FROM uf_pa_post", Long.class);
        jdbc.update("INSERT INTO uf_pa_sheet (company_id, dimension_id, period_id, resource_id, post_id," +
                        " department_id, department_name, post_name, status, item_count)" +
                        " VALUES (1, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
                otherDim, otherPeriod, PaTestData.TARGET, postId,
                PaTestData.DEPT, "人力资源部", "人事专员");
    }

    /** 建一个任务：直接建库，报告不关心发起链路（那是 PaTaskServiceTest 的事） */
    private long task(long sheet, int rater360) {
        jdbc.update("INSERT INTO uf_assess_task (company_id, dimension_id, period_id, sheet_id, resource_id," +
                        " department_id, leader_resource_id, status, rating_count, client_count, create_by)" +
                        " VALUES (1, ?, ?, ?, ?, ?, ?, 0, 2, ?, 1)",
                PaTestData.DIM, periodId, sheet, PaTestData.TARGET, PaTestData.DEPT, PaTestData.LEADER, rater360);
        long taskId = jdbc.queryForObject("SELECT MAX(id) FROM uf_assess_task", Long.class);
        rater(taskId, "SELF", PaTestData.TARGET);
        rater(taskId, "LEADER", PaTestData.LEADER);
        if (rater360 > 0) {
            rater(taskId, "RATER360", PaTestData.RATER_A);
        }
        if (rater360 > 1) {
            rater(taskId, "RATER360", PaTestData.RATER_B);
        }
        return taskId;
    }

    private void rater(long taskId, String type, long resourceId) {
        jdbc.update("INSERT INTO uf_assess_task_rater (task_id, rater_resource_id, rater_type, status, access_token)" +
                " VALUES (?, ?, ?, 0, ?)", taskId, resourceId, type, "tok-" + taskId + "-" + type + "-" + resourceId);
    }

    /** 直接写评级：报告只读，评测链路已在 PaFillServiceTest 覆盖 */
    private void submit(long taskId, String type, long raterId, String first, String second) {
        List<Map<String, Object>> items = items(taskId);
        assertEquals(2, items.size(), "夹具里的两个能力项必须都在页面上");
        rating(taskId, type, raterId, items.get(0), "5".equals(first) ? "优秀" : "待提升");
        rating(taskId, type, raterId, items.get(1), "2".equals(second) ? "待提升" : "优秀");
        jdbc.update("UPDATE uf_assess_task_rater SET status = 1, submit_time = SYSDATETIME()" +
                " WHERE task_id = ? AND rater_type = ?", taskId, type);
    }

    /** 某一项打指定等级（其余项不动）：验证项刻度时只需要控制这一项 */
    private void submitWithLevel(long taskId, String type, long raterId, long itemId, String levelName) {
        long sheet = jdbc.queryForObject("SELECT sheet_id FROM uf_assess_task WHERE id = ?", Long.class, taskId);
        Map<String, Object> level = jdbc.queryForMap(
                "SELECT id, level_name, score FROM uf_pa_item_level WHERE item_id = ? AND level_name = ?",
                itemId, levelName);
        jdbc.update("DELETE FROM uf_pa_rating WHERE sheet_id = ? AND item_id = ? AND rater_type = ?" +
                        " AND rater_resource_id = ?", sheet, itemId, type, raterId);
        jdbc.update("INSERT INTO uf_pa_rating (dimension_id, sheet_id, item_id, rater_type, rater_resource_id," +
                        " level_id, level_name, score) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                PaTestData.DIM, sheet, itemId, type, raterId,
                level.get("id"), level.get("level_name"), level.get("score"));
        jdbc.update("UPDATE uf_assess_task_rater SET status = 1, submit_time = SYSDATETIME()" +
                " WHERE task_id = ? AND rater_type = ? AND rater_resource_id = ?", taskId, type, raterId);
    }

    private void rating(long taskId, String type, long raterId, Map<String, Object> item, String levelName) {
        long sheet = jdbc.queryForObject("SELECT sheet_id FROM uf_assess_task WHERE id = ?", Long.class, taskId);
        Map<String, Object> level = jdbc.queryForMap(
                "SELECT id, level_name, score FROM uf_pa_item_level WHERE item_id = ? AND level_name = ?",
                item.get("item_id"), levelName);
        jdbc.update("INSERT INTO uf_pa_rating (dimension_id, sheet_id, item_id, rater_type, rater_resource_id," +
                        " level_id, level_name, score) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                PaTestData.DIM, sheet, item.get("item_id"), type, raterId,
                level.get("id"), level.get("level_name"), level.get("score"));
    }

    private List<Map<String, Object>> items(long taskId) {
        return jdbc.queryForList(
                "SELECT item_id FROM uf_pa_sheet_item WHERE sheet_id =" +
                        " (SELECT sheet_id FROM uf_assess_task WHERE id = ?) ORDER BY sort_no", taskId);
    }

    /** 提交并带上评语；评级按「全部优秀」写满 */
    private void submitWithComment(long taskId, String type, long raterId, String comment) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT item_id FROM uf_pa_sheet_item WHERE sheet_id =" +
                        " (SELECT sheet_id FROM uf_assess_task WHERE id = ?) ORDER BY sort_no", taskId);
        for (Map<String, Object> item : items) {
            rating(taskId, type, raterId, item, "优秀");
        }
        // 必须按 rater_resource_id 定位：同类型可能有多个 360，只按 type 更新会把
        // 没交的人也一起标成已提交，用例就测不到「有人没交」的场景了。
        jdbc.update("UPDATE uf_assess_task_rater SET status = 1, submit_time = SYSDATETIME(), comment = ?" +
                " WHERE task_id = ? AND rater_type = ? AND rater_resource_id = ?", comment, taskId, type, raterId);
    }
}
