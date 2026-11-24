package com.company.ability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportPreview;
import com.company.ability.dto.PaImportQuestion;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入预览：算「点了确认会发生什么」，不写任何东西。
 *
 * <p>用户在预览页做的每一个决定（覆盖 / 跳过、重算封存报告、撤回进行中任务）
 * 都会改变这里的结果，所以预览是<b>带参数</b>算的：{@link #preview} 收一份 {@link PaImportPlan}，
 * 用户改一个勾选就重算一次。反过来「预览一次、执行时再按另一个计划写」会立刻出现
 * 「预览说不动，执行完发现报告重算了」这类无法解释的情况。</p>
 *
 * <p>本位只读，特殊 SQL 用 {@link JdbcTemplate}：跨 {@code uf_assess_task} /
 * {@code uf_assess_period} / {@code HrmResource} 三张非 {@code uf_pa_*} 表的联查走 MyBatis
 * 要拼一堆 {@code @TableField(exist = false)} 的假实体，不划算。原生 SQL 绕过租户拦截器，
 * 所以每处都显式带 {@code dimension_id} 条件。</p>
 */
@Slf4j
@Service
public class PaImportPlanner {

    /** 单个能力项至少 2 档（与 PaModelServiceImpl.MIN_LEVELS 同口径） */
    private static final int MIN_LEVELS = 2;

    private final JdbcTemplate jdbc;
    private final PaPostMapper postMapper;
    private final PaItemMapper itemMapper;
    private final PaRatingMapper ratingMapper;
    private final PaSheetItemMapper sheetItemMapper;
    private final PaImportValidator validator;

    public PaImportPlanner(JdbcTemplate jdbc, PaPostMapper postMapper, PaItemMapper itemMapper,
                           PaRatingMapper ratingMapper, PaSheetItemMapper sheetItemMapper,
                           PaImportValidator validator) {
        this.jdbc = jdbc;
        this.postMapper = postMapper;
        this.itemMapper = itemMapper;
        this.ratingMapper = ratingMapper;
        this.sheetItemMapper = sheetItemMapper;
        this.validator = validator;
    }

    /**
     * 算预览。
     *
     * @param draft 已装配、已校验通过的草稿
     * @param plan  用户当前的选择
     */
    public PaImportPreview preview(PaImportDraft draft, PaImportPlan plan) {
        PaImportPlan effective = plan == null ? PaImportPlan.safeDefault() : plan;
        PaImportPreview preview = new PaImportPreview();

        List<Long> affectedPostIds = new ArrayList<>();
        for (PaImportDraft.PostDraft postDraft : draft.getPosts()) {
            PaImportPreview.PostPreview pp = previewPost(postDraft, effective);
            preview.getPosts().add(pp);
            // 只有真要覆盖的岗位才会动到历史测评数据；跳过的不算
            if ("OVERWRITE".equals(pp.getAction()) && pp.getPostId() != null) {
                affectedPostIds.add(pp.getPostId());
            }
        }

        if (!affectedPostIds.isEmpty()) {
            fillImpact(preview.getImpact(), affectedPostIds);
        }
        deriveWarnings(preview, effective);
        deriveBlockers(preview, draft);
        preview.setNeedsImpactDecision(preview.getImpact().getRunningCount() > 0
                || (preview.getImpact().getFrozenCount() > 0 && !effective.isRegenerateFrozen()));
        return preview;
    }

    /**
     * 落库前的硬闸门。
     *
     * <p>这条路径原来只有 {@code deriveWarnings}，{@code blockers} 字段全项目没有任何一处 add ——
     * 于是 {@code runApply} 里那句「还有未解决的问题，不能导入」永远不成立，等于没有检查。
     * 对话层的问题可以被一句话销掉（实测：1 档能力项只回一句「就这样吧」就能一路写进库），
     * 所以真正能兜住的只有这里：只看草稿最终长什么样，不看过程里谁答应过什么。</p>
     *
     * <p>口径与 {@code PaModelServiceImpl.validateLevels} 保持一致（每个能力项至少 2 档），
     * 否则导入会写出手工编辑已经拦死的数据。</p>
     */
    private void deriveBlockers(PaImportPreview preview, PaImportDraft draft) {
        if (draft == null || draft.getPosts() == null) {
            return;
        }
        for (PaImportDraft.PostDraft post : draft.getPosts()) {
            for (PaImportDraft.ItemDraft item : post.getItems()) {
                int n = item.getLevels() == null ? 0 : item.getLevels().size();
                if (n < MIN_LEVELS) {
                    PaImportQuestion q = new PaImportQuestion();
                    q.setType(PaImportQuestion.CONFLICT);
                    q.setSlot(PaImportQuestionBuilder.slotLevels(post, item));
                    q.setSheetName(post.getSheetName());
                    q.setBlocking(true);
                    q.setTopic("「" + itemName(item) + "」只有 " + n + " 个等级，至少要 " + MIN_LEVELS + " 个");
                    q.setQuestion(q.getTopic() + "，导入会把这种能力项写进库，评价人填表时它没有区分度");
                    preview.getBlockers().add(q);
                }
            }
        }
    }

    private static String itemName(PaImportDraft.ItemDraft item) {
        return item.getItemName() == null || item.getItemName().isBlank()
                ? "未命名能力项" : item.getItemName();
    }

    // ------------------------------------------------------------------ 单岗位

    private PaImportPreview.PostPreview previewPost(PaImportDraft.PostDraft draft, PaImportPlan plan) {
        PaImportPreview.PostPreview pp = new PaImportPreview.PostPreview();
        pp.setSheetName(draft.getSheetName());
        pp.setPostName(draft.getPostName());
        pp.setDepartmentId(draft.getDepartmentId());
        // 通用岗位库里存的就是 NULL，但预览页的部门列不能空着 ——
        // 空字符串会被看成「没读到部门」，用户不敢往下点
        pp.setDepartmentName(draft.getDepartmentId() == null
                ? PaImportQuestionBuilder.GENERIC_DEPARTMENT
                : draft.getDepartmentName());
        pp.setCategoryCount(draft.getCategories().size());
        pp.setItemCount(draft.getItems().size());
        pp.setBandCount(draft.getBands().size());
        pp.setLevelCount(draft.getItems().stream().mapToInt(i -> i.getLevels().size()).sum());
        pp.setWeightTotal(draft.getItems().stream()
                .map(i -> i.getWeight() == null ? BigDecimal.ZERO : i.getWeight())
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        PaPost existing = validator.findExistingPost(draft.getDepartmentId(), draft.getPostName());
        if (existing == null) {
            pp.setExists(false);
            pp.setAction("CREATE");
            pp.setSummary("新建岗位「" + draft.getPostName() + "」：" + pp.getCategoryCount() + " 个类别、"
                    + pp.getItemCount() + " 个能力项、" + pp.getBandCount() + " 段分值区间");
            return pp;
        }

        pp.setExists(true);
        pp.setPostId(existing.getId());

        if (!plan.isOverwrite()) {
            pp.setAction("SKIP");
            pp.setSummary("库里已有同名岗位，按你的选择跳过");
            return pp;
        }

        PaImportValidator.Diff diff = validator.diff(existing, draft);
        pp.setUnchanged(diff.unchanged);
        pp.setChanged(diff.changed);
        pp.setAdded(diff.added);
        pp.setRemoved(diff.removed);
        pp.setRemovedButReferenced(countReferenced(diff.removedIds));

        pp.setAction("OVERWRITE");
        if (!diff.hasDifference()) {
            pp.setSummary("库里已有同名岗位，内容完全一致（仍会按文件重写一遍，不产生变化）");
        } else {
            StringBuilder sb = new StringBuilder("覆盖岗位「").append(draft.getPostName()).append("」：");
            if (!diff.changed.isEmpty()) {
                sb.append("改动 ").append(diff.changed.size()).append(" 项");
            }
            if (!diff.added.isEmpty()) {
                sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "、")
                        .append("新增 ").append(diff.added.size()).append(" 项");
            }
            if (!diff.removed.isEmpty()) {
                sb.append(sb.charAt(sb.length() - 1) == '：' ? "" : "、")
                        .append("移除 ").append(diff.removed.size()).append(" 项");
                if (pp.getRemovedButReferenced() > 0) {
                    // 被引用过的能力项不能物理删除（历史报告还要读它），只能停用
                    sb.append("（其中 ").append(pp.getRemovedButReferenced()).append(" 项有历史评级，改为停用）");
                }
            }
            pp.setSummary(sb.toString());
        }
        return pp;
    }

    /** 这些能力项里有多少带着评级引用（决定「删除」还是「停用」） */
    private int countReferenced(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return 0;
        }
        Set<Long> referenced = new LinkedHashSet<>();
        for (PaRating r : ratingMapper.selectList(new LambdaQueryWrapper<PaRating>()
                .in(PaRating::getItemId, itemIds).select(PaRating::getItemId))) {
            referenced.add(r.getItemId());
        }
        for (PaSheetItem si : sheetItemMapper.selectList(new LambdaQueryWrapper<PaSheetItem>()
                .in(PaSheetItem::getItemId, itemIds).select(PaSheetItem::getItemId))) {
            referenced.add(si.getItemId());
        }
        return referenced.size();
    }

    // ------------------------------------------------------------------ 影响面

    private void fillImpact(PaImportPreview.Impact impact, List<Long> postIds) {
        long dimensionId = DimensionContext.getOrDefault();
        String in = placeholders(postIds.size());

        List<Object> args = new ArrayList<>();
        args.add(dimensionId);
        args.addAll(postIds);

        // 进行中：问卷已经按旧模型发出去，评完的分数没有意义
        // 姓名兜底用 COALESCE 而不是 ISNULL：两者在 SQL Server 上等价，
        // 但 H2（测试库）没有 ISNULL，只有 COALESCE 是跨库都认的写法
        List<Map<String, Object>> running = jdbc.queryForList(
                "SELECT t.id AS task_id, t.sheet_id, p.year, s.department_name, s.post_name," +
                        "       COALESCE(h.lastname, N'(人员已删除)') AS resource_name," +
                        "       (SELECT COUNT(*) FROM uf_assess_task_rater tr WHERE tr.task_id = t.id) AS total_raters," +
                        "       (SELECT COUNT(*) FROM uf_assess_task_rater tr WHERE tr.task_id = t.id AND tr.status = 1) AS submitted_raters" +
                        " FROM uf_assess_task t" +
                        " JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                        " JOIN uf_assess_period p ON p.id = t.period_id" +
                        " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                        " WHERE t.dimension_id = ? AND t.status = 0 AND s.post_id IN " + in +
                        " ORDER BY p.year DESC, t.id DESC", args.toArray());

        Set<Integer> years = new LinkedHashSet<>();
        for (Map<String, Object> r : running) {
            PaImportPreview.RunningTask rt = new PaImportPreview.RunningTask();
            rt.setTaskId(num(r.get("task_id")));
            rt.setSheetId(num(r.get("sheet_id")));
            rt.setYear(intOf(r.get("year")));
            rt.setResourceName(str(r.get("resource_name")));
            rt.setDepartmentName(str(r.get("department_name")));
            rt.setPostName(str(r.get("post_name")));
            rt.setSubmittedRaters(intOf(r.get("submitted_raters")));
            rt.setTotalRaters(intOf(r.get("total_raters")));
            impact.getRunning().add(rt);
            impact.setSubmittedRaterCount(impact.getSubmittedRaterCount() + rt.getSubmittedRaters());
            years.add(rt.getYear());
        }
        impact.setRunningCount(running.size());

        // 已封存：页面已出分（uf_pa_sheet.status = 2），报告上就是这个分数
        List<Map<String, Object>> frozen = jdbc.queryForList(
                "SELECT s.id AS sheet_id, p.year, s.department_name, s.post_name, s.total_score, s.band_name," +
                        "       s.seal_status, COALESCE(h.lastname, N'(人员已删除)') AS resource_name" +
                        " FROM uf_pa_sheet s" +
                        " JOIN uf_assess_period p ON p.id = s.period_id" +
                        " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                        " WHERE s.dimension_id = ? AND s.status = 2 AND s.post_id IN " + in +
                        " ORDER BY p.year DESC, s.id DESC", args.toArray());
        for (Map<String, Object> r : frozen) {
            PaImportPreview.FrozenReport fr = new PaImportPreview.FrozenReport();
            fr.setSheetId(num(r.get("sheet_id")));
            fr.setYear(intOf(r.get("year")));
            fr.setResourceName(str(r.get("resource_name")));
            fr.setDepartmentName(str(r.get("department_name")));
            fr.setPostName(str(r.get("post_name")));
            fr.setTotalScore(dec(r.get("total_score")));
            fr.setBandName(str(r.get("band_name")));
            fr.setSealed(intOf(r.get("seal_status")) == 1);
            impact.getFrozen().add(fr);
            years.add(fr.getYear());
        }
        impact.setFrozenCount(frozen.size());
        impact.setYears(new ArrayList<>(years));
    }

    // ------------------------------------------------------------------ 提醒

    private void deriveWarnings(PaImportPreview preview, PaImportPlan plan) {
        PaImportPreview.Impact impact = preview.getImpact();

        if (impact.getRunningCount() > 0) {
            if (plan.isVoidRunning()) {
                preview.getWarnings().add("将撤回 " + impact.getRunningCount() + " 个进行中的测评任务"
                        + (plan.isRelaunchRunning() ? "，并按原参数重新发起" : "")
                        + "。已提交的 " + impact.getSubmittedRaterCount() + " 份评价会作废"
                        + (plan.isRelaunchRunning()
                        ? "，评价人会收到两条消息（一条撤回、一条新邀请）。"
                        : "。"));
            } else {
                preview.getWarnings().add("有 " + impact.getRunningCount()
                        + " 个进行中的测评任务仍按旧模型评价，出分会与新模型不一致。"
                        + "你可以改为撤回后重新发起。");
            }
        }

        if (impact.getFrozenCount() > 0) {
            if (!plan.isRegenerateFrozen()) {
                preview.getWarnings().add("有 " + impact.getFrozenCount()
                        + " 份已封存报告。导入不会改动它们（分数与等级保持原样），"
                        + "如需按新模型重算请勾选「重新生成已封存报告」。");
            } else {
                long chosen = impact.getFrozen().stream()
                        .filter(f -> plan.shouldRegenerate(f.getYear())).count();
                preview.getWarnings().add("已封存报告中有 " + chosen + " 份将按新模型重算，"
                        + "分数与等级可能变化。重算用的仍是当初评价人打的分，不会重新发问卷。");
            }
        }

        for (PaImportPreview.PostPreview pp : preview.getPosts()) {
            if (pp.getWeightTotal() != null && pp.getWeightTotal().compareTo(new BigDecimal("100")) != 0) {
                preview.getWarnings().add("岗位「" + pp.getPostName() + "」权重合计 "
                        + pp.getWeightTotal().stripTrailingZeros().toPlainString()
                        + "%，系统按 Σ权重 归一算分，不要求等于 100%。");
            }
            if ("OVERWRITE".equals(pp.getAction()) && pp.getRemovedButReferenced() > 0) {
                preview.getWarnings().add("岗位「" + pp.getPostName() + "」有 "
                        + pp.getRemovedButReferenced() + " 个能力项在文件里已不存在但带着历史评级，"
                        + "将改为停用（不物理删除，历史报告仍可读）。");
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 一次性取岗位下所有能力项，供执行阶段比对（避免执行时再查一遍） */
    public List<PaItem> itemsOf(Long postId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId)
                .eq(PaItem::getDeleted, 0));
    }

    public PaPost postOf(Long postId) {
        return postId == null ? null : postMapper.selectById(postId);
    }

    private static String placeholders(int n) {
        return "(" + String.join(",", java.util.Collections.nCopies(n, "?")) + ")";
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : (o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 便于单测：空草稿 → 空预览 */
    public PaImportPreview emptyPreview() {
        return new PaImportPreview();
    }

    /** 供前端展示：把岗位草稿按 sheet 分组统计 */
    public Map<String, Integer> countBySheet(PaImportDraft draft) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (PaImportDraft.PostDraft p : draft.getPosts()) {
            out.put(p.getSheetName(), p.getItems().size());
        }
        return out;
    }
}
