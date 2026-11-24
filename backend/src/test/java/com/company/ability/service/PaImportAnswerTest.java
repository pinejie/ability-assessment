package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.entity.PaImportSession;
import com.company.ability.entity.PaImportStatus;
import com.company.ability.mapper.PaImportSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回答链路的「部门落值」测试。
 *
 * <p>这条链路有个只有真实跑一遍才会暴露的坑（2026-11-22 实测）：用户在页面上用部门选择器
 * 选了部门，问题算解决了，但草稿里每个岗位的 {@code departmentId} 仍是空 ——
 * 预览的部门列空着，写库会建出无部门岗位。根因是回答只写进了 resolved、没回填草稿，
 * 而且改过的草稿也没有落库。这里把两步都锁住。</p>
 *
 * <p>直接测 {@code applyAnswers} + {@code saveDraft}，不走 {@code runAnswer}：
 * 后者中间要过一次模型抽值，单测里没法稳定复现，而「回填 + 落库」正是最容易漏的部分。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力结构导入回答链路测试")
class PaImportAnswerTest {

    private static final long DIM = 1L;
    private static final long DEPT = 602L;
    private static final long RESOURCE = 902L;

    @Autowired
    private PaImportService importService;
    @Autowired
    private PaImportSessionMapper sessionMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        for (String t : List.of("uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet", "uf_assess_task_rater",
                "uf_assess_task", "uf_assess_period", "uf_pa_band", "uf_pa_item_level", "uf_pa_item",
                "uf_pa_category", "uf_pa_post", "uf_pa_import_session", "HrmResource", "HrmDepartment")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (?, ?, ?, '0')", DEPT, "数字化推进办公室", "数字化推进办公室");
        DimensionContext.set(DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    @Test
    @DisplayName("文件里没写部门 → 必问；回答后每个岗位都带上部门")
    void departmentAnswerFillsEveryPost() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        List<PaImportQuestion> before = new PaImportQuestionBuilder().build(draft, PaImportQuestionBuilder.none());
        assertTrue(before.stream().anyMatch(q -> "dept".equals(q.getSlot()) && q.isBlocking()),
                "文件没有部门时必须有 dept 阻断项");

        Map<String, String> problems = importService.applyAnswers(draft, new LinkedHashMap<>(),
                Map.of("dept", "数字化推进办公室"), before);
        assertTrue(problems.isEmpty(), "唯一匹配的部门名应该能采用：" + problems);

        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            assertEquals(DEPT, post.getDepartmentId(), "「" + post.getPostName() + "」要回填部门 id");
            assertEquals("数字化推进办公室", post.getDepartmentName(), "部门名快照也要带上");
        }

        // 再算一次问题：部门问题必须消失（否则用户会一直被问同一件事）
        List<PaImportQuestion> after = new PaImportQuestionBuilder()
                .build(draft, Map.of("dept", "数字化推进办公室#" + DEPT));
        assertTrue(after.stream().noneMatch(q -> "dept".equals(q.getSlot())), "部门已确认后不应再问部门");
    }

