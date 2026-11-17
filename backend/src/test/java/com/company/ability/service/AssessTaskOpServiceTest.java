package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessTaskOpDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.AssessTaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务运营单测：撤回 / 取消 / 催办 / 重算。
 *
 * 重点覆盖 §4.3、§7.1.2 里那些「静默做错比报错更糟」的判定：
 * ① 有 FLOW/EXTERNAL 评级时撤回必须拒绝（否则撤回后重算又出分）；
 * ② 已完成任务不传 force 必须拒绝；
 * ③ 已撤回/已取消的重算必须拒绝（闸门会放行，状态校验必须在闸门之前）；
 * ④ 通知失败不回滚撤回，但必须回传 noticeWritten=false。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessTaskOpServiceTest {

    private static final long DIM = 3L;
    private static final long TASK_ID = 88L;
    private static final long SHEET_ID = 14L;
    private static final long PERIOD_ID = 13L;
    private static final long TARGET = 2505L;
    private static final long OPERATOR = 900L;
    private static final int YEAR = 2026;

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private AssessPushService pushService;

    private AssessTaskOpService service;

    @BeforeEach
    void setUp() {
        service = new AssessTaskOpService(jdbc, pushService);
        DimensionContext.set(DIM);
        LoginUser user = new LoginUser("tester", OPERATOR, "测试员", 10L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        stubTask(0);
        stubTaskHeadRow();
        stubPeriod(0);
        stubNoForeignData();
        stubSheetAfter();
        when(pushService.submittedRaters(TASK_ID)).thenReturn(List.of(
                row("rater_resource_id", 2144L, "access_token", "tok-leader")));
        when(pushService.writeVoids(anyLong(), anyString(), any(Integer.class), anyString(), any()))
                .thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== 撤回 ====================

    @Test
    @DisplayName("voidTask：正常撤回，带回现场数据并写通知")
    void voidSucceeds() {
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 1,
                "voidedRatingCount", 180, "voidedRaterCount", 4,
                "submittedRaterCount", 2, "snapshotDeleted", 1, "alreadyVoided", 0));

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强"));

        assertThat(r.getFullRollback()).isTrue();
        assertThat(r.getVoidedRatingCount()).isEqualTo(180);
        assertThat(r.getSubmittedRaterCount()).isEqualTo(2);
        assertThat(r.getSnapshotDeleted()).isTrue();
        assertThat(r.getAlreadyVoided()).isFalse();
        assertThat(r.getNoticeWritten()).isTrue();
        assertThat(r.getNotifiedRaterCount()).isEqualTo(1);

        // 过程入参：taskId, operatorId, reason
        ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("{call usp_assess_void"), cap.capture());
        assertThat(cap.getValue()[0]).isEqualTo(TASK_ID);
        assertThat(cap.getValue()[1]).isEqualTo(OPERATOR);
        assertThat(cap.getValue()[2]).isEqualTo("客户评价人漏选财务部王强");
    }

    @Test
    @DisplayName("voidTask：存在 FLOW/EXTERNAL 评级时拒绝，不调撤回过程")
    void voidRejectsForeignRatings() {
        when(jdbc.queryForObject(contains("AND source <> 'WEB'"), eq(Integer.class), eq(SHEET_ID)))
                .thenReturn(21);

        assertThatThrownBy(() -> service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("流程评级 / 外部导入评级");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_void"), any(Object[].class));
    }

    @Test
    @DisplayName("voidTask：存在评价意见时也放行，并回传被删条数（2026-11-17 起撤回不再被评语拦住）")
    void voidAllowsComments() {
        stubTask(0);
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 1,
                "voidedRatingCount", 180, "voidedRaterCount", 3, "voidedCommentCount", 2,
                "submittedRaterCount", 2, "snapshotDeleted", 1, "alreadyVoided", 0));

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强"));

        assertThat(r.getVoidedCommentCount()).isEqualTo(2);
        verify(jdbc).queryForList(contains("{call usp_assess_void"), any(Object[].class));
    }

    @Test
    @DisplayName("voidTask：已完成任务未传 force 时拒绝")
    void voidNeedsForceWhenDone() {
        stubTask(2);
        AssessTaskOpDTO.VoidRequest req = voidReq("客户评价人漏选财务部王强");
        req.setForce(false);

        assertThatThrownBy(() -> service.voidTask(TASK_ID, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("force=true");
    }

    @Test
    @DisplayName("voidTask：已完成 + force=true 放行")
    void voidWithForceSucceeds() {
        stubTask(2);
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 1,
                "voidedRatingCount", 10, "voidedRaterCount", 3,
                "submittedRaterCount", 3, "snapshotDeleted", 1, "alreadyVoided", 0));
        AssessTaskOpDTO.VoidRequest req = voidReq("客户评价人漏选财务部王强");
        req.setForce(true);

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, req);

        assertThat(r.getFullRollback()).isTrue();
    }

    @Test
    @DisplayName("voidTask：年度已关闭时拒绝（数据已冻结）")
    void voidRejectsClosedPeriod() {
        stubPeriod(1);

        assertThatThrownBy(() -> service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("年度已关闭");
    }

    @Test
    @DisplayName("voidTask：通知失败不回滚撤回，但必须回传 noticeWritten=false")
    void voidNoticeFailureIsReported() {
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 1,
                "voidedRatingCount", 10, "voidedRaterCount", 3,
                "submittedRaterCount", 1, "snapshotDeleted", 1, "alreadyVoided", 0));
        when(pushService.writeVoids(anyLong(), anyString(), any(Integer.class), anyString(), any()))
                .thenThrow(new RuntimeException("表被锁"));

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强"));

        // 撤回本身已完成
        assertThat(r.getFullRollback()).isTrue();
        // 通知失败被明确回传，不静默
        assertThat(r.getNoticeWritten()).isFalse();
        assertThat(r.getNoticeError()).contains("通知消息写入失败").contains("补发");
    }

    @Test
    @DisplayName("voidTask：此前已撤回时幂等返回，不重复通知")
    void voidIdempotent() {
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 0,
                "voidedRatingCount", 0, "voidedRaterCount", 0,
                "submittedRaterCount", 0, "snapshotDeleted", 0, "alreadyVoided", 1));

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强"));

        assertThat(r.getAlreadyVoided()).isTrue();
        assertThat(r.getNoticeWritten()).isFalse();
        verify(pushService, never()).writeVoids(anyLong(), anyString(), any(Integer.class),
                anyString(), any());
    }

    @Test
    @DisplayName("voidTask：无已提交者时通知为成功且 0 人（待评者不推）")
    void voidWithoutSubmittedSendsNothing() {
        stubProc(row("taskId", TASK_ID, "sheetId", SHEET_ID, "fullRollback", 0,
                "voidedRatingCount", 0, "voidedRaterCount", 3,
                "submittedRaterCount", 0, "snapshotDeleted", 0, "alreadyVoided", 0));
        when(pushService.submittedRaters(TASK_ID)).thenReturn(List.of());

        AssessTaskVO.VoidResult r = service.voidTask(TASK_ID, voidReq("客户评价人漏选财务部王强"));

        assertThat(r.getNoticeWritten()).isTrue();
        assertThat(r.getNotifiedRaterCount()).isZero();
        verify(pushService, never()).writeVoids(anyLong(), anyString(), any(Integer.class),
                anyString(), any());
    }

    @Test
    @DisplayName("voidTask：任务不存在时报错")
    void voidMissingTask() {
        when(jdbc.queryForList(contains("FROM uf_assess_task t WHERE t.id = ?"), eq(999L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.voidTask(999L, voidReq("客户评价人漏选财务部王强")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
    }

    // ==================== 催办 ====================

    @Test
    @DisplayName("remind：只推未提交的人，biz_key 轮次递增")
    void remindOnlyPending() {
        when(pushService.pendingRaters(eq(TASK_ID), any())).thenReturn(List.of(
                row("rater_resource_id", 3785L, "access_token", "t1", "rater_type", "CLIENT")));
        when(pushService.remindRound(TASK_ID)).thenReturn(1);
        when(pushService.writeReminds(eq(TASK_ID), eq(2), anyString(), any())).thenReturn(1);

        AssessTaskVO.RemindResult r = service.remind(TASK_ID, null);

        assertThat(r.getPendingRaterCount()).isEqualTo(1);
        assertThat(r.getPushedCount()).isEqualTo(1);
        assertThat(r.getRound()).isEqualTo(2);
        assertThat(r.getMessage()).contains("1 位");
    }

    @Test
    @DisplayName("remind：指定催办只累加被催者的 remind_count，不波及未被催的人")
    void remindCountOnlyTouchesTargets() {
        when(pushService.pendingRaters(eq(TASK_ID), any())).thenReturn(List.of(
                row("rater_resource_id", 3785L, "access_token", "t1", "rater_type", "CLIENT"),
                row("rater_resource_id", 2144L, "access_token", "t2", "rater_type", "LEADER")));
        when(pushService.remindRound(TASK_ID)).thenReturn(0);
        when(pushService.writeReminds(eq(TASK_ID), eq(1), anyString(), any())).thenReturn(2);

        service.remind(TASK_ID, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("rater_resource_id IN (?,?)");
        assertThat(args.getValue()).containsExactly(TASK_ID, 3785L, 2144L);
    }

    @Test
    @DisplayName("remind：没有待评人时不发消息")
    void remindWithNobodyPending() {
        when(pushService.pendingRaters(eq(TASK_ID), any())).thenReturn(List.of());

        AssessTaskVO.RemindResult r = service.remind(TASK_ID, null);

        assertThat(r.getPushedCount()).isZero();
        assertThat(r.getMessage()).contains("无需催办");
        verify(pushService, never()).writeReminds(anyLong(), any(Integer.class), anyString(), any());
    }

    @Test
    @DisplayName("remind：已完成任务不能催办")
    void remindRejectsFinishedTask() {
        stubTask(2);

        assertThatThrownBy(() -> service.remind(TASK_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("进行中");
    }

    // ==================== 重算 ====================

    @Test
    @DisplayName("retry：进行中且全员齐备时算分并把任务置完成")
    void retryCalculates() {
        stubPending(0);
        when(jdbc.queryForMap(contains("SELECT actual_score, score_rate, grade_name"), eq(SHEET_ID)))
                .thenReturn(row("actual_score", new BigDecimal("81.25"),
                        "score_rate", new BigDecimal("81.25"), "grade_name", "胜任"));

        AssessTaskVO.RetryResult r = service.retry(TASK_ID);

        assertThat(r.getActualScore()).isEqualByComparingTo("81.25");
        assertThat(r.getGradeName()).isEqualTo("胜任");
        assertThat(r.getTaskStatus()).isEqualTo(2);
        verify(jdbc).update(contains("{call usp_assess_recalc"), eq(SHEET_ID));
        verify(jdbc).update(contains("SET status = ?"), eq(2), eq(TASK_ID));
    }

    @Test
    @DisplayName("retry：仍有待评人时拒绝（不静默跳过）")
    void retryRejectsPending() {
        stubPending(2);

        assertThatThrownBy(() -> service.retry(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提交");
        verify(jdbc, never()).update(contains("{call usp_assess_recalc"), any(Object[].class));
    }

    /** retry 里的 pending 查询是字面量 status = 0（单参数），与 fill 服务的参数化写法不同 */
    private void stubPending(int pending) {
        when(jdbc.queryForObject(
                contains("SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = 0"),
                eq(Integer.class), eq(TASK_ID))).thenReturn(pending);
    }

    @Test
    @DisplayName("retry：已撤回任务必须拒绝（闸门会数出 0 人放行，状态校验要在闸门之前）")
    void retryRejectsVoided() {
        stubTask(4);

        assertThatThrownBy(() -> service.retry(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已撤回");
        // 不应走到闸门查询，更不应调 recalc
        verify(jdbc, never()).update(contains("{call usp_assess_recalc"), any(Object[].class));
    }

    @Test
    @DisplayName("retry：已取消任务同样拒绝")
    void retryRejectsCancelled() {
        stubTask(3);

        assertThatThrownBy(() -> service.retry(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
    }

    // ==================== 删除（2026-11-14） ====================

    @Test
    @DisplayName("delete：已撤回任务可删，只删任务与评价人，sheet 保留")
    void deleteVoidedTask() {
        stubTask(4);
        when(jdbc.update(contains("DELETE FROM uf_assess_task_rater"), eq(TASK_ID))).thenReturn(3);

        AssessTaskVO.DeleteResult r = service.delete(TASK_ID);

        assertThat(r.getDeletedRaterCount()).isEqualTo(3);
        assertThat(r.getSheetId()).isEqualTo(SHEET_ID);
        assertThat(r.getSheetKept()).isTrue();
        verify(jdbc).update(contains("DELETE FROM uf_assess_task_rater"), eq(TASK_ID));
        verify(jdbc).update(contains("DELETE FROM uf_assess_task WHERE id = ?"), eq(TASK_ID));
        // 关键：绝不能碰 sheet 及其子表 —— sheet 14 在生产上被 6 个任务共用
        verify(jdbc, never()).update(contains("uf_assess_sheet"), any(Object[].class));
    }

    @Test
    @DisplayName("delete：已取消任务同样可删")
    void deleteCancelledTask() {
        stubTask(3);
        when(jdbc.update(contains("DELETE FROM uf_assess_task_rater"), eq(TASK_ID))).thenReturn(2);

        assertThat(service.delete(TASK_ID).getSheetKept()).isTrue();
        verify(jdbc).update(contains("DELETE FROM uf_assess_task WHERE id = ?"), eq(TASK_ID));
    }

    @Test
    @DisplayName("delete：进行中的任务拒绝（人还在等评价，链接一删就失效）")
    void deleteRejectsRunning() {
        stubTask(0);

        assertThatThrownBy(() -> service.delete(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已取消或已撤回");
        verify(jdbc, never()).update(contains("DELETE FROM uf_assess_task_rater"), any(Object[].class));
        verify(jdbc, never()).update(contains("DELETE FROM uf_assess_task WHERE id"), any(Object[].class));
    }

    @Test
    @DisplayName("delete：已完成的任务拒绝（有分数、进过报告，删了数据链断裂）")
    void deleteRejectsDone() {
        stubTask(2);

        assertThatThrownBy(() -> service.delete(TASK_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有已取消或已撤回");
        verify(jdbc, never()).update(contains("DELETE FROM uf_assess_task_rater"), any(Object[].class));
    }

    @Test
    @DisplayName("delete：先删评价人再删任务（顺序反了会留下孤儿评价人）")
    void deleteOrderRatersBeforeTask() {
        stubTask(4);
        when(jdbc.update(contains("DELETE FROM uf_assess_task_rater"), eq(TASK_ID))).thenReturn(1);

        service.delete(TASK_ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(jdbc);
        order.verify(jdbc).update(contains("DELETE FROM uf_assess_task_rater"), eq(TASK_ID));
        order.verify(jdbc).update(contains("DELETE FROM uf_assess_task WHERE id = ?"), eq(TASK_ID));
    }

    // ==================== 我的评价 ====================

    @Test
    @DisplayName("myTasks：只按登录身份过滤，SQL 里已排除撤回/取消的任务")
    void myTasksOnlyForCurrentUser() {
        when(jdbc.queryForList(contains("WHERE tr.rater_resource_id = ?"), eq(OPERATOR)))
                .thenReturn(List.of(row(
                        "rater_task_id", 1201L, "task_id", TASK_ID, "access_token", "tok",
                        "rater_type", "LEADER", "rater_status", 0, "task_status", 0,
                        "year", YEAR, "target_name", "张晓明", "dimension_name", "管理能力",
                        "dept_name", "生产部")));

        List<AssessTaskVO.MyTaskItem> list = service.myTasks();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStatusText()).isEqualTo("待评价");
        assertThat(list.get(0).getPath()).isEqualTo("/assess/fill/tok");
        assertThat(list.get(0).getRaterTypeName()).isEqualTo("直接上级");
    }

    @Test
    @DisplayName("myTasks：年度关闭后，未提交的不再出现在待办（2026-11-19）")
    void myTasksExcludesClosedPeriodUnlessSubmitted() {
        when(jdbc.queryForList(contains("WHERE tr.rater_resource_id = ?"), eq(OPERATOR)))
                .thenReturn(List.of(row(
                        "rater_task_id", 1201L, "task_id", TASK_ID, "access_token", "tok",
                        "rater_type", "LEADER", "rater_status", 0, "task_status", 0,
                        "year", YEAR, "target_name", "张晓明", "dimension_name", "管理能力",
                        "dept_name", "生产部")));

        service.myTasks();

        // 关闭年度的过滤必须落在 SQL 里，而不是取回内存再筛：
        // 未提交的（tr.status=0）只在年度进行中时出现，已提交的（tr.status=1）始终保留
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(OPERATOR));
        assertThat(sql.getValue()).contains("p.status = 0 OR tr.status = 1");
    }

    @Test
    @DisplayName("myTasks：状态文案映射（§7.2.1）")
    void myTasksStatusText() {
        when(jdbc.queryForList(contains("WHERE tr.rater_resource_id = ?"), eq(OPERATOR)))
                .thenReturn(List.of(
                        row("rater_task_id", 1L, "task_id", 1L, "access_token", "a",
                                "rater_type", "SELF", "rater_status", 1, "task_status", 0),
                        row("rater_task_id", 2L, "task_id", 2L, "access_token", "b",
                                "rater_type", "SELF", "rater_status", 1, "task_status", 2)));

        List<AssessTaskVO.MyTaskItem> list = service.myTasks();

        assertThat(list.get(0).getStatusText()).isEqualTo("已提交 · 等待其他评价人");
        assertThat(list.get(1).getStatusText()).isEqualTo("已完成");
    }

    // ==================== 辅助 ====================

    private void stubTask(int status) {
        when(jdbc.queryForList(contains("FROM uf_assess_task t WHERE t.id = ?"), eq(TASK_ID)))
                .thenReturn(List.of(row(
                        "id", TASK_ID, "sheet_id", SHEET_ID, "period_id", PERIOD_ID,
                        "dimension_id", DIM, "resource_id", TARGET, "status", status,
                        "rating_count", 19, "client_count", 2, "leader_resource_id", 2144L,
                        "remark", "单测", "void_reason", null)));
    }

    private void stubTaskHeadRow() {
        when(jdbc.queryForMap(contains("LEFT JOIN uf_assess_dimension d"), eq(TASK_ID)))
                .thenReturn(row("id", TASK_ID, "status", 0, "void_reason", null,
                        "create_time", null, "year", YEAR, "target_name", "张晓明",
                        "dept_name", "生产部", "dimension_name", "管理能力",
                        "leader_name", "李经理", "actual_score", null, "grade_name", null,
                        "standard_score", new BigDecimal("100.00")));
    }

    private void stubPeriod(int status) {
        when(jdbc.queryForObject(contains("SELECT status FROM uf_assess_period"), eq(Integer.class), eq(PERIOD_ID)))
                .thenReturn(status);
    }

    private void stubNoForeignData() {
        when(jdbc.queryForObject(contains("AND source <> 'WEB'"), eq(Integer.class), eq(SHEET_ID)))
                .thenReturn(0);
        // 归档表在生产中不存在
        when(jdbc.queryForObject(contains("OBJECT_ID('dbo.uf_assess_subject'"), eq(Integer.class)))
                .thenReturn(0);
    }

    private void stubSheetAfter() {
        when(jdbc.queryForMap(contains("SELECT id, status, standard_score"), eq(SHEET_ID)))
                .thenReturn(row("id", SHEET_ID, "status", 0,
                        "standard_score", new BigDecimal("100.00"),
                        "actual_score", null, "score_rate", null, "grade_name", null));
    }

    @SafeVarargs
    private void stubProc(Map<String, Object>... rows) {
        when(jdbc.queryForList(contains("{call usp_assess_void"), any(Object[].class)))
                .thenReturn(List.of(rows));
    }

    private static AssessTaskOpDTO.VoidRequest voidReq(String reason) {
        AssessTaskOpDTO.VoidRequest req = new AssessTaskOpDTO.VoidRequest();
        req.setReason(reason);
        req.setForce(false);
        return req;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
