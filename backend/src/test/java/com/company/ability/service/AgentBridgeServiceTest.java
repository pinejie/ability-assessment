package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.ExternalScoreVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能导入二期桥服务单测：令牌门、维度解析歧义、不可交付不落稿、过期作废、提交直通。
 * zd-code 代理路径不在本测覆盖（冒烟已实测）；draft 插入用 KeyHolder 桩返回主键。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentBridgeServiceTest {

    private static final String TOKEN = "cp-test-internal-token";
    private static final String PAYLOAD =
            "{\"sessionId\":\"s-1\",\"year\":2026,\"rows\":[{\"kind\":\"RATING\",\"resourceId\":224," +
            "\"elementId\":32,\"levelNo\":4,\"originalScore\":88}]}";

    @Mock private JdbcTemplate jdbc;
    @Mock private ExternalScoreService externalScoreService;

    private AgentBridgeService service;

    @BeforeEach
    void setUp() {
        service = new AgentBridgeService(jdbc, new ObjectMapper(), externalScoreService,
                true, "http://127.0.0.1:8000", "", "/home/wgd/ws/cp", TOKEN,
                "ws://172.18.28.206:8000", 1L, 30, 2);
        LoginUser user = new LoginUser("tester", 900L, "测试员", 10L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        DimensionContext.clear();
    }

    // ==================== submitDraft ====================

    @Test
    @DisplayName("submitDraft：内部令牌错误一律 401")
    void wrongTokenRejected() {
        assertThatThrownBy(() -> service.submitDraft("nope", PAYLOAD, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内部令牌无效");
    }

    @Test
    @DisplayName("submitDraft：sessionId 缺失/非法阻断（前端首条消息上下文必须回带）")
    void missingSessionIdRejected() {
        assertThatThrownBy(() -> service.submitDraft(TOKEN, PAYLOAD.replace("s-1", ""), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("submitDraft：被测人同年度多维度页面且未指定维度 → 拒绝（歧义不猜）")
    void ambiguousDimensionRejected() {
        when(jdbc.queryForList(contains("uf_assess_sheet s"), any(Object[].class))).thenReturn(List.of(
                row("resource_id", 224L, "id", 11L, "dimension_id", 3L),
                row("resource_id", 224L, "id", 12L, "dimension_id", 4L)));
        assertThatThrownBy(() -> service.submitDraft(TOKEN, PAYLOAD, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("维度");
    }

    @Test
    @DisplayName("submitDraft：无页面 → 拒绝")
    void noSheetRejected() {
        when(jdbc.queryForList(contains("uf_assess_sheet s"), any(Object[].class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.submitDraft(TOKEN, PAYLOAD, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无测评页面");
    }

    @Test
    @DisplayName("submitDraft：preview 有 BLOCK 时返回行结果、不落稿")
    void blockedNotPersisted() {
        stubSheet();
        when(externalScoreService.preview(any())).thenReturn(preview(false, "评级必须为 1–5 整数"));
        Map<String, Object> out = service.submitDraft(TOKEN, PAYLOAD, false);
        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("ready")).isEqualTo(false);
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
    }

    @Test
    @DisplayName("submitDraft：validate 模式只校验不落稿")
    void validateOnlyDoesNotPersist() {
        stubSheet();
        when(externalScoreService.preview(any())).thenReturn(preview(true, null));
        Map<String, Object> out = service.submitDraft(TOKEN, PAYLOAD, true);
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("validateOnly")).isEqualTo(true);
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
    }

    @Test
    @DisplayName("submitDraft：通过则落稿返回 draftId + ready + 过期时间")
    void happyPathPersistsDraft() {
        stubSheet();
        when(externalScoreService.preview(any())).thenReturn(preview(true, null));
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class))).thenAnswer(inv -> {
                    ((GeneratedKeyHolder) inv.getArgument(1)).getKeyList().add(Map.of("KEY", 42L));
                    return 1;
                });
        Map<String, Object> out = service.submitDraft(TOKEN, PAYLOAD, false);
        assertThat(out.get("draftId")).isEqualTo(42L);
        assertThat(out.get("ready")).isEqualTo(true);
        assertThat(out.get("expiresAt")).isNotNull();
    }

    // ==================== commitDraft ====================

    @Test
    @DisplayName("commitDraft：过期稿（超过绝对上限）拒绝并标记 EXPIRED")
    void expiredDraftRejected() {
        String old = LocalDateTime.now().minusHours(3)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(1L, "READY", old, old)));
        assertThatThrownBy(() -> service.commitDraft(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("过期");
        verify(jdbc).update(contains("SET status='EXPIRED'"), ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("commitDraft：已提交稿拒绝重复写入")
    void committedDraftRejected() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(2L, "COMMITTED", now, now)));
        assertThatThrownBy(() -> service.commitDraft(2L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能重复");
    }

    @Test
    @DisplayName("commitDraft：重校验挡回时保持可议（rejected=true，不写库、不改状态）")
    void recheckRejectionKeepsDraft() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(3L, "READY", now, now)));
        when(externalScoreService.preview(any())).thenReturn(preview(false, "年度已关闭"));
        Map<String, Object> out = service.commitDraft(3L);
        assertThat(out.get("rejected")).isEqualTo(true);
        verify(externalScoreService, never()).commit(any());
    }

    @Test
    @DisplayName("commitDraft：通过则按稿内维度提交并标记 COMMITTED")
    void happyCommit() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(4L, "READY", now, now)));
        when(externalScoreService.preview(any())).thenReturn(preview(true, null));
        // 提交调用瞬间维度应已切到稿内维度（withDimension 结束后恢复，只能在调用内断言）
        long[] dimAtCommit = new long[1];
        ExternalScoreVO.CommitResult commitResult = new ExternalScoreVO.CommitResult();
        ExternalScoreVO.CommitPersonResult p = new ExternalScoreVO.CommitPersonResult();
        p.setResourceId(224L);
        p.setSuccess(true);
        p.setExternalTotal(1);
        p.setExternalCovered(1);
        commitResult.setPersons(List.of(p));
        when(externalScoreService.commit(any())).thenAnswer(inv -> {
            dimAtCommit[0] = DimensionContext.getOrDefault();
            return commitResult;
        });

        Map<String, Object> out = service.commitDraft(4L);
        assertThat(out.get("rejected")).isEqualTo(false);
        assertThat(dimAtCommit[0]).isEqualTo(3L); // 提交期临时切到稿内维度
        verify(jdbc).update(contains("SET status=?"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
    }

    // ==================== 辅助 ====================

    private void stubSheet() {
        when(jdbc.queryForList(contains("uf_assess_sheet s"), any(Object[].class))).thenReturn(
                List.of(row("resource_id", 224L, "id", 11L, "dimension_id", 3L)));
    }

    private static ExternalScoreVO.PreviewResult preview(boolean commitable, String blockMessage) {
        ExternalScoreVO.PreviewResult r = new ExternalScoreVO.PreviewResult();
        ExternalScoreVO.LineResult line = new ExternalScoreVO.LineResult();
        line.setLineNo(1);
        line.setStatus(commitable ? "PASS" : "BLOCK");
        line.setMessage(blockMessage);
        r.setLines(List.of(line));
        r.setCoverage(List.of());
        r.setCommitable(commitable);
        return r;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> draftRow(long id, String status, String ready, String seen) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("session_id", "s-1");
        m.put("year", 2026);
        m.put("dimension_id", 3L);
        m.put("status", status);
        m.put("payload_json", PAYLOAD);
        m.put("ready_time", ready);
        m.put("last_seen_time", seen);
        m.put("commit_time", null);
        return m;
    }
}