    @Test
    @DisplayName("重名部门不猜：找不到唯一匹配就报问题，不落值")
    void ambiguousDepartmentIsRejected() {
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (?, ?, ?, '0')", 603L, "综合办公室", "综合办公室");
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled)" +
                " VALUES (?, ?, ?, '0')", 604L, "综合办公室", "综合办公室B");

        PaImportDraft draft = twoPostsWithoutDepartment();
        List<PaImportQuestion> asked = new PaImportQuestionBuilder()
                .build(draft, PaImportQuestionBuilder.none());

        Map<String, String> problems = importService.applyAnswers(draft, new LinkedHashMap<>(),
                Map.of("dept", "综合办公室"), asked);

        assertNotNull(problems.get("dept"), "两个同名部门时必须报没法采用");
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            assertNull(post.getDepartmentId(), "猜不出部门时不能随手填一个");
        }
    }

    @Test
    @DisplayName("回答后改过的草稿要落库：重新查会话读回来仍带部门")
    void answeredDraftIsPersisted() {
        PaImportSession session = newSession();
        PaImportDraft draft = twoPostsWithoutDepartment();
        importService.applyAnswers(draft, new LinkedHashMap<>(), Map.of("dept", "数字化推进办公室"),
                new PaImportQuestionBuilder().build(draft, PaImportQuestionBuilder.none()));

        importService.saveDraft(session, draft);

        PaImportSession reloaded = sessionMapper.selectOne(new LambdaQueryWrapper<PaImportSession>()
                .eq(PaImportSession::getSessionKey, session.getSessionKey()));
        assertNotNull(reloaded, "会话应该还在");
        for (PaImportDraft.PostDraft post : importService.readDraft(reloaded).getPosts()) {
            assertEquals(DEPT, post.getDepartmentId(),
                    "落库的草稿要带部门，否则写库时会建出无部门岗位");
        }
    }

    @Test
    @DisplayName("答「全公司通用」→ 岗位不绑部门，且不再反复问部门")
    void genericDepartmentAnswerClearsDepartment() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        List<PaImportQuestion> before = new PaImportQuestionBuilder().build(draft, PaImportQuestionBuilder.none());

        Map<String, String> problems = importService.applyAnswers(draft, new LinkedHashMap<>(),
                Map.of("dept", PaImportQuestionBuilder.GENERIC_DEPARTMENT), before);
        assertTrue(problems.isEmpty(), "通用是合法答案，不该报错：" + problems);

        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            assertNull(post.getDepartmentId(), "通用岗位的部门必须是空（sql/63：NULL = 全公司通用）");
            assertNull(post.getDepartmentName(), "通用岗位不该留部门名快照");
        }

        List<PaImportQuestion> after = new PaImportQuestionBuilder()
                .build(draft, Map.of("dept", PaImportQuestionBuilder.GENERIC_DEPARTMENT));
        assertTrue(after.stream().noneMatch(q -> "dept".equals(q.getSlot())), "通用已确认后不应再问部门");
    }

    @Test
    @DisplayName("先选部门再改通用：上一轮的部门要被清掉，不能留着")
    void genericAnswerOverridesPickedDepartment() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        Map<String, String> resolved = new LinkedHashMap<>();
        importService.applyAnswers(draft, resolved, Map.of("dept", "数字化推进办公室"),
                new PaImportQuestionBuilder().build(draft, PaImportQuestionBuilder.none()));
        assertEquals(DEPT, draft.getPosts().get(0).getDepartmentId(), "先确认部门");

        importService.applyAnswers(draft, resolved,
                Map.of("dept", PaImportQuestionBuilder.GENERIC_DEPARTMENT),
                new PaImportQuestionBuilder().build(draft, resolved));

        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            assertNull(post.getDepartmentId(), "改成通用后不能还挂着旧部门");
            assertNull(post.getDepartmentName(), "部门名快照也要清掉");
        }
    }

    @Test
    @DisplayName("部门补全不再把「文件里读到部门名」当已完成：没 id 就还得问")
    void departmentNameFromFileStillAsksForId() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        // 模拟装配器：从 Excel 里读到「质量部」这个字符串，但没有对应到 HrmDepartment.id
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            post.setDepartmentFromFile(true);
            post.setDepartmentName("质量部");
        }

        List<PaImportQuestion> questions = new PaImportQuestionBuilder()
                .build(draft, PaImportQuestionBuilder.none());
        assertTrue(questions.stream().anyMatch(q -> "dept".equals(q.getSlot()) && q.isBlocking()),
                "只有部门名没有 id 时仍必须问 —— 否则写库拿文字去建岗位");
    }

    @Test
    @DisplayName("文件里写了部门名且组织树里有唯一匹配 → 自动解析成 id，不再问人")
    void departmentNameFromFileResolvesWhenUnique() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            post.setDepartmentFromFile(true);
            post.setDepartmentName("数字化推进办公室");
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        importService.resolveFileDepartments(draft, resolved);

        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            assertEquals(DEPT, post.getDepartmentId(), "唯一匹配时要能自动落到部门 id");
        }
        assertTrue(resolved.containsKey("dept"), "解析成功后要写进 resolved，否则还会再问一次");
        assertTrue(new PaImportQuestionBuilder().build(draft, resolved).stream()
                        .noneMatch(q -> "dept".equals(q.getSlot())),
                "已经解析出部门就不该再问");
    }

    @Test
    @DisplayName("「全公司通用」不进候选白名单（选了部门被顶成通用的根因）")
    void genericIsNotOfferedAsCandidate() {
        PaImportDraft draft = twoPostsWithoutDepartment();
        PaImportQuestion dept = new PaImportQuestionBuilder()
                .build(draft, PaImportQuestionBuilder.none()).stream()
                .filter(q -> "dept".equals(q.getSlot())).findFirst().orElseThrow();

        // candidates 是模型挑 suggestion 的白名单（PaImportDialogAi.isAmongCandidates）。
        // 2026-11-23 线上：它等于 ["全公司通用"]，模型据此回「全公司通用」，
        // 前端预填进隐形的 answers['dept']，用户选了部门也被这个值覆盖。
        assertFalse(dept.getCandidates().contains(PaImportQuestionBuilder.GENERIC_DEPARTMENT),
                "「全公司通用」不能进 candidates 白名单");
        assertEquals(Boolean.TRUE, dept.getContext().get("genericOption"),
                "要用独立的上下文标记告诉前端渲染通用勾选项");
    }

    // ------------------------------------------------------------------ 造数据

    private PaImportDraft twoPostsWithoutDepartment() {
        PaImportDraft draft = new PaImportDraft();
        draft.getPosts().add(post("技术人员", "T1", "技术能力"));
        draft.getPosts().add(post("信息化管理人员", "I1", "信息化能力"));
        return draft;
    }

    private PaImportDraft.PostDraft post(String name, String code, String category) {
        PaImportDraft.PostDraft post = new PaImportDraft.PostDraft();
        post.setSheetName(name);
        post.setPostName(name);
        // 刻意不设 departmentId / departmentName：模拟真实文件里没有部门
        // departmentFromFile 默认 false，正是「文件里没写」的口径

        PaImportDraft.CategoryDraft cat = new PaImportDraft.CategoryDraft();
        cat.setCategoryName(category);
        cat.setSort(0);
        post.getCategories().add(cat);

        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setItemCode(code);
        item.setItemName(category);
        item.setCategoryName(category);
        item.setWeight(new BigDecimal("100"));
        item.setRaterLeader(1);
        item.setRater360(0);
        item.setSort(0);
        for (int i = 1; i <= 2; i++) {
            PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
            lv.setLevelName("档" + i);
            lv.setScore(new BigDecimal(i));
            lv.setSort(i - 1);
            item.getLevels().add(lv);
        }
        post.getItems().add(item);

        PaImportDraft.BandDraft band1 = new PaImportDraft.BandDraft();
        band1.setBandName("档1");
        band1.setLowerBound(new BigDecimal("1"));
        band1.setUpperBound(new BigDecimal("2"));
        band1.setSort(0);
        post.getBands().add(band1);
        PaImportDraft.BandDraft band2 = new PaImportDraft.BandDraft();
        band2.setBandName("档2");
        band2.setLowerBound(new BigDecimal("2"));
        band2.setUpperBound(null);
        band2.setSort(1);
        post.getBands().add(band2);
        return post;
    }

    private PaImportSession newSession() {
        PaImportSession session = new PaImportSession();
        session.setSessionKey(UUID.randomUUID().toString());
        session.setDimensionId(DIM);
        session.setResourceId(RESOURCE);
        session.setStatus(PaImportStatus.NEEDS_CONFIRM);
        session.setFileName("test.xlsx");
        session.setFileSize(1024L);
        session.setRoundNo(0);
        session.setProgress(50);
        // expire_time 是 NOT NULL：真实会话由服务端按 TTL 算，这里直接给个未来时间
        session.setExpireTime(java.time.LocalDateTime.now().plusMinutes(30));
        sessionMapper.insert(session);
        return session;
    }
}
