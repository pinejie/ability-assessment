package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.ExternalScoreDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.ExternalScoreVO;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部评分导入服务单测：JdbcTemplate 全 mock（存储过程/原生 SQL 不在 H2 跑），
 * 按 SQL 特征分发假数据。覆盖：preview 校验矩阵、commit 分组与阻断、refill、clear。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalScoreServiceTest {

    private static final long DIM = 3L;
    private static final long SHEET_ID = 77L;

    @Mock
    private JdbcTemplate jdbc;

    private ExternalScoreService service;

    @BeforeEach
    void setUp() {
        service = new ExternalScoreService(jdbc, new ObjectMapper(), 1L);
        DimensionContext.set(DIM);
        LoginUser user = new LoginUser("tester", 900L, "测试员", 10L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        // 默认：仅 resource=100 一人有页面（进行中），页面非流程要素 11/12，已覆盖 11。
        // resource=200 查无此行 → 自然走"无测评页面"分支。
        when(jdbc.queryForList(contains("s.id AS sheet_id"), any(Object[].class)))
                .thenReturn(List.of(row("sheet_id", SHEET_ID, "resource_id", 100L,
                        "resource_name", "张三", "period_status", 0, "sheet_status", 1,
                        "actual_score", null, "grade_name", null, "annual_score", null)));
        when(jdbc.queryForList(contains("SELECT r.sheet_id, r.element_id FROM uf_assess_sheet_row"), any(Object[].class)))
                .thenReturn(List.of(
                        row("sheet_id", SHEET_ID, "element_id", 11L),
                        row("sheet_id", SHEET_ID, "element_id", 12L)));
        // 已覆盖外部要素（批量查询：sheet_id, element_id）
        when(jdbc.queryForList(contains("SELECT rt.sheet_id, rt.element_id FROM uf_assess_sheet_rating"),
                any(Object[].class))).thenReturn(List.of(row("sheet_id", SHEET_ID, "element_id", 11L)));
        // 等级分值配置的评价值集合（当前维度配了 5 级，评价 1..5 各对应一级）
        when(jdbc.queryForList(contains("SELECT rate_value FROM uf_assess_level_score"),
                eq(Integer.class), any(Long.class), any(Long.class)))
                .thenReturn(List.of(1, 2, 3, 4, 5));
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== preview ====================

    @Test
    @DisplayName("preview：合法行 PASS，覆盖摘要 x/y 正确")
    void previewPassesValidRows() {
        ExternalScoreVO.PreviewResult r = service.preview(ratings(
                rating(100L, 12L, 4, new BigDecimal("88"))));
        assertThat(r.isCommitable()).isTrue();
        assertThat(r.getLines()).hasSize(1);
        assertThat(r.getLines().get(0).getStatus()).isEqualTo("PASS");
        assertThat(r.getCoverage()).hasSize(1);
        assertThat(r.getCoverage().get(0).getExternalTotal()).isEqualTo(2);
        assertThat(r.getCoverage().get(0).getExternalCoveredAfter()).isEqualTo(2);
    }

    @Test
    @DisplayName("preview：流程要素/越界等级/重复行/无页面 各自 BLOCK，合法行 PASS")
    void previewBlocks() {
        var req = ratings(
                rating(100L, 99L, 3, null),   // 不在非流程白名单 → BLOCK
                rating(100L, 11L, 9, null),   // 评价值未配置（评价 1..5 之外）→ BLOCK
                rating(100L, 11L, 3, null),   // 合法首行 → PASS
                rating(100L, 11L, 2, null),   // 同人同要素重复 → BLOCK
                rating(200L, 11L, 3, null));  // 查无页面 → BLOCK
        ExternalScoreVO.PreviewResult r = service.preview(req);
        assertThat(r.isCommitable()).isFalse();
        assertThat(r.getLines()).extracting("status")
                .containsExactly("BLOCK", "BLOCK", "PASS", "BLOCK", "BLOCK");
        assertThat(r.getLines().get(0).getMessage()).contains("流程评价要素");
        assertThat(r.getLines().get(1).getMessage()).contains("对应的评价");
        assertThat(r.getLines().get(3).getMessage()).contains("重复");
        assertThat(r.getLines().get(4).getMessage()).contains("无测评页面");
    }

    @Test
    @DisplayName("preview：年度已关闭 → 全部 BLOCK")
    void previewBlocksClosedYear() {
        when(jdbc.queryForList(contains("s.id AS sheet_id"), any(Object[].class)))
                .thenReturn(List.of(row("sheet_id", SHEET_ID, "resource_id", 100L,
                        "resource_name", "张三", "period_status", 1, "sheet_status", 2)));
        ExternalScoreVO.PreviewResult r = service.preview(ratings(rating(100L, 11L, 3, null)));
        assertThat(r.getLines().get(0).getStatus()).isEqualTo("BLOCK");
        assertThat(r.getLines().get(0).getMessage()).contains("年度已关闭");
    }

    @Test
    @DisplayName("preview：年度分越界 BLOCK，合法 PASS")
    void previewAnnualRows() {
        var req = new ExternalScoreDTO.ImportRequest();
        req.setYear(2026);
        ExternalScoreDTO.AnnualRow bad = new ExternalScoreDTO.AnnualRow();
        bad.setResourceId(100L);
        bad.setAnnualScore(new BigDecimal("101"));
        ExternalScoreDTO.AnnualRow good = new ExternalScoreDTO.AnnualRow();
        good.setResourceId(100L);
        good.setAnnualScore(new BigDecimal("85.5"));
        req.setAnnuals(List.of(bad, good));

        ExternalScoreVO.PreviewResult r = service.preview(req);
        assertThat(r.getLines().get(0).getStatus()).isEqualTo("BLOCK");
        assertThat(r.getLines().get(1).getStatus()).isEqualTo("PASS");
    }

    // ==================== commit ====================

    @Test
    @DisplayName("commit：存在 BLOCK 时整体拒绝，不调用存储过程")
    void commitRejectsWhenBlocked() {
        when(jdbc.queryForList(contains("SELECT r.sheet_id, r.element_id FROM uf_assess_sheet_row"), any(Object[].class)))
                .thenReturn(List.of());  // 白名单为空 → 所有评级行 BLOCK

        assertThatThrownBy(() -> service.commit(ratings(rating(100L, 11L, 3, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已拒绝提交");
        verify(jdbc, never()).queryForList(contains("{call usp_assess_external_score"), any(Object[].class));
    }

    @Test
    @DisplayName("commit：通过后按人调 usp_assess_external_score（含 itemsJson、操作人、维度）")
    void commitCallsProcPerPerson() {
        stubProcCall();
        ExternalScoreVO.CommitResult result = service.commit(ratings(
                rating(100L, 11L, 3, new BigDecimal("65")),
                rating(100L, 12L, 4, null)));

        assertThat(result.getPersons()).hasSize(1);
        ExternalScoreVO.CommitPersonResult pr = result.getPersons().get(0);
        assertThat(pr.isSuccess()).isTrue();
        assertThat(pr.getSheetId()).isEqualTo(SHEET_ID);
        assertThat(pr.getGradeName()).isEqualTo("胜任");
        assertThat(pr.getMessage()).contains("覆盖 2/2");

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("{call usp_assess_external_score"), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args[0]).isEqualTo(1L);                       // company
        assertThat(args[2]).isEqualTo(100L);                     // resource
        assertThat(args[3]).isEqualTo(DIM);                      // dimension
        assertThat(args[4]).isEqualTo(900L);                     // operator（登录人）
        assertThat((String) args[5]).contains("\"elementId\":11").contains("\"originalScore\":65");
        assertThat(args[6]).isEqualTo("EXTERNAL_IMPORT");
    }

    @Test
    @DisplayName("commit：存储过程抛错只影响该人，返回失败信息（按人独立成败）")
    void commitPersonFailureIsolated() {
        when(jdbc.queryForList(contains("{call usp_assess_external_score"), any(Object[].class)))
                .thenThrow(new RuntimeException("SQLServerException: 年度已关闭"));
        ExternalScoreVO.CommitResult result = service.commit(ratings(rating(100L, 11L, 3, null)));
        assertThat(result.getPersons().get(0).isSuccess()).isFalse();
        assertThat(result.getPersons().get(0).getMessage()).isEqualTo("年度已关闭");
    }

    @Test
    @DisplayName("commit：未登录（无操作人）拒绝写入")
    void commitRequiresLogin() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.commit(ratings(rating(100L, 11L, 3, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录身份");
    }

    // ==================== refill / clear ====================

    @Test
    @DisplayName("commit：年度分调用带维度参数（sql/27 六参数，防跨维度误写）")
    void annualCommitCarriesDimension() {
        Map<String, Object> procRow = new HashMap<>();
        procRow.put("sheetId", SHEET_ID);
        procRow.put("annual_score", new BigDecimal("85.5"));
        when(jdbc.queryForList(contains("{call usp_assess_annual_score"), any(Object[].class)))
                .thenReturn(List.of(procRow));
        ExternalScoreDTO.ImportRequest req = new ExternalScoreDTO.ImportRequest();
        req.setYear(2026);
        ExternalScoreDTO.AnnualRow a = new ExternalScoreDTO.AnnualRow();
        a.setResourceId(100L);
        a.setAnnualScore(new BigDecimal("85.5"));
        req.setAnnuals(List.of(a));

        var result = service.commit(req);
        assertThat(result.getPersons().get(0).isSuccess()).isTrue();
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("{call usp_assess_annual_score"), captor.capture());
        assertThat(captor.getValue()).hasSize(6);
        assertThat(captor.getValue()[5]).isEqualTo(DIM);
    }

    @Test
    @DisplayName("refill：取最后成功稿，以 EXTERNAL_REFILL 重放存储过程")
    void refillReplaysLastDraft() {
        String lastDraft = "[{\"elementId\":11,\"levelNo\":4,\"originalScore\":88}]";
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(lastDraft));
        stubProcCall();

        var req = new ExternalScoreDTO.PersonRequest();
        req.setYear(2026);
        req.setResourceId(100L);
        ExternalScoreVO.CommitResult result = service.refill(req);
        assertThat(result.getPersons().get(0).isSuccess()).isTrue();
        assertThat(result.getPersons().get(0).getAction()).isEqualTo("REFILL");

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("{call usp_assess_external_score"), captor.capture());
        assertThat(captor.getValue()[5]).isEqualTo(lastDraft);
        assertThat(captor.getValue()[6]).isEqualTo("EXTERNAL_REFILL");
    }

    @Test
    @DisplayName("refill：无历史稿时明确报错")
    void refillWithoutHistory() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
        var req = new ExternalScoreDTO.PersonRequest();
        req.setYear(2026);
        req.setResourceId(100L);
        assertThatThrownBy(() -> service.refill(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有历史导入稿");
    }

    @Test
    @DisplayName("clear：调用 usp_assess_external_clear")
    void clearCallsProc() {
        when(jdbc.queryForList(contains("{call usp_assess_external_clear"), any(Object[].class)))
                .thenReturn(List.of(row("sheetId", SHEET_ID, "status", 1)));
        var req = new ExternalScoreDTO.PersonRequest();
        req.setYear(2026);
        req.setResourceId(100L);
        ExternalScoreVO.CommitResult result = service.clear(req);
        assertThat(result.getPersons().get(0).isSuccess()).isTrue();
        assertThat(result.getPersons().get(0).getMessage()).contains("已清空");
    }

    // ==================== 辅助 ====================

    private void stubProcCall() {
        Map<String, Object> procRow = new HashMap<>();
        procRow.put("sheetId", SHEET_ID);
        procRow.put("status", 2);
        procRow.put("actual_score", new BigDecimal("81.25"));
        procRow.put("grade_name", "胜任");
        procRow.put("externalTotal", 2);
        procRow.put("externalCovered", 2);
        when(jdbc.queryForList(contains("{call usp_assess_external_score"), any(Object[].class)))
                .thenReturn(List.of(procRow));
    }

    private static ExternalScoreDTO.ImportRequest ratings(ExternalScoreDTO.RatingRow... rows) {
        ExternalScoreDTO.ImportRequest req = new ExternalScoreDTO.ImportRequest();
        req.setYear(2026);
        req.setRatings(List.of(rows));
        return req;
    }

    private static ExternalScoreDTO.RatingRow rating(long res, long element, Integer level, BigDecimal original) {
        ExternalScoreDTO.RatingRow r = new ExternalScoreDTO.RatingRow();
        r.setResourceId(res);
        r.setElementId(element);
        r.setLevelNo(level);
        r.setOriginalScore(original);
        return r;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
