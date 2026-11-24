package com.company.ability.service;

import com.company.ability.dto.PaImportDraft;
import com.company.ability.entity.PaItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力项匹配：拿草稿里的一项，在既有能力项里找出「同一项」。
 *
 * <p><b>为什么不能只按编码</b>：编码允许重复（sql/65）。模板里同一岗位的编码常分两段用 ——
 * 专业能力 A01…G01，职业素养/个性特质/发展潜力又从 E01 来，于是 E01 / F01 / G01 各出现两次。
 * 只按编码建 Map 的话，后一项会把前一项覆盖掉，两个草稿项都匹配到同一行旧数据，
 * 另一项被当成「新增」重复插入，旧的又被当成「文件里没有」删掉 —— 数据静默错乱。</p>
 *
 * <p><b>三级匹配</b>：编码+名称都相同 → 只编码相同 → 只名称相同。
 * 名称常被业务微调（加个逗号、换个同义词），所以编码命中时优先认编码；
 * 而编码重复时，「编码+名称」这一级能把两项分清楚。</p>
 *
 * <p><b>命中即消耗</b>：一条既有能力项只能被匹配一次。同一份文件里两个都叫 E01 的项，
 * 第一个吃掉按编码能对上的那行，第二个再按名称找自己那行，不会抢同一行。</p>
 *
 * <p>导入的预览比对（{@link PaImportValidator#diff}）和真正写库
 * （{@link PaImportApplier#writeContent}）必须用同一套规则，否则预览说「覆盖 3 项」
 * 而实际动作是另一回事。</p>
 */
final class PaImportItemMatcher {

    private static final String SEP = "\u0000";

    private final List<PaItem> existing;
    private final boolean[] used;
    private final Map<String, Deque<Integer>> byCodeName = new LinkedHashMap<>();
    private final Map<String, Deque<Integer>> byCode = new LinkedHashMap<>();
    private final Map<String, Deque<Integer>> byName = new LinkedHashMap<>();

    PaImportItemMatcher(List<PaItem> existing) {
        this.existing = existing == null ? List.of() : new ArrayList<>(existing);
        this.used = new boolean[this.existing.size()];
        for (int i = 0; i < this.existing.size(); i++) {
            PaItem item = this.existing.get(i);
            String code = nz(item.getItemCode());
            String name = nz(item.getItemName());
            if (!code.isEmpty() && !name.isEmpty()) {
                byCodeName.computeIfAbsent(code + SEP + name, k -> new ArrayDeque<>()).add(i);
            }
            if (!code.isEmpty()) {
                byCode.computeIfAbsent(code, k -> new ArrayDeque<>()).add(i);
            }
            // 名称在同岗位内理论上是唯一的（sql/50 有约束），这里仍按队列处理，
            // 不让历史脏数据把匹配搞崩
            if (!name.isEmpty()) {
                byName.computeIfAbsent(name, k -> new ArrayDeque<>()).add(i);
            }
        }
    }

    /**
     * 找草稿项对应的既有能力项。
     *
     * @return 命中的既有项；没命中返回 null（= 新增）
     */
    PaItem match(PaImportDraft.ItemDraft draft) {
        if (draft == null) {
            return null;
        }
        String code = nz(draft.getItemCode());
        String name = nz(draft.getItemName());

        if (!code.isEmpty() && !name.isEmpty()) {
            PaItem hit = take(byCodeName, code + SEP + name);
            if (hit != null) {
                return hit;
            }
        }
        if (!code.isEmpty()) {
            PaItem hit = take(byCode, code);
            if (hit != null) {
                return hit;
            }
        }
        if (!name.isEmpty()) {
            return take(byName, name);
        }
        return null;
    }

    /** 没被任何草稿项匹配上的既有能力项 = 文件里没有的（要停用或删除） */
    List<PaItem> remaining() {
        List<PaItem> out = new ArrayList<>();
        for (int i = 0; i < existing.size(); i++) {
            if (!used[i]) {
                out.add(existing.get(i));
            }
        }
        return out;
    }

    private PaItem take(Map<String, Deque<Integer>> index, String key) {
        Deque<Integer> queue = index.get(key);
        while (queue != null && !queue.isEmpty()) {
            int i = queue.poll();
            if (!used[i]) {
                used[i] = true;
                return existing.get(i);
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
