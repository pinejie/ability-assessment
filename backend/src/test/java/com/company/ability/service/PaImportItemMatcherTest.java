package com.company.ability.service;

import com.company.ability.dto.PaImportDraft;
import com.company.ability.entity.PaItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力项匹配规则的纯单测（不起 Spring，不碰库）。
 *
 * <p>编码允许重复（sql/65）之后，「哪一项对哪一行」全靠这里，规则错了不会报错、
 * 只会静默改错行或删错行 —— 所以把三级匹配和「命中即消耗」逐条钉住。</p>
 */
class PaImportItemMatcherTest {

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("第一级：编码和名称都相同 → 命中")
    void exactCodeAndNameWins() {
        PaItem old = item(1L, "E01", "服务意识");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(old));

        assertSame(old, matcher.match(draft("E01", "服务意识")));
        assertTrue(matcher.remaining().isEmpty());
    }

    @Test
    @DisplayName("第二级：编码对上但名称改了 → 仍认作同一项（业务常给能力项改措辞）")
    void codeOnlyStillMatches() {
        PaItem old = item(1L, "Q1", "检验能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(old));

        assertSame(old, matcher.match(draft("Q1", "检验与试验能力")),
                "编码稳定、名称常被微调，编码命中就该原地更新，不能删了重建");
    }

    @Test
    @DisplayName("第三级：编码变了但名称没变 → 按名称认回来")
    void nameOnlyStillMatches() {
        PaItem old = item(1L, "Q1", "检验能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(old));

        assertSame(old, matcher.match(draft("Q9", "检验能力")));
    }

    @Test
    @DisplayName("编码重复时按名称分辨：两个 E01 各自对上自己那行，不会抢同一行")
    void duplicateCodesDisambiguatedByName() {
        PaItem service = item(1L, "E01", "服务意识");
        PaItem stress = item(2L, "E01", "抗压能力");
        // 故意把「抗压能力」放前面：若实现按队列顺序瞎给，第一项就会抢走它
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(stress, service));

        assertSame(service, matcher.match(draft("E01", "服务意识")));
        assertSame(stress, matcher.match(draft("E01", "抗压能力")));
        assertTrue(matcher.remaining().isEmpty(), "两项都被认领，没有多余的");
    }

    @Test
    @DisplayName("命中即消耗：库里两个同编码同名的历史脏数据，两个草稿项各认一个")
    void duplicateRowsAreConsumedOneByOne() {
        PaItem a = item(1L, "E01", "抗压能力");
        PaItem b = item(2L, "E01", "抗压能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(a, b));

        PaItem first = matcher.match(draft("E01", "抗压能力"));
        PaItem second = matcher.match(draft("E01", "抗压能力"));
        assertEquals(2, List.of(first, second).stream().distinct().count(), "不能两次都给同一行");
        assertTrue(matcher.remaining().isEmpty());
    }

    @Test
    @DisplayName("编码对不上、名称也对不上 → 新增")
    void noMatchMeansNew() {
        PaItem old = item(1L, "Q1", "检验能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(old));

        assertNull(matcher.match(draft("E01", "服务意识")));
        assertEquals(List.of(old), matcher.remaining(), "没被认领的旧项 = 文件里没有的，要停用或删除");
    }

    @Test
    @DisplayName("没有编码的旧项不参与编码匹配，只能按名称认")
    void blankCodeMatchesByNameOnly() {
        PaItem old = item(1L, null, "检验能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(old));

        assertSame(old, matcher.match(draft("Q1", "检验能力")),
                "旧项没编码，名称一致就该认作同一项（否则每次导入都删了重建）");
    }

    @Test
    @DisplayName("草稿项编码为空时按名称匹配，不会因为空串命中一堆无编码旧项")
    void blankDraftCodeDoesNotMatchEverything() {
        PaItem a = item(1L, null, "检验能力");
        PaItem b = item(2L, null, "判定能力");
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(a, b));

        assertSame(b, matcher.match(draft(null, "判定能力")));
        assertSame(a, matcher.match(draft("", "检验能力")));
    }

    @Test
    @DisplayName("名称和编码都空的旧项永远不会被匹配（只能被当成文件里没有的）")
    void fullyBlankOldItemNeverMatches() {
        PaItem blank = item(1L, null, null);
        PaImportItemMatcher matcher = new PaImportItemMatcher(List.of(blank));

        assertNull(matcher.match(draft(null, null)));
        assertEquals(List.of(blank), matcher.remaining());
    }

    // ------------------------------------------------------------------ 工具

    private static PaItem item(Long id, String code, String name) {
        PaItem item = new PaItem();
        item.setId(id);
        item.setItemCode(code);
        item.setItemName(name);
        return item;
    }

    private static PaImportDraft.ItemDraft draft(String code, String name) {
        PaImportDraft.ItemDraft item = new PaImportDraft.ItemDraft();
        item.setItemCode(code);
        item.setItemName(name);
        return item;
    }
}
