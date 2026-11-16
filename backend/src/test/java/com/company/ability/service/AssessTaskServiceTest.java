package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessTaskDTO;
import com.company.ability.entity.AssessTask;
import com.company.ability.entity.AssessTaskRater;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessTaskMapper;
import com.company.ability.mapper.AssessTaskRaterMapper;
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

import java.util.ArrayList;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起测评单测。
 *
 * 覆盖重点是「发起前的五道闸门」，尤其是 5b/5c 两条口径护栏 ——
 * 它们的存在意义就是「宁可发起失败，也不发一个注定算不出分的任务」，
 * 一旦被误删或写反，症状是「全员都交了但永远没分」，在生产上极难定位。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessTaskServiceTest {

    private static final long DIM = 3L;
    private static final long COMPANY = 1L;
    private static final long PERIOD_ID = 13L;
    private static final long SHEET_ID = 14L;
    private static final long TARGET = 2505L;
    private static final long LEADER = 2144L;
    private static final long CLIENT_A = 3785L;
    private static final long CLIENT_B = 2381L;
    private static final long OPERATOR = 900L;
    private static final int YEAR = 2026;

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private AssessTaskMapper taskMapper;
    @Mock
    private AssessTaskRaterMapper raterMapper;
    @Mock
    private AssessSheetSyncService syncService;
    @Mock
    private AssessPushService pushService;
    @Mock
    private RaterEligibilityService eligibility;

    private AssessTaskService service;

    @BeforeEach
    void setUp() {
        service = new AssessTaskService(jdbc, taskMapper, raterMapper, syncService, pushService,
                eligibility, COMPANY);
        DimensionContext.set(DIM);
        LoginUser user = new LoginUser("tester", OPERATOR, "测试员", 10L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        stubPeriod(0);
        stubRequirement(1);
        stubSheet(SHEET_ID);
        stubRunningTask(0);
        stubElements(19, 19, 5, 0);   // 19 个要素、5 个客户可评、无孤岛外部评级
        stubLeader(LEADER);
        stubClients();
        stubInsertIds();
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("create：自评 + 领导 + N 客户各生成一条评价人链接，token 唯一且可解入口")
    void createDispatchesAllRaters() {
        AssessTaskVO.CreateResult r = service.create(request(LEADER, List.of(CLIENT_A, CLIENT_B)));

        assertThat(r.getTaskId()).isEqualTo(500L);
        assertThat(r.getSheetId()).isEqualTo(SHEET_ID);
        assertThat(r.getRatingCount()).isEqualTo(19);
        assertThat(r.getClientCount()).isEqualTo(2);
        assertThat(r.getRaters()).hasSize(4);   // SELF + LEADER + 2 CLIENT

        assertThat(r.getRaters()).extracting(AssessTaskVO.RaterLink::getRaterType)
                .containsExactly("SELF", "LEADER", "CLIENT", "CLIENT");
        assertThat(r.getRaters()).extracting(AssessTaskVO.RaterLink::getRaterResourceId)
                .containsExactly(TARGET, LEADER, CLIENT_A, CLIENT_B);
        assertThat(r.getRaters()).allMatch(l -> l.getAccessToken().length() >= 40);
        assertThat(r.getRaters()).allMatch(l -> l.getPath().equals("/assess/fill/" + l.getAccessToken()));
        assertThat(r.getRaters()).allMatch(l -> l.getRaterName() != null);
        // token 不能重复（重复会导致不同人打开同一份评价）
        assertThat(r.getRaters()).extracting(AssessTaskVO.RaterLink::getAccessToken).doesNotHaveDuplicates();

        // 任务落库字段与元素口径一致
        ArgumentCaptor<AssessTask> task = ArgumentCaptor.forClass(AssessTask.class);
        verify(taskMapper).insert(task.capture());
        assertThat(task.getValue().getSheetId()).isEqualTo(SHEET_ID);
        assertThat(task.getValue().getLeaderResourceId()).isEqualTo(LEADER);
        assertThat(task.getValue().getRatingCount()).isEqualTo(19);
        assertThat(task.getValue().getClientCount()).isEqualTo(2);
        assertThat(task.getValue().getCreateBy()).isEqualTo(OPERATOR);

        // 页面头回填 + 日志
        verify(jdbc).update(contains("UPDATE uf_assess_sheet SET leader_resource_id"), eq(LEADER), eq(2), eq(SHEET_ID));
        verify(jdbc).update(contains("INSERT INTO uf_assess_sheet_log"), eq(SHEET_ID), eq("TASK_CREATE"),
                eq(OPERATOR), anyString(), eq(DIM));
    }

    @Test
    @DisplayName("create：直接领导只认 OA 组织关系（HrmResource.managerid），入参不参与决策")
    void createFallsBackToSheetLeader() {
        AssessTaskVO.LeaderInfo info = service.resolveLeader(TARGET);

        assertThat(info.getLeaderResourceId()).isEqualTo(LEADER);
        assertThat(info.getLeaderName()).isEqualTo("李四");
    }

    @Test
    @DisplayName("create：客户名单去重（同一人填两次只算一位）")
    void createDedupesClients() {
        AssessTaskVO.CreateResult r = service.create(request(LEADER, List.of(CLIENT_A, CLIENT_A, CLIENT_B)));

        assertThat(r.getClientCount()).isEqualTo(2);
        assertThat(r.getRaters()).hasSize(4);
    }

    @Test
    @DisplayName("create：无客户可评要素且不选客户时正常发起（只 SELF + LEADER）")
    void createWithoutClientElements() {
        stubElements(19, 19, 0, 0);

        AssessTaskVO.CreateResult r = service.create(request(LEADER, List.of()));

        assertThat(r.getClientCount()).isZero();
        assertThat(r.getRaters()).extracting(AssessTaskVO.RaterLink::getRaterType)
                .containsExactly("SELF", "LEADER");
    }

    // ==================== 闸门 ====================

    @Test
    @DisplayName("create：年度不存在拒绝")
    void rejectMissingPeriod() {
        when(jdbc.queryForList(contains("FROM uf_assess_period WHERE company_id = ?"), eq(COMPANY), eq(YEAR), eq(DIM)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("年度不存在");
        verifyNoTaskCreated();
    }

    @Test
    @DisplayName("create：年度已关闭拒绝")
    void rejectClosedPeriod() {
        stubPeriod(1);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关闭");
        verifyNoTaskCreated();
    }

    @Test
    @DisplayName("create：无人员能力要求拒绝（没有页可评）")
    void rejectWithoutRequirement() {
        stubRequirement(0);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("人员能力要求");
        verifyNoTaskCreated();
        verify(syncService, never()).syncRequirement(anyLong(), any(Integer.class), eq(true));
    }

    @Test
    @DisplayName("create：同一页面已有进行中任务时拒绝（防两人同时评）")
    void rejectDuplicateRunningTask() {
        stubRunningTask(1);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有进行中的测评任务");
        verifyNoTaskCreated();
    }

    @Test
    @DisplayName("create：OA 组织关系里上级就是被测人本人时拒绝")
    void rejectSelfAsLeader() {
        stubLeader(TARGET);

        assertThatThrownBy(() -> service.create(request(TARGET, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能是被测人本人");
    }

    @Test
    @DisplayName("create：入参 leaderResourceId 不参与决策，领导一律以 OA 为准")
    void createIgnoresLeaderArgument() {
        AssessTaskVO.CreateResult r = service.create(request(CLIENT_A, List.of(CLIENT_A)));

        assertThat(r.getRaters()).extracting(AssessTaskVO.RaterLink::getRaterResourceId)
                .containsExactly(TARGET, LEADER, CLIENT_A);
    }

    @Test
    @DisplayName("create：OA 未维护直接上级时拒绝（组织关系是唯一口径，不让人工指定兜底）")
    void rejectMissingLeader() {
        stubLeader(0L);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有直接上级")
                .hasMessageContaining("张三");
    }

    @Test
    @DisplayName("create：领导已离职时拒绝")
    void rejectResignedLeader() {
        when(eligibility.problems(any(), any())).thenReturn(List.of(
                new RaterEligibilityService.Verdict(LEADER, "李四", "直接上级", false,
                        RaterEligibilityService.Reason.RESIGNED, "已离职（status=5），无法参与测评")));

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已离职");
    }

    @Test
    @DisplayName("create：被测人无 OA 登录名时拒绝（2026-11-13 回归：曾放过 1343 人）")
    void rejectTargetWithoutLoginid() {
        when(eligibility.problems(any(), any())).thenReturn(List.of(
                new RaterEligibilityService.Verdict(TARGET, "王五", "被测人", false,
                        RaterEligibilityService.Reason.NO_LOGINID,
                        "在岗但没有 OA 登录名，无法登录系统，请先在泛微中补齐登录名")));

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("被测人")
                .hasMessageContaining("没有 OA 登录名");
    }

    @Test
    @DisplayName("create：存在不合格人员时拒绝，并一次报出全部问题人员")
    void rejectMultipleProblemsAndListThemAll() {
        when(eligibility.problems(any(), any())).thenReturn(List.of(
                new RaterEligibilityService.Verdict(TARGET, "王五", "被测人", false,
                        RaterEligibilityService.Reason.NO_LOGINID, "没有 OA 登录名"),
                new RaterEligibilityService.Verdict(CLIENT_A, "赵六", "客户评价人", false,
                        RaterEligibilityService.Reason.RESIGNED, "已离职（status=5），无法参与测评")));

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("被测人")
                .hasMessageContaining("另有 1 人也存在问题");
    }

    @Test
    @DisplayName("create：校验范围覆盖被测人 + 领导 + 全部客户，一个不漏")
    void eligibilityCoversAllParticipants() {
        service.create(request(LEADER, List.of(CLIENT_A, CLIENT_B)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, String>> roles = ArgumentCaptor.forClass(Map.class);
        verify(eligibility).problems(ids.capture(), roles.capture());

        assertThat(ids.getValue()).containsExactly(TARGET, LEADER, CLIENT_A, CLIENT_B);
        assertThat(roles.getValue())
                .containsEntry(TARGET, "被测人")
                .containsEntry(LEADER, "直接上级")
                .containsEntry(CLIENT_A, "客户评价人");
    }

    @Test
    @DisplayName("create：有客户可评要素但未选客户时拒绝")
    void rejectMissingClients() {
        assertThatThrownBy(() -> service.create(request(LEADER, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少选择一位客户评价人");
    }

    @Test
    @DisplayName("create：无客户可评要素却指定了客户时拒绝（避免占名额空转）")
    void rejectUselessClients() {
        stubElements(19, 19, 0, 0);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能指定客户评价人");
    }

    @Test
    @DisplayName("create：被测人本人不能作为客户评价人")
    void rejectSelfAsClient() {
        assertThatThrownBy(() -> service.create(request(LEADER, List.of(TARGET))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本人不能作为客户评价人");
    }

    // ==================== 口径护栏 5b / 5c ====================

    @Test
    @DisplayName("create：页面 active 行数 ≠ 要素口径时拒绝（否则算分永远对不上）")
    void rejectActiveCountMismatch() {
        // 要素口径 19 条，但页面只有 18 条 active —— 模拟 sql/40 修复前的漂移
        stubElements(19, 18, 5, 0);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效要素与能力要素开关不一致");
        verifyNoTaskCreated();
    }

    @Test
    @DisplayName("create：存在「流程中不评价」却带外部导入评级时拒绝（网页通道无法补齐计数）")
    void rejectOrphanExternalRatings() {
        stubElements(19, 19, 5, 3);

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("外部导入评级");
        verifyNoTaskCreated();
    }

    @Test
    @DisplayName("create：要素全被关掉时拒绝（没有需要评价的要素）")
    void rejectNoAssessableElements() {
        when(jdbc.queryForList(contains("ISNULL(e.assess_in_flow, 1) = 1"), eq(SHEET_ID)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request(LEADER, List.of(CLIENT_A))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有需要评价的能力要素");
    }

    // ==================== 辅助 ====================

    private void verifyNoTaskCreated() {
        verify(taskMapper, never()).insert(any(AssessTask.class));
        verify(raterMapper, never()).insert(any(AssessTaskRater.class));
    }

    private static AssessTaskDTO.CreateRequest request(Long leaderId, List<Long> clients) {
        AssessTaskDTO.CreateRequest req = new AssessTaskDTO.CreateRequest();
        req.setYear(YEAR);
        req.setResourceId(TARGET);
        req.setLeaderResourceId(leaderId);
        req.setClientResourceIds(clients);
        req.setRemark("单测");
        return req;
    }

    private void stubPeriod(int status) {
        when(jdbc.queryForList(contains("FROM uf_assess_period WHERE company_id = ?"),
                eq(COMPANY), eq(YEAR), eq(DIM)))
                .thenReturn(List.of(row("id", PERIOD_ID, "status", status)));
    }

    private void stubRequirement(int count) {
        when(jdbc.queryForObject(contains("FROM uf_user_ability_req_new WHERE resource_id = ?"),
                eq(Integer.class), eq(TARGET), eq(YEAR), eq(DIM))).thenReturn(count);
    }

    private void stubSheet(long sheetId) {
        when(jdbc.queryForObject(contains("SELECT id FROM uf_assess_sheet WHERE period_id = ?"),
                eq(Long.class), eq(PERIOD_ID), eq(TARGET))).thenReturn(sheetId);
        when(jdbc.queryForMap(contains("SELECT department_id, job_title_id FROM uf_assess_sheet"),
                eq(sheetId))).thenReturn(row("department_id", 57L, "job_title_id", 3L));
    }

    private void stubRunningTask(int running) {
        when(jdbc.queryForObject(contains("FROM uf_assess_task WHERE sheet_id = ? AND status = ?"),
                eq(Integer.class), eq(SHEET_ID), eq(0))).thenReturn(running);
    }

    /**
     * @param flowCount   要素口径条数（assess_in_flow=1）
     * @param activeCount 页面 active 条数
     * @param clientCount 其中允许客户评价的条数
     * @param orphan      含孤儿外部评级的行数
     */
    private void stubElements(int flowCount, int activeCount, int clientCount, int orphan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < flowCount; i++) {
            rows.add(row("element_id", 100L + i,
                    "assess_in_flow", 1,
                    "allow_client_assess", i < clientCount ? 1 : 0));
        }
        when(jdbc.queryForList(contains("ISNULL(e.assess_in_flow, 1) = 1"), eq(SHEET_ID)))
                .thenReturn(rows);

        when(jdbc.queryForObject(contains("AND r.active = 1 AND e.deleted = 0"),
                eq(Integer.class), eq(SHEET_ID))).thenReturn(activeCount);
        when(jdbc.queryForObject(contains("EXISTS ("), eq(Integer.class), eq(SHEET_ID))).thenReturn(orphan);
    }

    private void stubLeader(long leaderId) {
        when(jdbc.queryForList(contains("SELECT r.managerid"), eq(TARGET)))
                .thenReturn(List.of(row("managerid", leaderId, "leader_name", "李四",
                        "dept_name", "研发部")));
        // 提示语里出现的人名：被测人叫张三，其余一律"某人"（避免与领导名混淆）
        when(jdbc.queryForList(contains("SELECT lastname FROM HrmResource"), eq(String.class), anyLong()))
                .thenAnswer(inv -> List.of(inv.<Long>getArgument(2) == TARGET ? "张三" : "某人"));
    }

    private void stubClients() {
        // 全员可登录性校验交给 RaterEligibilityService（@Mock 默认返回空列表 = 无人有问题）。
        // 需要构造问题人员的用例自行覆盖 problems(...)。
    }

    /** 让 insert 之后实体拿到 ID，模拟自增主键回填 */
    private void stubInsertIds() {
        doAnswer(inv -> {
            AssessTask t = inv.getArgument(0);
            t.setId(500L);
            return 1;
        }).when(taskMapper).insert(any(AssessTask.class));

        final long[] seq = {700L};
        doAnswer(inv -> {
            AssessTaskRater r = inv.getArgument(0);
            r.setId(seq[0]++);
            return 1;
        }).when(raterMapper).insert(any(AssessTaskRater.class));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
