package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportPreview;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.exception.BusinessException;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.mapper.PaItemLevelMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「等级不足 2 档」必须挡在导入门口。
 *
 * <p>2026-11-22 实测发现的洞：等级类问题原来被 {@code resolved} 一票放行 ——
 * 用户在对话里随便回一句「就这样吧」，槽位就算解决、问题消失、预览无阻断，
 * 最终 1 档能力项原样写进库。后果不报错：评价人填表时该项只有一个选项，
 * 这份评价对区分度毫无贡献，而报表照常出分，没人会察觉。</p>
 *
 * <p>三处都要拦住，因为任何一处单独失效都够漏：</p>
 * <ol>
 *   <li>问题构造器不再认 {@code resolved}（草稿没变，问题就得还在）</li>
 *   <li>回答链路明确拒收等级类回答（并说清该去改表格）</li>
 *   <li>预览补出真正的阻断项 —— 写库那道闸门原来读的 {@code blockers} 从来没被填过</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("导入：等级不足 2 档必须拦住")
class PaImportLevelGuardTest {

    private static final long DIM = 1L;
    private static final long DEPT = 605L;

    @Autowired
    private PaImportService importService;
    @Autowired
    private PaImportApplier applier;
    @Autowired
    private PaImportPlanner planner;
    @Autowired
    private PaItemLevelMapper levelMapper;
    @Autowired
    private com.company.ability.mapper.PaImportSessionMapper sessionMapper;
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
                " VALUES (?, ?, ?, '0')", DEPT, "探针部", "探针部");
        DimensionContext.set(DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    @Test
    @DisplayName("回答「就这样吧」不再销掉等级问题：问题仍在，且草稿没被改动")
    void answeringCannotSilenceLevelQuestion() {
        PaImportDraft draft = draftWithLevels(1);
        String slot = slotOf(draft);

        assertTrue(hasLevelBlocking(draft, Map.of()), "1 档能力项必须报阻断项");

        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> problems = importService.applyAnswers(draft, resolved,
                Map.of(slot, "就这样吧"), questions(draft, Map.of()));

        assertNotNull(problems.get(slot), "等级类回答必须被拒收，并告诉人去改表格");
        assertTrue(problems.get(slot).contains("至少"), "提示要说清至少几档：" + problems.get(slot));
        assertTrue(!resolved.containsKey(slot), "不能记成已解决 —— 记了就会一票放行");
        assertEquals(1, draft.getPosts().get(0).getItems().get(0).getLevels().size(), "草稿不该被改动");

        assertTrue(hasLevelBlocking(draft, resolved),
                "即使 resolved 里被塞了这个 slot（比如历史会话里存过），问题也必须还在");
    }

    @Test
    @DisplayName("同岗位两个能力项编码重复时，等级问题各有各的槽位（不会共用一槽）")
    void duplicateCodesGetDistinctSlots() {
        PaImportDraft draft = draftWithLevels(1);
        // 模板的正常写法：专业能力段和素养段都从 E01 起
        PaImportDraft.ItemDraft second = new PaImportDraft.ItemDraft();
        second.setItemCode("E01");
        second.setItemName("抗压能力");
        second.setCategoryName("专业能力");
        second.setWeight(BigDecimal.ZERO);
        second.setRaterLeader(1);
        second.setRater360(0);
        second.setSort(1);
        PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
        lv.setLevelName("唯一档");
        lv.setScore(BigDecimal.ONE);
        lv.setSort(0);
        second.getLevels().add(lv);
        draft.getPosts().get(0).getItems().get(0).setItemCode("E01");
        draft.getPosts().get(0).getItems().add(second);

        String slotFirst = PaImportQuestionBuilder.slotLevels(draft.getPosts().get(0),
                draft.getPosts().get(0).getItems().get(0));
        String slotSecond = PaImportQuestionBuilder.slotLevels(draft.getPosts().get(0), second);
        assertNotEquals(slotFirst, slotSecond,
                "槽位只按编码拼的话，两个 E01 会共用一槽 —— 用户答一问、两问一起变");

        // 两个都要各报一条阻断，不能因为槽位相同被 renumber 去重掉一条
        long blocking = questions(draft, Map.of()).stream()
                .filter(PaImportQuestion::isBlocking)
                .filter(q -> q.getSlot().equals(slotFirst) || q.getSlot().equals(slotSecond))
                .count();
        assertEquals(2, blocking, "两个 1 档能力项各要一条阻断");
    }

    @Test
    @DisplayName("预览补出阻断项：写库前的闸门这次真的有内容")
    void previewCarriesBlocker() {
        PaImportDraft draft = draftWithLevels(1);
        PaImportPreview preview = planner.preview(draft, new PaImportPlan());

        assertTrue(preview.getBlockers().stream().anyMatch(
                        b -> slotOf(draft).equals(b.getSlot()) && b.isBlocking()),
                "1 档能力项必须在 blockers 里出现，否则 runApply 那道检查永远不成立");
    }

    @Test
    @DisplayName("2 档能力项不受影响：无阻断、能正常写库")
    void twoLevelsPassesCleanly() {
        PaImportDraft draft = draftWithLevels(2);
        PaImportPreview preview = planner.preview(draft, new PaImportPlan());

        assertTrue(preview.getBlockers().isEmpty(),
                "2 档是合规下限，不能误伤：" + preview.getBlockers().stream()
                        .map(PaImportQuestion::getTopic).toList());
        assertTrue(!hasLevelBlocking(draft, Map.of()), "2 档不该再报问题");

        applier.apply(draft, new PaImportPlan(), preview);
        Long itemId = itemIdOf(draft);
        assertEquals(2, levelMapper.selectCount(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, itemId)), "两档都要落库");
    }

    @Test
    @DisplayName("分值重复同样销不掉：重复档位等于少一档")
    void duplicateScoresCannotBeSilenced() {
        PaImportDraft draft = draftWithLevels(2);
        PaImportDraft.ItemDraft item = draft.getPosts().get(0).getItems().get(0);
        item.getLevels().get(1).setScore(item.getLevels().get(0).getScore());

        Map<String, String> resolved = Map.of(slotOf(draft), "知道了");
        assertTrue(hasLevelBlocking(draft, resolved), "两档同分必须报出来，且不能被 resolved 销掉");
    }

    @Test
    @DisplayName("只剩等级问题时直接转人工：不让人白答几轮再撞上限")
    void unsolvableBlockersGoManualAtOnce() {
        PaImportDraft draft = draftWithLevels(1);
        com.company.ability.entity.PaImportSession session = newSession();

        importService.finishRound(session, draft, questions(draft, Map.of()),
                new LinkedHashMap<>(), null);

        com.company.ability.entity.PaImportSession reloaded = sessionMapper.selectById(session.getId());
        assertEquals(com.company.ability.entity.PaImportStatus.MANUAL, reloaded.getStatus(),
                "等级问题对话里解决不了，继续追问只是浪费人的时间");
        assertTrue(reloaded.getError() != null && reloaded.getError().contains("档"),
                "要说清该回表格补档：" + reloaded.getError());
        assertEquals(0, reloaded.getRoundNo() == null ? 0 : reloaded.getRoundNo(),
                "不该推进轮次 —— 根本没问出去");
    }

    @Test
    @DisplayName("还剩能在对话里解决的问题时，照常追问，不误转人工")
    void solvableBlockersStillAsk() {
        PaImportDraft draft = draftWithLevels(2);
        // 造一个对话能解决的问题：评级主体认不出来
        draft.getPosts().get(0).getItems().get(0).setRaterLeader(-1);
        com.company.ability.entity.PaImportSession session = newSession();

        importService.finishRound(session, draft, questions(draft, Map.of()),
                new LinkedHashMap<>(), null);

        com.company.ability.entity.PaImportSession reloaded = sessionMapper.selectById(session.getId());
        assertEquals(com.company.ability.entity.PaImportStatus.NEEDS_CONFIRM, reloaded.getStatus(),
                "评级主体这类问题本来就该问，不能一刀切转人工");
    }

    @Test
    @DisplayName("写库闸门真的生效：状态就算被推到 READY，1 档草稿也写不进去")
    void applyRefusesOneLevelDraft() {
        PaImportDraft draft = draftWithLevels(1);
        com.company.ability.entity.PaImportSession session = newSession();
        // 直接构造「状态机已放行」的现场，绕开前面所有拦截，只验最后一道墙。
        // 这正是修复前会自然出现的情形：问题被一句话销掉 → 进 READY → 直接写库。
        importService.saveDraft(session, draft);
        jdbc.update("UPDATE uf_pa_import_session SET status = ? WHERE id = ?",
                com.company.ability.entity.PaImportStatus.READY, session.getId());

        importService.runApply(session.getSessionKey());

        com.company.ability.entity.PaImportSession reloaded = sessionMapper.selectById(session.getId());
        assertEquals(com.company.ability.entity.PaImportStatus.FAILED, reloaded.getStatus(),
                "写库前的闸门必须拦住它");
        assertTrue(reloaded.getError() != null && reloaded.getError().contains("不能导入"),
                "要说明为什么不能导入：" + reloaded.getError());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_pa_post WHERE post_name = ? AND deleted = 0",
                Integer.class, "探针岗"), "一行都不该写进库");
    }

    // ------------------------------------------------------------------ 造数据

    private String slotOf(PaImportDraft draft) {
        return PaImportQuestionBuilder.slotLevels(draft.getPosts().get(0),
                draft.getPosts().get(0).getItems().get(0));
    }

    private List<PaImportQuestion> questions(PaImportDraft draft, Map<String, String> resolved) {
        return new PaImportQuestionBuilder().build(draft, resolved);
    }

    private boolean hasLevelBlocking(PaImportDraft draft, Map<String, String> resolved) {
        String slot = slotOf(draft);
        return questions(draft, resolved).stream()
                .anyMatch(q -> slot.equals(q.getSlot()) && q.isBlocking());
    }

    private Long itemIdOf(PaImportDraft draft) {
        return jdbc.queryForObject(
                "SELECT i.id FROM uf_pa_item i JOIN uf_pa_post p ON p.id = i.post_id" +
                        " WHERE p.post_name = ? AND i.deleted = 0",
                Long.class, draft.getPosts().get(0).getPostName());
    }

    private com.company.ability.entity.PaImportSession newSession() {
        com.company.ability.entity.PaImportSession session = new com.company.ability.entity.PaImportSession();
        session.setSessionKey(java.util.UUID.randomUUID().toString());
        session.setDimensionId(DIM);
        session.setResourceId(901L);
        session.setStatus(com.company.ability.entity.PaImportStatus.PARSING);
        session.setFileName("test.xlsx");
        session.setFileSize(1024L);
        session.setRoundNo(0);
        session.setProgress(50);
        session.setExpireTime(java.time.LocalDateTime.now().plusMinutes(30));
        sessionMapper.insert(session);
        return session;
    }

    private PaImportDraft draftWithLevels(int levelCount) {
        PaImportDraft draft = new PaImportDraft();
        PaImportDraft.PostDraft post = new PaImportDraft.PostDraft();
        post.setSheetName("探针岗");
        post.setPostName("探针岗");
        post.setDepartmentId(DEPT);
        post.setDepartmentName("探针部");
        post.setDepartmentFromFile(true);

        PaImportDraft.CategoryDraft cat = new PaImportDraft.CategoryDraft();
        cat.setCategoryName("专业能力");
        cat.setSort(0);
        post.getCategories().add(cat);

        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setItemCode("P1");
        item.setItemName("探针能力");
        item.setCategoryName("专业能力");
        item.setWeight(new BigDecimal("100"));
        item.setRaterLeader(1);
        item.setRater360(0);
        item.setSort(0);
        for (int i = 1; i <= levelCount; i++) {
            PaImportDraft.LevelDraft lv = new PaImportDraft.LevelDraft();
            lv.setLevelName("档" + i);
            lv.setScore(new BigDecimal(i));
            lv.setSort(i - 1);
            item.getLevels().add(lv);
        }
        post.getItems().add(item);

        PaImportDraft.BandDraft band = new PaImportDraft.BandDraft();
        band.setBandName("唯一档");
        band.setLowerBound(new BigDecimal("1"));
        band.setUpperBound(null);
        band.setSort(0);
        post.getBands().add(band);

        draft.getPosts().add(post);
        return draft;
    }
}
