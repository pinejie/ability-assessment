package com.company.ability.service;

import com.company.ability.service.RaterEligibilityService.Reason;
import com.company.ability.service.RaterEligibilityService.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价人可登录性校验。
 *
 * 这个类的唯一职责是「判据与 SSO 登录一致」：{@code status IN (0,1,2,3) AND loginid 非空}。
 * 这条判据有两头都会出错：
 * <ul>
 *   <li>放宽 loginid 非空 → 放进一批泛微本身鉴权不了的人，链接全是死链；</li>
 *   <li>收紧状态到 status=1 → 试用期/试用延期/临时人员进不来，
 *       与业务口径「只要在职就可以」不符（2026-11-13 就是这么错的）。</li>
 * </ul>
 * 所以两个方向都要有用例钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RaterEligibilityServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private DimensionIntegrationService integrationService;

    private RaterEligibilityService service() {
        return service("loginid");
    }

    /** 指定本维度用哪个字段认人（loginid = 泛微口径，workcode = 其它系统口径） */
    private RaterEligibilityService service(String matchField) {
        when(integrationService.matchFieldOf(any())).thenReturn(matchField);
        return new RaterEligibilityService(jdbc, new PersonStatusPolicy(integrationService));
    }

    // ==================== 判据 ====================

    @Test
    @DisplayName("status=1 且 loginid 非空 → 可参与")
    void eligible() {
        stub(hr(2505L, "王国栋", 1, "2024188"));

        List<Verdict> v = service().check(List.of(2505L), Map.of(2505L, "被测人"));

        assertThat(v).singleElement().satisfies(x -> {
            assertThat(x.eligible()).isTrue();
            assertThat(x.reason()).isEqualTo(Reason.OK);
            assertThat(x.message()).isNull();
            assertThat(x.name()).isEqualTo("王国栋");
            assertThat(x.role()).isEqualTo("被测人");
        });
    }

    @Test
    @DisplayName("status=0/2/3（试用/临时/试用延期）+ 有登录名 → 可参与（业务口径：只要在职就可以）")
    void acceptNonFormalActiveStatuses() {
        stub(hr(1L, "甲", 0, "a"), hr(2L, "乙", 2, "b"), hr(3L, "丙", 3, "c"));

        assertThat(service().check(List.of(1L, 2L, 3L), null))
                .extracting(Verdict::reason)
                .containsExactly(Reason.OK, Reason.OK, Reason.OK);
    }

    @Test
    @DisplayName("status=0/2/3 但没有登录名 → 仍拒绝（状态放开不等于登录名可以空）")
    void rejectNonFormalWithoutLoginid() {
        stub(hr(1L, "甲", 0, null), hr(2L, "乙", 2, ""), hr(3L, "丙", 3, "  "));

        assertThat(service().check(List.of(1L, 2L, 3L), null))
                .extracting(Verdict::reason)
                .containsExactly(Reason.NO_LOGINID, Reason.NO_LOGINID, Reason.NO_LOGINID);
    }

    @Test
    @DisplayName("status=1 但 loginid 为 null → 拒绝（实测 1343 人属此类，是本类存在的主因）")
    void rejectNullLoginid() {
        stub(hr(2505L, "王国栋", 1, null));

        List<Verdict> v = service().check(List.of(2505L), null);

        assertThat(v).singleElement().satisfies(x -> {
            assertThat(x.eligible()).isFalse();
            assertThat(x.reason()).isEqualTo(Reason.NO_LOGINID);
            assertThat(x.message()).contains("没有 OA 登录名");
        });
    }

    @Test
    @DisplayName("status=1 但 loginid 为空串/纯空格 → 与 null 同等对待")
    void rejectBlankLoginid() {
        stub(hr(1L, "甲", 1, "   "), hr(2L, "乙", 1, ""));

        assertThat(service().check(List.of(1L, 2L), null))
                .extracting(Verdict::reason)
                .containsExactly(Reason.NO_LOGINID, Reason.NO_LOGINID);
    }

    @Test
    @DisplayName("维度按工号认人时，没有登录名但有工号的人可以参与（第一步不启用，第二步才放开的人群）")
    void workcodeMatchedPersonWithoutLoginidIsEligible() {
        stub(hrWithWorkcode(8106L, "周无账号", 1, null, "2024001"));

        List<Verdict> v = service("workcode").check(List.of(8106L), null);

        assertThat(v).singleElement().satisfies(x -> {
            assertThat(x.eligible()).isTrue();
            assertThat(x.reason()).isEqualTo(Reason.OK);
        });
    }

    @Test
    @DisplayName("维度按工号认人时，工号为空同样拒绝，且提示语说的是工号不是登录名")
    void workcodeMatchedPersonWithoutWorkcodeIsRejected() {
        stub(hrWithWorkcode(1L, "甲", 1, "haslogin", "   "));

        List<Verdict> v = service("workcode").check(List.of(1L), null);

        assertThat(v).singleElement().satisfies(x -> {
            assertThat(x.eligible()).isFalse();
            assertThat(x.reason()).isEqualTo(Reason.NO_LOGINID);
            // 提示语必须说清缺的是哪一列，否则 HR 会去补错东西
            assertThat(x.message()).contains("工号").doesNotContain("OA 登录名");
        });
    }

    @Test
    @DisplayName("status=4/5/6/7（解聘/离职/退休/失效）→ 拒绝，且原因可区分")
    void rejectLeftStatuses() {
        stub(hr(1L, "甲", 4, "a"), hr(2L, "乙", 5, "b"), hr(3L, "丙", 6, "c"), hr(4L, "丁", 7, "d"));

        assertThat(service().check(List.of(1L, 2L, 3L, 4L), null))
                .extracting(Verdict::reason)
                .containsExactly(Reason.DISMISSED, Reason.RESIGNED, Reason.RETIRED, Reason.INVALID);
    }

    @Test
    @DisplayName("离职的人即使有登录名也拒绝（status 判据先于 loginid）")
    void rejectLeftEvenWithLoginid() {
        stub(hr(1L, "甲", 5, "leaver"));

        assertThat(service().check(List.of(1L), null))
                .singleElement()
                .satisfies(x -> {
                    assertThat(x.eligible()).isFalse();
                    assertThat(x.reason()).isEqualTo(Reason.RESIGNED);
                });
    }

    @Test
    @DisplayName("未出现在 HrmResource → NOT_FOUND，不因查不到就静默放过")
    void rejectNotFound() {
        stub();

        assertThat(service().check(List.of(999L), null))
                .singleElement()
                .satisfies(x -> {
                    assertThat(x.eligible()).isFalse();
                    assertThat(x.reason()).isEqualTo(Reason.NOT_FOUND);
                });
    }

    @Test
    @DisplayName("未知状态码 → 拒绝（宁可拒绝也不能放一个不确定的人进来）")
    void rejectUnknownStatus() {
        stub(hr(1L, "甲", 9, "a"));

        assertThat(service().check(List.of(1L), null))
                .singleElement()
                .satisfies(x -> {
                    assertThat(x.eligible()).isFalse();
                    assertThat(x.reason()).isEqualTo(Reason.UNKNOWN_STATUS);
                    assertThat(x.message()).contains("status=9");
                });
    }

    // ==================== 批量行为 ====================

    @Test
    @DisplayName("problems 只返回不合格的人，合格的不占位")
    void problemsFiltersEligible() {
        stub(hr(1L, "甲", 1, "a"), hr(2L, "乙", 5, null));

        assertThat(service().problems(List.of(1L, 2L), null))
                .extracting(Verdict::resourceId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("入参去重且保序；空/0/null 被忽略，不会拼出 IN () 语法错误")
    void dedupesAndPreservesOrder() {
        stub(hr(1L, "甲", 1, "a"), hr(2L, "乙", 1, "b"));

        List<Verdict> v = service().check(List.of(2L, 1L, 2L, 0L), null);

        assertThat(v).extracting(Verdict::resourceId).containsExactly(2L, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("IN (?,?)"), args.capture());
        assertThat(args.getValue()).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("空集合不发 SQL（避免 IN () 语法错误）")
    void emptyInputSkipsQuery() {
        assertThat(service().check(List.of(), null)).isEmpty();
        verify(jdbc, org.mockito.Mockito.never()).queryForList(anyString(), any(Object[].class));
    }

    // ==================== 提示语 ====================

    @Test
    @DisplayName("describe 把多个问题人员拼成一句，带角色与姓名")
    void describeCombinesAll() {
        String text = RaterEligibilityService.describe(List.of(
                new Verdict(1L, "张三", "被测人", false, Reason.NO_LOGINID, "没有 OA 登录名"),
                new Verdict(2L, "李四", "客户评价人", false, Reason.RESIGNED, "已离职")));

        assertThat(text)
                .contains("张三（被测人）")
                .contains("李四（客户评价人）")
                .contains("；");
    }

    @Test
    @DisplayName("describe 遇到无姓名的人用 ID 兜底，不输出 null")
    void describeHandlesMissingName() {
        String text = RaterEligibilityService.describe(List.of(
                new Verdict(42L, null, "客户评价人", false, Reason.NOT_FOUND, "人员不存在")));

        assertThat(text).contains("ID 42").doesNotContain("null");
    }

    // ==================== 辅助 ====================

    private void stub(Map<String, Object>... rows) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(rows));
    }

    private static Map<String, Object> hr(long id, String name, int status, String loginid) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("lastname", name);
        m.put("status", status);
        m.put("loginid", loginid);
        return m;
    }

    /** 带上工号的行：用于验证「维度改成按工号认人后，没登录名的人也能参与」 */
    private static Map<String, Object> hrWithWorkcode(long id, String name, int status,
                                                      String loginid, String workcode) {
        Map<String, Object> m = hr(id, name, status, loginid);
        m.put("workcode", workcode);
        return m;
    }
}
