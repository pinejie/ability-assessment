package com.company.ability.service;

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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息入队单测。
 *
 * 2026-11-13 起队列表是通用设施（uf_push_msg），业务语义全部收拢在本类。
 * 因此这里的用例锁定「入队侧该负责的事」，任务侧不该再有：
 * ① token 只能在 pc_url/mobile_url，绝不能进 content（泛微会过滤+截断正文）；
 * ② 一人一行，同人多角色必须拆成多行（合并会导致链接只有第一个能用）；
 * ③ 撤回通知只发已提交者，待评者不推。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessPushServiceTest {

    private static final long TASK_ID = 88L;
    private static final String BASE = "http://assess.example.com";

    @Mock
    private JdbcTemplate jdbc;

    private AssessPushService service;

    @BeforeEach
    void setUp() {
        service = new AssessPushService(jdbc);
        ReflectionTestUtils.setField(service, "fillBaseUrl", BASE);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    @DisplayName("writeInvites：token 只出现在 pc_url/mobile_url，正文里不能有")
    void inviteKeepsTokenOutOfContent() {
        service.writeInvites(TASK_ID, 2026, 20, "张晓明",
                List.of(link("SELF", 2505L, "tok-self")));

        Object[] args = capturedInsert();
        String content = (String) args[3];
        String pcUrl = (String) args[4];
        assertThat(pcUrl).isEqualTo(BASE + "/assess/fill/tok-self");
        // 移动端单独一条路径：泛微 e9 按终端分别取 pc_url / mobile_url，
        // 同一个地址在手机上排版不可用（2026-11-14）。
        assertThat((String) args[5]).isEqualTo(BASE + "/m/assess/fill/tok-self");
        assertThat(content).doesNotContain("tok-self");
        assertThat(content).contains("2026").contains("20 个要素");
    }

    @Test
    @DisplayName("writeInvites：三种角色文案不同，接收人一人一行")
    void inviteUsesRoleSpecificCopy() {
        service.writeInvites(TASK_ID, 2026, 20, "张晓明", List.of(
                link("SELF", 2505L, "t1"),
                link("LEADER", 2144L, "t2"),
                link("CLIENT", 3785L, "t3")));

        ArgumentCaptor<Object[]> caps = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(anyString(), caps.capture());
        List<Object[]> all = caps.getAllValues();

        assertThat((String) all.get(0)[2]).contains("自评");
        assertThat((String) all.get(1)[2]).contains("上级评价");
        assertThat((String) all.get(2)[2]).contains("客户评价");

        // receiver_id 是第一列：每人独立一行，不拼逗号串
        assertThat(all.get(0)[0]).isEqualTo(2505L);
        assertThat(all.get(1)[0]).isEqualTo(2144L);
        assertThat(all.get(2)[0]).isEqualTo(3785L);
    }

    @Test
    @DisplayName("同一人在同一任务有多个角色时，插入多行各自独立的链接")
    void sameRaterWithTwoRolesGetsTwoRows() {
        service.writeInvites(TASK_ID, 2026, 20, "张晓明", List.of(
                link("LEADER", 2505L, "tok-leader"),
                link("CLIENT", 2505L, "tok-client")));

        ArgumentCaptor<Object[]> caps = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), caps.capture());
        List<Object[]> all = caps.getAllValues();

        // 同一个人，两行；链接不同，各自能打开
        assertThat(all.get(0)[0]).isEqualTo(2505L);
        assertThat(all.get(1)[0]).isEqualTo(2505L);
        assertThat((String) all.get(0)[4]).endsWith("/tok-leader");
        assertThat((String) all.get(1)[4]).endsWith("/tok-client");
    }

    @Test
    @DisplayName("插入使用通用表 uf_push_msg 与专属消息类型 596")
    void insertTargetsGenericTable() {
        service.writeInvites(TASK_ID, 2026, 20, "张晓明",
                List.of(link("SELF", 2505L, "tok")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO uf_push_msg")
                .doesNotContain("biz_key")
                .doesNotContain("receiver_ids");

        // 这里钉死 596 是有意的：换类型必须先确认 OA 侧两件事都齐了才允许——
        //   ECOLOGY_MESSAGE_TYPE 有该 typecode，且 ECOLOGY_BIZ_MOBILE_CONFIG 有
        //   MESSAGETYPEID=',596,' 的行。否则站内能收、手机端静默收不到。
        assertThat(capturedInsert()[1]).isEqualTo(596);
    }

    @Test
    @DisplayName("writeVoids：正文携带真实撤回原因（Q12），链接仍用旧 token")
    void voidCarriesReason() {
        service.writeVoids(TASK_ID, "张晓明", 2026, "客户评价人漏选财务部王强",
                List.of(row("rater_resource_id", 2144L, "access_token", "tok-old")));

        Object[] args = capturedInsert();
        String content = (String) args[3];
        assertThat(content).contains("已被管理员撤回")
                .contains("客户评价人漏选财务部王强")
                .contains("作废清除");
        assertThat((String) args[4]).isEqualTo(BASE + "/assess/fill/tok-old");
        assertThat(args[0]).isEqualTo(2144L);
    }

    @Test
    @DisplayName("writeReminds：催办只推未提交的人，链接用自己的 token")
    void remindGoesToPendingOnly() {
        service.writeReminds(TASK_ID, 2, "张晓明",
                List.of(row("rater_resource_id", 3785L, "access_token", "tok-r", "rater_type", "CLIENT")));

        Object[] args = capturedInsert();
        assertThat(args[0]).isEqualTo(3785L);
        assertThat((String) args[3]).contains("尽快完成");
        assertThat((String) args[4]).endsWith("/tok-r");
    }

    @Test
    @DisplayName("insert：正文超过 500 字时截断（防御性，正常路径 reason≤200 到不了）")
    void longContentTruncated() {
        service.insert(1L, "标题", "原".repeat(800), "tok");

        assertThat((String) capturedInsert()[3]).hasSize(500);
    }

    @Test
    @DisplayName("未配置评分域名时不编造链接")
    void noBaseUrlMeansNoLink() {
        ReflectionTestUtils.setField(service, "fillBaseUrl", "");

        service.writeInvites(TASK_ID, 2026, 20, "张晓明", List.of(link("SELF", 2505L, "tok")));

        Object[] args = capturedInsert();
        assertThat(args[4]).isNull();
        assertThat(args[5]).isNull();
    }

    @Test
    @DisplayName("remindRound：从 remind_count 取最大值，不能数行数（一次催 3 人会算成 3 轮）")
    void remindRoundUsesMaxRemindCount() {
        when(jdbc.queryForObject(contains("MAX(remind_count)"), eq(Integer.class), eq(TASK_ID)))
                .thenReturn(2);

        assertThat(service.remindRound(TASK_ID)).isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Integer.class), eq(TASK_ID));
        assertThat(sql.getValue())
                .contains("uf_assess_task_rater")
                .doesNotContain("uf_push_msg")
                .doesNotContain("biz_key");
    }

    @Test
    @DisplayName("submittedRaters：按 submit_time 判定，不能按 status=1（撤回后已被置 3）")
    void submittedRatersUsesSubmitTimeNotStatus() {
        when(jdbc.queryForList(anyString(), eq(TASK_ID))).thenReturn(List.of());

        service.submittedRaters(TASK_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(TASK_ID));
        assertThat(sql.getValue())
                .contains("submit_time IS NOT NULL")
                .doesNotContain("status = 1");
    }

    // ==================== 辅助 ====================

    private Object[] capturedInsert() {
        ArgumentCaptor<Object[]> cap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO uf_push_msg"), cap.capture());
        return cap.getValue();
    }

    private static com.company.ability.vo.AssessTaskVO.RaterLink link(String type, long resourceId, String token) {
        com.company.ability.vo.AssessTaskVO.RaterLink l = new com.company.ability.vo.AssessTaskVO.RaterLink();
        l.setRaterType(type);
        l.setRaterResourceId(resourceId);
        l.setAccessToken(token);
        l.setRaterName("张三");
        return l;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
