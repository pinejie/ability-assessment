package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessFillDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.AssessTaskVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价填写服务单测：JdbcTemplate 全 mock（存储过程不在 H2 跑），按 SQL 特征分发假数据。
 *
 * 重点覆盖方案里最容易被写错的三处：
 * ① 撤回/取消的状态页不能泄漏任何要素或姓名；
 * ② 要素集合必须「不多不少」，少一个即拒绝（否则 recalc 计数不成立、分数永不出）；
 * ③ 单人提交 @skip_recalc=1 + @source='WEB'，只有 pending=0 才调 finish。
 *
 * 要素/等级走真实 RowMapper（用 mock 的 ResultSet 喂行），这样客户遮蔽等级的
 * 服务端行为才真的被测到，而不是被测试桩代劳。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessFillServiceTest {

    private static final long TASK_ID = 88L;
    private static final long RATER_ID = 1201L;
    private static final long RATER_RESOURCE = 2504L;
    private static final long TARGET_RESOURCE = 2505L;
    private static final long SHEET_ID = 14L;
    private static final long DIM = 3L;
    private static final long COMPANY = 1L;
    private static final String TOKEN = "tok-abc";

    @Mock
    private JdbcTemplate jdbc;

    private AssessFillService service;

    @BeforeEach
    void setUp() throws SQLException {
        service = new AssessFillService(jdbc, new ObjectMapper(), COMPANY);
        DimensionContext.set(DIM);
        loginAs(RATER_RESOURCE);
        stubRaterContext(0, 0);   // 任务进行中、评价人待评
        stubElements();
        stubLevels();
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== page ====================

    @Test
    @DisplayName("page：进行中任务返回要素、档位、草稿与整体进度")
    void pageReturnsFillData() throws SQLException {
        stubLevels(5, 3);
        stubProgress(4, 1);

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getStatus()).isEqualTo("FILLING");
        assertThat(page.getTargetName()).isEqualTo("张晓明");
        assertThat(page.getRaterTypeName()).isEqualTo("直接上级");
        assertThat(page.getElements()).hasSize(2);
        assertThat(page.getElements().get(0).getElementName()).isEqualTo("沟通能力");
        // 档位跟着要素走：59 配了 5 档、60 只配 3 档
        assertThat(page.getElements().get(0).getLevels()).hasSize(5);
        assertThat(page.getElements().get(1).getLevels()).hasSize(3);
        assertThat(page.getElements().get(0).getLevels().get(0).getRequirement())
                .isEqualTo("沟通能力 1 级行为要求");
        assertThat(page.getDraft()).containsEntry(59L, 3);
        assertThat(page.getDraftVersion()).isEqualTo(2);
        assertThat(page.getSubmittedCount()).isEqualTo(1);
        assertThat(page.getRaterTotal()).isEqualTo(4);
        assertThat(page.getMasked()).isFalse();
    }

    @Test
    @DisplayName("page：任务已撤回只返回状态与说明，不含姓名/要素/草稿（不泄漏信息）")
    void pageVoidedHidesEverything() {
        stubRaterContext(4, 1);   // 任务已撤回

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getStatus()).isEqualTo("VOIDED");
        assertThat(page.getMessage()).contains("撤回");
        assertThat(page.getElements()).isNull();
        assertThat(page.getTargetName()).isNull();
        assertThat(page.getDraft()).isNull();
    }

    @Test
    @DisplayName("page：任务已取消返回 CANCELLED 且不含要素")
    void pageCancelled() {
        stubRaterContext(3, 0);

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getStatus()).isEqualTo("CANCELLED");
        assertThat(page.getMessage()).contains("取消");
        assertThat(page.getElements()).isNull();
    }

    @Test
    @DisplayName("page：token 查不到直接拒绝")
    void pageUnknownToken() {
        when(jdbc.queryForList(contains("WHERE tr.access_token = ?"), eq("bad-token")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.page("bad-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("链接无效");
    }

    @Test
    @DisplayName("page：登录人不是该评价人时拒绝（防代填）")
    void pageRejectsWrongUser() {
        loginAs(9999L);

        assertThatThrownBy(() -> service.page(TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前登录账号");
    }

    @Test
    @DisplayName("page：客户视角遮蔽要求等级（服务端不返回，而非前端隐藏）")
    void pageMasksForClient() {
        stubRaterContext(0, 0, "CLIENT");
        stubProgress(4, 0);

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getMasked()).isTrue();
        assertThat(page.getElements()).isNotEmpty();
        assertThat(page.getElements()).allMatch(e -> e.getRequiredLevelNo() == null);
    }

    // ==================== saveDraft ====================

    @Test
    @DisplayName("saveDraft：版本一致时合并写入并递增版本")
    void saveDraftMerges() {
        AssessTaskVO.DraftResult r = service.saveDraft(TOKEN, draft(2, 60, 4));

        assertThat(r.getConflict()).isFalse();
        assertThat(r.getDraftVersion()).isEqualTo(3);
        assertThat(r.getRatedCount()).isEqualTo(2);   // 原有 59:3 保留 + 新增 60:4
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        // 不带评语的暂存走另一条 SQL（不碰 comment 列），见 saveDraftKeepsCommentWhenAbsent
        verify(jdbc).update(contains("UPDATE uf_assess_task_rater SET draft_json"), json.capture(),
                eq(3), eq(RATER_ID));
        assertThat(json.getValue()).contains("\"59\":3").contains("\"60\":4");
    }

    @Test
    @DisplayName("saveDraft：带评语时一并写入（2026-11-17）")
    void saveDraftWritesComment() {
        AssessTaskVO.DraftResult r = service.saveDraft(TOKEN,
                draftWithComment(2, "  踏实肯干  ", 60, 4));

        assertThat(r.getConflict()).isFalse();
        ArgumentCaptor<Object> comment = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("comment = ? WHERE id = ?"), anyString(), eq(3),
                comment.capture(), eq(RATER_ID));
        assertThat(comment.getValue()).isEqualTo("踏实肯干");   // 前后空白要 trim 掉
    }

    @Test
    @DisplayName("saveDraft：不带评语时不清空已存评语")
    void saveDraftKeepsCommentWhenAbsent() {
        service.saveDraft(TOKEN, draft(2, 60, 4));

        verify(jdbc).update(contains("UPDATE uf_assess_task_rater SET draft_json"), anyString(),
                eq(3), eq(RATER_ID));
        verify(jdbc, never()).update(contains("comment = ?"), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("saveDraft：版本不一致返回冲突与库内最新草稿，不覆盖")
    void saveDraftRejectsStaleVersion() {
        AssessTaskVO.DraftResult r = service.saveDraft(TOKEN, draft(1, 60, 4));

        assertThat(r.getConflict()).isTrue();
        assertThat(r.getDraftVersion()).isEqualTo(2);
        assertThat(r.getLatestDraft()).containsEntry(59L, 3);
        verify(jdbc, never()).update(contains("UPDATE uf_assess_task_rater SET draft_json"),
                anyString(), any(Integer.class), any(Long.class));
    }

    @Test
    @DisplayName("saveDraft：任务已撤回拒绝暂存")
    void saveDraftRejectsVoidedTask() {
        stubRaterContext(4, 1);

        assertThatThrownBy(() -> service.saveDraft(TOKEN, draft(2, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("撤回");
    }

    @Test
    @DisplayName("saveDraft：本人已提交后拒绝暂存（单项提交，草稿即最终答案）")
    void saveDraftRejectsSubmittedRater() {
        stubRaterContext(0, 1);   // 任务进行中、本人已提交

        assertThatThrownBy(() -> service.saveDraft(TOKEN, draft(2, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已提交");
        // 不得写入草稿
        verify(jdbc, never()).update(contains("UPDATE uf_assess_task_rater SET draft_json"), any(Object[].class));
    }

    // ==================== submit ====================

    @Test
    @DisplayName("submit：自评不写评语，即使前端传了也忽略（2026-11-17）")
    void submitSelfIgnoresComment() {
        stubRaterContext(0, 0, "SELF");
        stubPending(2);
        stubProgress(4, 2);

        service.submit(TOKEN, submitWithComment("自评也写了", 59, 3, 60, 4));

        ArgumentCaptor<Object> comment = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("comment = ? WHERE id = ?"), any(), anyString(),
                comment.capture(), eq(RATER_ID));
        assertThat(comment.getValue()).isNull();
    }

    @Test
    @DisplayName("submit：上级未写评语即拒绝，且不写评级（2026-11-17）")
    void submitRejectsBlankComment() {
        stubPending(2);
        stubProgress(4, 2);

        assertThatThrownBy(() -> service.submit(TOKEN, submitWithComment("   ", 59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评价意见");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：客户未写评语同样拒绝（2026-11-17）")
    void submitClientRejectsBlankComment() {
        stubRaterContext(0, 0, "CLIENT");
        stubPending(2);
        stubProgress(4, 2);

        assertThatThrownBy(() -> service.submit(TOKEN, submitWithComment(null, 59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评价意见");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：评语超过 1000 字拒绝")
    void submitRejectsTooLongComment() {
        stubPending(2);
        stubProgress(4, 2);

        assertThatThrownBy(() -> service.submit(TOKEN,
                submitWithComment("长".repeat(1001), 59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1000");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("page：上级返回已存评语且要求必填，自评不要求")
    void pageCarriesCommentFlag() {
        stubRaterContext(0, 0, "LEADER");
        AssessTaskVO.FillPage leader = service.page(TOKEN);
        assertThat(leader.getCommentRequired()).isTrue();
        assertThat(leader.getComment()).isEqualTo("  已写过的评语  ");   // 原样回显，不在读时裁

        stubRaterContext(0, 0, "SELF");
        AssessTaskVO.FillPage self = service.page(TOKEN);
        assertThat(self.getCommentRequired()).isFalse();
    }

    @Test
    @DisplayName("submit：单人提交只写评级不调 finish，@skip_recalc=1 且 @source=WEB")
    void submitSingleWritesRatingOnly() {
        stubPending(2);
        stubProgress(4, 2);

        AssessTaskVO.SubmitResult r = service.submit(TOKEN, submit(59, 3, 60, 4));

        assertThat(r.getSubmitted()).isTrue();
        assertThat(r.getCalculated()).isFalse();
        assertThat(r.getMessage()).contains("还有 2 位评价人未提交");
        assertThat(r.getTaskProgress().getSubmitted()).isEqualTo(2);
        assertThat(r.getTaskProgress().getTotal()).isEqualTo(4);

        Object[] a = capturedRateArgs();
        assertThat(a).hasSize(10);
        assertThat(a[5]).isEqualTo(TASK_ID);        // 来源单据 ID = task_id
        assertThat(a[7]).isNull();                  // 领导/自评的 client_ids_json 必须为 null，不能是 []
        assertThat(a[8]).isEqualTo(1);              // skip_recalc
        assertThat(a[9]).isEqualTo("WEB");          // source
        verify(jdbc, never()).queryForList(contains("{call usp_assess_task_finish"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：全员齐备时调用 usp_assess_task_finish 并回报已算分")
    void submitLastRaterTriggersFinish() {
        stubPending(0);
        stubProgress(4, 4);

        AssessTaskVO.SubmitResult r = service.submit(TOKEN, submit(59, 3, 60, 4));

        assertThat(r.getCalculated()).isTrue();
        assertThat(r.getMessage()).contains("已计算得分");
        verify(jdbc).queryForList(contains("{call usp_assess_task_finish"), eq(TASK_ID));
    }

    @Test
    @DisplayName("submit：要素少一个即拒绝，且不写评级")
    void submitRejectsMissingElement() {
        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评价不完整");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：多出不属于该角色的要素也拒绝")
    void submitRejectsExtraElement() {
        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3, 60, 4, 999, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多出");
    }

    @Test
    @DisplayName("submit：评级不是该要素已配置的档位，拒绝")
    void submitRejectsInvalidLevel() {
        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3, 60, 9)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是该要素已配置的档位");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：要素只配 3 档，提交 4 档虽在维度配置内也必须拒绝")
    void submitRejectsLevelBeyondElementConfig() throws SQLException {
        stubLevels(5, 3);
        // 60 号要素只配了 1–3 档，4 档在维度配置里存在但对该要素不成立
        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是该要素已配置的档位");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
    }

    @Test
    @DisplayName("submit：任务已完成拒绝再改")
    void submitRejectsFinishedTask() {
        stubRaterContext(2, 1);

        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已完成");
    }

    @Test
    @DisplayName("submit：本人已提交后拒绝再次提交，且不写评级（单项提交，2026-11-16）")
    void submitRejectsAlreadySubmittedRater() {
        stubRaterContext(0, 1);   // 任务进行中、本人已提交

        assertThatThrownBy(() -> service.submit(TOKEN, submit(59, 3, 60, 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已提交");
        // 关键：不覆盖既有评级，也不调算分
        verify(jdbc, never()).queryForList(contains("{call usp_assess_rate"), any(Object[].class));
        verify(jdbc, never()).queryForList(contains("{call usp_assess_task_finish"), any(Object[].class));
    }

    @Test
    @DisplayName("page：本人已提交仍返回要素与评级，但状态为 SUBMITTED（只读查看，不泄漏写入口）")
    void pageSubmittedIsReadOnlyView() {
        stubRaterContext(0, 1);   // 任务进行中、本人已提交
        stubProgress(4, 2);

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getStatus()).isEqualTo("SUBMITTED");
        assertThat(page.getMessage()).contains("锁定");
        assertThat(page.getElements()).isNotEmpty();   // 仍可查看自己打过的分
        assertThat(page.getDraft()).isNotEmpty();
    }

    @Test
    @DisplayName("page：任务已完成时文案说明整单已算分并锁定")
    void pageDoneSaysLocked() {
        stubRaterContext(2, 1);
        stubProgress(4, 4);

        AssessTaskVO.FillPage page = service.page(TOKEN);

        assertThat(page.getStatus()).isEqualTo("SUBMITTED");
        assertThat(page.getMessage()).contains("算分").contains("锁定");
    }

    @Test
    @DisplayName("submit：客户提交时 @client_ids_json 传全量客户名单")
    void submitClientCarriesClientIds() {
        stubRaterContext(0, 0, "CLIENT");
        stubPending(1);
        stubProgress(3, 2);
        when(jdbc.queryForList(contains("SELECT rater_resource_id FROM uf_assess_task_rater"),
                eq(Long.class), eq(TASK_ID))).thenReturn(List.of(3785L, 2381L));

        service.submit(TOKEN, submit(59, 3, 60, 4));

        assertThat(capturedRateArgs()[7]).isEqualTo("[3785,2381]");
    }

    // ==================== 辅助 ====================

    private Object[] capturedRateArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("{call usp_assess_rate"), args.capture());
        return args.getValue();
    }

    private void loginAs(long resourceId) {
        LoginUser user = new LoginUser("u" + resourceId, resourceId, "测试人", 57L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void stubRaterContext(int taskStatus, int raterStatus) {
        stubRaterContext(taskStatus, raterStatus, "LEADER");
    }

    private void stubRaterContext(int taskStatus, int raterStatus, String raterType) {
        Map<String, Object> row = row(
                "rater_id", RATER_ID, "task_id", TASK_ID, "rater_type", raterType,
                "rater_resource_id", RATER_RESOURCE,
                "rater_status", raterStatus, "draft_json", "{\"59\":3}", "draft_version", 2,
                "comment", "  已写过的评语  ",
                "task_status", taskStatus, "sheet_id", SHEET_ID, "resource_id", TARGET_RESOURCE,
                "dimension_id", DIM, "year", 2026, "dimension_name", "管理能力",
                "target_name", "张晓明", "dept_name", "生产部");
        when(jdbc.queryForList(contains("WHERE tr.access_token = ?"), eq(TOKEN)))
                .thenReturn(List.of(row));
    }

    /** 两个要素，走真实 RowMapper：把「客户视角不返回要求等级」也一并校验到 */
    private void stubElements() throws SQLException {
        List<Map<String, Object>> rows = List.of(
                row("element_id", 59L, "element_name", "沟通能力", "description", "能清晰表达观点",
                        "category_id", 7L, "category_name", "通用能力", "required_level_no", 3),
                row("element_id", 60L, "element_name", "执行能力", "description", "能推动事项落地",
                        "category_id", 7L, "category_name", "通用能力", "required_level_no", 4));
        when(jdbc.query(sqlContaining("FROM uf_assess_sheet_row r JOIN uf_ability_element e"),
                ArgumentMatchers.<RowMapper<AssessTaskVO.ElementItem>>any(), any(Object[].class)))
                .thenAnswer(inv -> mapRows(rows, inv.getArgument(1)));
    }

    private void stubLevels() throws SQLException {
        stubLevels(5, 5);
    }

    /**
     * 档位按要素配置：59 号给 five1 档、60 号给 five2 档。
     * 两个数字不同即可模拟「某要素配置的档位比维度少」（§6.3、§6.5.1）。
     */
    private void stubLevels(int levelsOf59, int levelsOf60) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int level = 1; level <= levelsOf59; level++) {
            rows.add(row("element_id", 59L, "level", level, "level_name", level + "级",
                    "level_requirement", "沟通能力 " + level + " 级行为要求",
                    "rate_value", level, "level_desc", level + "级"));
        }
        for (int level = 1; level <= levelsOf60; level++) {
            rows.add(row("element_id", 60L, "level", level, "level_name", level + "级",
                    "level_requirement", "执行能力 " + level + " 级行为要求",
                    "rate_value", level, "level_desc", level + "级"));
        }
        when(jdbc.query(sqlContaining("FROM uf_ability_element_level l"),
                ArgumentMatchers.<org.springframework.jdbc.core.ResultSetExtractor<Map<Long, List<AssessTaskVO.LevelItem>>>>any(),
                any(Object[].class)))
                .thenAnswer(inv -> extractLevels(rows, inv.getArgument(1)));
    }

    /** 模拟 ResultSetExtractor 的契约：由 mapper 自己遍历 ResultSet 并返回结果 */
    @SuppressWarnings("unchecked")
    private static Map<Long, List<AssessTaskVO.LevelItem>> extractLevels(
            List<Map<String, Object>> rows, Object extractorArg) throws SQLException {
        org.springframework.jdbc.core.ResultSetExtractor<Map<Long, List<AssessTaskVO.LevelItem>>> ex =
                (org.springframework.jdbc.core.ResultSetExtractor<Map<Long, List<AssessTaskVO.LevelItem>>>) extractorArg;
        return ex.extractData(mockRs(rows));
    }

    private void stubProgress(int total, int submitted) {
        when(jdbc.queryForMap(contains("SUM(CASE WHEN status ="), eq(1), eq(TASK_ID)))
                .thenReturn(row("total", total, "submitted", submitted));
    }

    private void stubPending(int pending) {
        when(jdbc.queryForObject(
                contains("COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = ?"),
                eq(Integer.class), eq(TASK_ID), eq(0))).thenReturn(pending);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> mapRows(List<Map<String, Object>> rows, Object mapperArg) throws SQLException {
        RowMapper<T> mapper = (RowMapper<T>) mapperArg;
        ResultSet rs = mockRs(rows);
        List<T> out = new ArrayList<>();
        int i = 0;
        while (rs.next()) {
            out.add(mapper.mapRow(rs, i++));
        }
        return out;
    }

    /** 用 Map 行模拟一个只支持按列名取值的 ResultSet */
    private static ResultSet mockRs(List<Map<String, Object>> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(inv -> cursor.incrementAndGet() < rows.size());
        when(rs.getLong(anyString())).thenAnswer(inv -> {
            Object v = rows.get(cursor.get()).get(inv.getArgument(0, String.class));
            return v == null ? 0L : ((Number) v).longValue();
        });
        when(rs.getString(anyString())).thenAnswer(inv ->
                (String) rows.get(cursor.get()).get(inv.getArgument(0, String.class)));
        when(rs.getObject(anyString())).thenAnswer(inv ->
                rows.get(cursor.get()).get(inv.getArgument(0, String.class)));
        return rs;
    }

    /**
     * 按 elementId, levelNo 成对传入。
     * 带默认评语 —— 上级/客户提交时评语必填（2026-11-17），不带会被服务层拦下。
     */
    private static AssessFillDTO.SubmitRequest submit(Object... pairs) {
        return submitWithComment("工作扎实，沟通到位", pairs);
    }

    private static AssessFillDTO.SubmitRequest submitWithComment(String comment, Object... pairs) {
        List<AssessFillDTO.Rating> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            AssessFillDTO.Rating r = new AssessFillDTO.Rating();
            r.setElementId(((Number) pairs[i]).longValue());
            r.setLevelNo(((Number) pairs[i + 1]).intValue());
            list.add(r);
        }
        AssessFillDTO.SubmitRequest req = new AssessFillDTO.SubmitRequest();
        req.setRatings(list);
        req.setComment(comment);
        return req;
    }

    private static AssessFillDTO.DraftRequest draft(int version, long elementId, int levelNo) {
        return draftWithComment(version, null, elementId, levelNo);
    }

    private static AssessFillDTO.DraftRequest draftWithComment(int version, String comment, long elementId, int levelNo) {
        AssessFillDTO.DraftRequest req = new AssessFillDTO.DraftRequest();
        req.setVersion(version);
        req.setRatings(submit(elementId, levelNo).getRatings());
        req.setComment(comment);
        return req;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** jdbc.query(String, RowMapper, Object...) 的 SQL 参数匹配器 */
    private static String sqlContaining(String fragment) {
        return ArgumentMatchers.argThat(s -> s != null && s.contains(fragment));
    }
}
