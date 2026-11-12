package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.AbilityStructureVO;
import com.company.ability.vo.ExternalScoreVO;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String STRUCTURE_PAYLOAD =
            "{\"kind\":\"ABILITY_STRUCTURE\",\"sessionId\":\"s-1\",\"dimensionId\":3," +
            "\"categories\":[{\"name\":\"安全环保\"}],\"elements\":[]}";

    private static final String PAYLOAD =
            "{\"sessionId\":\"s-1\",\"year\":2026,\"rows\":[{\"kind\":\"RATING\",\"resourceId\":224," +
            "\"elementId\":32,\"levelNo\":4,\"originalScore\":88}]}";

    @Mock private JdbcTemplate jdbc;
    @Mock private ExternalScoreService externalScoreService;
    @Mock private AssessDimensionService dimensionService;
    @Mock private com.company.ability.service.AdminService adminService;
    @Mock private AgentSessionService sessionService;
    @Mock private AbilityStructureImportService structureImportService;

    private AgentBridgeService service;

    @BeforeEach
    void setUp() {
        service = new AgentBridgeService(jdbc, new ObjectMapper(), externalScoreService,
                dimensionService, adminService, sessionService, structureImportService, true, TOKEN, 1L, 30, 2);
        LoginUser user = new LoginUser("tester", 900L, "测试员", 10L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(adminService.isAdmin(any())).thenReturn(false);
        when(dimensionService.isAuthorizedForUser(any(), any())).thenReturn(true);
        when(sessionService.isLiveSession(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        DimensionContext.clear();
    }

    // ==================== submitDraft ====================

    @Test
    @DisplayName("submitDraft：sessionId 格式合法但不是活会话 → 拒收（防任意会话投毒）")
    void deadSessionDraftRejected() {
        when(sessionService.isLiveSession(any())).thenReturn(false);
        assertThatThrownBy(() -> service.submitDraft(TOKEN, PAYLOAD, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("会话不存在或已结束");
    }

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
    @DisplayName("submitDraft：结构导入稿校验未过 → 返回行结果不落稿")
    void structureDraftBlockedNotPersisted() {
        AbilityStructureVO.PreviewResult blocked = new AbilityStructureVO.PreviewResult();
        AbilityStructureVO.Line line = new AbilityStructureVO.Line();
        line.setSection("ELEMENT");
        line.setName("风险辨识");
        line.setStatus("BLOCK");
        line.setMessage("缺少等级描述");
        blocked.setLines(List.of(line));
        blocked.setCounts(new AbilityStructureVO.Counts());
        blocked.setCommitable(false);
        when(structureImportService.preview(any(), any())).thenReturn(blocked);

        Map<String, Object> out = service.submitDraft(TOKEN, STRUCTURE_PAYLOAD, false);
        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("ready")).isEqualTo(false);
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
    }

    @Test
    @DisplayName("submitDraft：结构导入稿校验通过 → 落稿 kind=ABILITY_STRUCTURE，年度可空")
    void structureDraftPersisted() {
        AbilityStructureVO.PreviewResult ok = new AbilityStructureVO.PreviewResult();
        ok.setLines(List.of());
        ok.setCounts(new AbilityStructureVO.Counts());
        ok.setCommitable(true);
        ok.setYear(null);
        when(structureImportService.preview(any(), any())).thenReturn(ok);
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class))).thenAnswer(inv -> {
                    ((GeneratedKeyHolder) inv.getArgument(1)).getKeyList().add(Map.of("KEY", 77L));
                    return 1;
                });
        Map<String, Object> out = service.submitDraft(TOKEN, STRUCTURE_PAYLOAD, false);
        assertThat(out.get("draftId")).isEqualTo(77L);
        assertThat(out.get("ready")).isEqualTo(true);
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

    // ==================== deliver 行对账（skipped/coveredRows） ====================

    @Test
    @DisplayName("行对账：file_rows 未上报（旧会话/sql/29 未应用）→ 跳过对账，正常成稿")
    void reconcileSkippedWhenFileRowsUnknown() {
        stubSheet(); stubPreviewOk(); stubKeyHolder();
        when(jdbc.queryForObject(contains("file_rows"), eq(Integer.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(null);
        Map<String, Object> out = service.submitDraft(TOKEN, PAYLOAD, false);
        assertThat(out.get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("行对账：有行未交代去向 → 拒稿返回 reconciliation 指令，不落稿")
    void reconcileRejectsMissingRowFate() {
        stubSheet(); stubPreviewOk();
        stubFileRows(4); // 数据行物理行号 2..5
        String payload = PAYLOAD.replace("}]}",
                "}],\"coveredRows\":[2,3],\"skipped\":[{\"rowNo\":4,\"raw\":\"甲\",\"reason\":\"超范围\"}]}");
        Map<String, Object> out = service.submitDraft(TOKEN, payload, false);
        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(out.get("ready")).isEqualTo(false);
        assertThat(String.valueOf(out.get("reconciliation"))).contains("[5]");
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
    }

    @Test
    @DisplayName("行对账：coveredRows+skipped 覆盖全部数据行 → 成稿，skipped 随 payload 原样入稿")
    void reconcilePassPersistsSkipped() {
        stubSheet(); stubPreviewOk(); stubKeyHolder(); stubFileRows(4);
        String payload = PAYLOAD.replace("}]}",
                "}],\"coveredRows\":[2,3],\"skipped\":[{\"rowNo\":4,\"raw\":\"甲\",\"reason\":\"超范围\"},"
                        + "{\"rowNo\":5,\"raw\":\"乙\",\"reason\":\"查无此人\"}]}");
        Map<String, Object> out = service.submitDraft(TOKEN, payload, false);
        assertThat(out.get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("行对账：unreconciled 豁免标记（轮次耗尽强制成稿）→ 放行")
    void unreconciledFlagBypassesGate() {
        stubSheet(); stubPreviewOk(); stubKeyHolder(); stubFileRows(4);
        String payload = PAYLOAD.replace("}]}", "}],\"unreconciled\":true}");
        Map<String, Object> out = service.submitDraft(TOKEN, payload, false);
        assertThat(out.get("draftId")).isEqualTo(42L);
    }

    // ==================== 结构交付稿多 sheet 行对账 ====================

    @Test
    @DisplayName("结构对账：structure_file_stats 未上报（旧文件/未经 parse）→ 跳过对账，正常成稿")
    void structureReconcileSkippedWhenStatsUnknown() {
        stubStructureOk(); stubKeyHolder();
        when(jdbc.queryForObject(contains("structure_file_stats"), eq(String.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(null);
        assertThat(service.submitDraft(TOKEN, STRUCTURE_PAYLOAD, false).get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("结构对账：某表有行未交代去向 → 拒稿，提示行号须写「表名!行号」")
    void structureReconcileRejectsMissingRowFate() {
        stubStructureOk();
        stubStructureStats("[{\"name\":\"能力素质行为表现库\",\"firstLine\":3,\"lastLine\":5,\"rows\":3}]");
        String payload = STRUCTURE_PAYLOAD.replace("\"elements\":[]",
                "\"elements\":[],\"coveredRows\":[\"能力素质行为表现库!3\"],"
                        + "\"skipped\":[{\"rowNo\":\"能力素质行为表现库!4\",\"reason\":\"标题行\"}]");
        Map<String, Object> out = service.submitDraft(TOKEN, payload, false);
        assertThat(out.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(out.get("reconciliation")))
                .contains("能力素质行为表现库!5").contains("表名!行号");
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class));
    }

    @Test
    @DisplayName("结构对账：每张表每一行都交代（入稿+跳过）→ 成稿")
    void structureReconcilePass() {
        stubStructureOk(); stubKeyHolder();
        stubStructureStats("[{\"name\":\"行为表现库\",\"firstLine\":3,\"lastLine\":4,\"rows\":2}," +
                "{\"name\":\"模型表\",\"firstLine\":3,\"lastLine\":3,\"rows\":1}]");
        String payload = STRUCTURE_PAYLOAD.replace("\"elements\":[]",
                "\"elements\":[],\"coveredRows\":[\"行为表现库!3\",\"模型表!3\"],"
                        + "\"skipped\":[{\"rowNo\":\"行为表现库!4\",\"raw\":\"备注\",\"reason\":\"说明行\"}]");
        assertThat(service.submitDraft(TOKEN, payload, false).get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("结构对账：unreconciled 豁免标记 → 放行（轮次耗尽强制成稿）")
    void structureReconcileUnreconciledBypass() {
        stubStructureOk(); stubKeyHolder();
        stubStructureStats("[{\"name\":\"行为表现库\",\"firstLine\":3,\"lastLine\":9,\"rows\":7}]");
        String payload = STRUCTURE_PAYLOAD.replace("\"elements\":[]", "\"elements\":[],\"unreconciled\":true");
        assertThat(service.submitDraft(TOKEN, payload, false).get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("recordStructureStats：行块归一化后落库；越界行块/错误令牌一律拒绝")
    void recordStructureStatsWritesNormalized() {
        assertThatThrownBy(() -> service.recordStructureStats("nope", "s-1", new ObjectMapper().createArrayNode()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内部令牌无效");
        com.fasterxml.jackson.databind.node.ArrayNode bad = new ObjectMapper().createArrayNode();
        bad.addObject().put("name", "x").put("firstLine", 9).put("lastLine", 3).put("rows", 1);
        assertThatThrownBy(() -> service.recordStructureStats(TOKEN, "s-1", bad))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sheet 行块不合法");
        com.fasterxml.jackson.databind.node.ArrayNode good = new ObjectMapper().createArrayNode();
        good.addObject().put("name", "行为表现库").put("firstLine", 3).put("lastLine", 5).put("rows", 3);
        service.recordStructureStats(TOKEN, "s-1", good);
        verify(jdbc).update(contains("SET structure_file_stats"), contains("行为表现库"), eq("s-1"), eq(1L));
    }

    // ==================== 结构交付稿逐行预览（审核页） ====================

    @Test
    @DisplayName("previewStructureDraft：结构稿按当前维度重跑校验，返回行级状态与 counts")
    void previewStructureDraftRunsPreview() {
        when(jdbc.queryForList(contains("FROM uf_assess_agent_draft"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(AgentImportRunService.KIND_STRUCTURE, 3L, "READY")));
        AbilityStructureVO.PreviewResult ok = new AbilityStructureVO.PreviewResult();
        AbilityStructureVO.Line line = new AbilityStructureVO.Line();
        line.setSection("POSITION");
        line.setName("安环部 · 主任");
        line.setStatus("PENDING");
        line.setMessage("部门未匹配：安环部");
        ok.setLines(List.of(line));
        ok.setCounts(new AbilityStructureVO.Counts());
        ok.setCommitable(true);
        when(structureImportService.preview(any(), any())).thenReturn(ok);

        Map<String, Object> out = service.previewStructureDraft(7L);

        assertThat(out.get("draftId")).isEqualTo(7L);
        assertThat(out.get("status")).isEqualTo("READY");
        assertThat(out.get("commitable")).isEqualTo(true);
        assertThat(String.valueOf(out.get("lines"))).contains("安环部 · 主任");
    }

    @Test
    @DisplayName("previewStructureDraft：评分稿/无权维度/不存在 分别 400/403/404")
    void previewStructureDraftGuards() {
        when(jdbc.queryForList(contains("FROM uf_assess_agent_draft"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.previewStructureDraft(7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("交付稿不存在");

        when(jdbc.queryForList(contains("FROM uf_assess_agent_draft"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(AgentImportRunService.KIND_EXTERNAL, 3L, "READY")));
        assertThatThrownBy(() -> service.previewStructureDraft(7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有能力结构交付稿");

        when(jdbc.queryForList(contains("FROM uf_assess_agent_draft"),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(AgentImportRunService.KIND_STRUCTURE, 9L, "READY")));
        when(dimensionService.isAuthorizedForUser(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.previewStructureDraft(7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权操作");
    }

    /** 交付稿查询结果行（previewStructureDraft 只读这几列） */
    private Map<String, Object> draftRow(String kind, long dimensionId, String status) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("kind", kind);
        row.put("dimension_id", dimensionId);
        row.put("status", status);
        row.put("payload_json", STRUCTURE_PAYLOAD);
        return row;
    }

    private void stubStructureOk() {
        AbilityStructureVO.PreviewResult ok = new AbilityStructureVO.PreviewResult();
        ok.setLines(List.of());
        ok.setCounts(new AbilityStructureVO.Counts());
        ok.setCommitable(true);
        when(structureImportService.preview(any(), any())).thenReturn(ok);
    }

    private void stubStructureStats(String json) {
        when(jdbc.queryForObject(contains("structure_file_stats"), eq(String.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(json);
    }

    // ==================== 批次维度硬闸 ====================

    @Test
    @DisplayName("submitDraft：交付维度与批次发起维度不一致 → 409 拒收（权限再多也只认当前维度）")
    void dimensionMismatchAgainstBatchRejected() {
        stubSheet(); // 解析出的维度=3
        when(jdbc.queryForObject(contains("uf_assess_import_run"), eq(Long.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(9L);
        assertThatThrownBy(() -> service.submitDraft(TOKEN, PAYLOAD, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("维度").hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("submitDraft：批次维度一致放行；无批次记录（聊天通道）不设闸")
    void dimensionGatePassWhenMatchedOrAbsent() {
        stubSheet(); stubPreviewOk(); stubKeyHolder();
        when(jdbc.queryForObject(contains("uf_assess_import_run"), eq(Long.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(3L);
        assertThat(service.submitDraft(TOKEN, PAYLOAD, false).get("draftId")).isEqualTo(42L);

        org.mockito.Mockito.reset(jdbc);
        stubSheet(); stubPreviewOk(); stubKeyHolder();   // queryForObject 未桩 → null → 放行
        assertThat(service.submitDraft(TOKEN, PAYLOAD, false).get("draftId")).isEqualTo(42L);
    }

    @Test
    @DisplayName("recordFileStats：令牌正确才写 file_rows；错误令牌 401")
    void recordFileStatsTokenGate() {
        assertThatThrownBy(() -> service.recordFileStats("nope", "s-1", 4))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内部令牌无效");
        service.recordFileStats(TOKEN, "s-1", 4);
        verify(jdbc).update(contains("SET file_rows"), eq(4), eq("s-1"), eq(1L));
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
    @DisplayName("commitDraft：登录人无稿内维度授权 → 403（越权门）")
    void crossDimensionCommitRejected() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(9L, "READY", now, now)));
        when(dimensionService.isAuthorizedForUser(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.commitDraft(9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        verify(externalScoreService, never()).commit(any());
    }

    @Test
    @DisplayName("commitDraft：结构导入稿 → 走结构服务写入，不触碰评分服务（年份可空）")
    void structureDraftCommitRejected() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> structure = draftRow(31L, "READY", now, now);
        structure.put("kind", "ABILITY_STRUCTURE");
        structure.put("year", null);
        structure.put("payload_json", STRUCTURE_PAYLOAD);
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(structure));
        AbilityStructureVO.CommitResult result = new AbilityStructureVO.CommitResult();
        result.setCounts(new AbilityStructureVO.Counts());
        result.setFailed(List.of());
        when(structureImportService.commit(any(), any(), any())).thenReturn(result);
        Map<String, Object> out = service.commitDraft(31L);
        assertThat(out.get("rejected")).isEqualTo(false);
        verify(externalScoreService, never()).preview(any());
        verify(externalScoreService, never()).commit(any());
    }

    @Test
    @DisplayName("commitDraft：部分人失败时保持 READY 可重试（upsert 幂等补写失败者）")
    void partialFailureKeepsReady() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        when(jdbc.queryForList(contains("WHERE id = ? AND company_id"), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any()))
                .thenReturn(List.of(draftRow(8L, "READY", now, now)));
        when(externalScoreService.preview(any())).thenReturn(preview(true, null));
        ExternalScoreVO.CommitResult mixed = new ExternalScoreVO.CommitResult();
        ExternalScoreVO.CommitPersonResult p1 = new ExternalScoreVO.CommitPersonResult();
        p1.setResourceId(224L);
        p1.setSuccess(true);
        ExternalScoreVO.CommitPersonResult p2 = new ExternalScoreVO.CommitPersonResult();
        p2.setResourceId(225L);
        p2.setSuccess(false);
        p2.setMessage("年度已关闭");
        mixed.setPersons(List.of(p1, p2));
        when(externalScoreService.commit(any())).thenReturn(mixed);

        service.commitDraft(8L);
        ArgumentCaptor<Object> statusCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("SET status=?"), statusCap.capture(),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any());
        assertThat(statusCap.getValue()).isEqualTo("READY");
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

    private void stubPreviewOk() {
        when(externalScoreService.preview(any())).thenReturn(preview(true, null));
    }

    private void stubKeyHolder() {
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(GeneratedKeyHolder.class))).thenAnswer(inv -> {
                    ((GeneratedKeyHolder) inv.getArgument(1)).getKeyList().add(Map.of("KEY", 42L));
                    return 1;
                });
    }

    private void stubFileRows(int rows) {
        when(jdbc.queryForObject(contains("file_rows"), eq(Integer.class),
                ArgumentMatchers.<Object>any(), ArgumentMatchers.<Object>any())).thenReturn(rows);
    }

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
        m.put("kind", "EXTERNAL_SCORE");
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
