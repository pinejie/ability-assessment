package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.company.ability.entity.PaBand;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheet;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaBandMapper;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import com.company.ability.mapper.PaSheetMapper;
import com.company.ability.service.PaScoreService;
import com.company.ability.utils.PaBandScale;
import com.company.ability.utils.PaItemLevelScale;
import com.company.ability.vo.PaSheetScoreVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测评算分实现（方案 17 §五）。
 *
 * <p>三条不可调和的取舍都写在这里，改之前先读：</p>
 * <ol>
 *   <li><b>加权平均，不是加权求和</b>：权重合计 110% 时加权求和会让总分虚高 10%、等级全偏；
 *       加权平均不受影响，所以「合计 ≠ 100%」只是页面提醒，不阻断保存（口径 #2）。
 *       分母只累加<b>真正算出 item_score</b> 的能力项 —— 全员都没评的项不能拉低得分。</li>
 *   <li><b>未评的主体不进分母</b>：某个 360 没评这一项，项得分就是上级与其余 360 的平均；
 *       全部没评（提交闸门理论上挡住了，重算时仍可能）才留 NULL。
 *       按 0 算会让「没评」变成「评得极差」，那是另一种语义。</li>
 *   <li><b>自评全程不参与</b>：只落 {@code selfScore} 供报告展示（口径 #13）。
 *       把它混进分母会让「自己给自己打高分」直接抬高总得分。</li>
 * </ol>
 *
 * <p>项内平均是<b>扁平平均</b>，不是「上级均值与 360 均值再平均」：
 * 方案 §5.5 算例里上级 4、360 甲 4、360 乙 3 得 (4+4+3)/3 = 3.6667，
 * 也就是说上级只有一票，与每位 360 等权。改成两级平均会让人数多的 360 组权重翻倍。</p>
 *
 * <p>与旧存储过程的关系：{@code usp_assess_recalc} / {@code usp_assess_rate} 等 8 个过程
 * 在 P3 随旧表一起 DROP（方案 §6.2），本类是唯一算分入口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaScoreServiceImpl implements PaScoreService {

    static final int ERR_SHEET_NOT_FOUND = 40501;

    /** 项得分与总得分保留 4 位小数：再多位数报告上也不展示，只会让「差 0.00005 算不平」变复杂 */
    private static final int SCALE = 4;

    /** uf_pa_sheet.status */
    private static final int SHEET_PARTIAL = 1;
    private static final int SHEET_SCORED = 2;

    /** uf_assess_task.status */
    private static final int TASK_RUNNING = 0;
    private static final int TASK_DONE = 2;

    private final PaSheetMapper sheetMapper;
    private final PaSheetItemMapper sheetItemMapper;
    private final PaRatingMapper ratingMapper;
    private final PaBandMapper bandMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // ================================================================ 入口

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaSheetScoreVO recalculate(Long sheetId) {
        return doRecalculate(sheetId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaSheetScoreVO finish(Long taskId) {
        long sheetId = num(loadTask(taskId).get("sheet_id"));

        PaSheetScoreVO vo = doRecalculate(sheetId, true);
        if (Boolean.TRUE.equals(vo.getScored())) {
            jdbc.update("UPDATE uf_assess_task SET status = ?, update_time = SYSDATETIME() WHERE id = ? AND status = ?",
                    TASK_DONE, taskId, TASK_RUNNING);
        }
        log.info("测评算分完成 taskId={}, sheetId={}, 总得分={}, 等级说明={}",
                taskId, sheetId, vo.getTotalScore(), vo.getBandName());
        return vo;
    }

    @Override
    public void markPartial(Long sheetId) {
        // 已算分的页面不再降级：分数是有效结果，被另一次发起里的单人提交改成「部分提交」，
        // 报告上的分数就凭空消失了。
        Integer status = jdbc.queryForObject(
                "SELECT status FROM uf_pa_sheet WHERE id = ?", Integer.class, sheetId);
        if (status != null && status == SHEET_SCORED) {
            return;
        }
        jdbc.update("UPDATE uf_pa_sheet SET status = ?, update_time = SYSDATETIME() WHERE id = ?",
                SHEET_PARTIAL, sheetId);
    }

    // ================================================================ 算分主体

    /**
     * @param withComment 是否落合并评语。只有全员齐备（finish）时才写 ——
     *                    中途把已提交者的评语拼进去，报告上会出现「半份意见」。
     */
    private PaSheetScoreVO doRecalculate(long sheetId, boolean withComment) {
        PaSheet sheet = sheetMapper.selectById(sheetId);
        if (sheet == null) {
            throw new BusinessException(ERR_SHEET_NOT_FOUND, "测评页不存在：" + sheetId);
        }

        List<PaSheetItem> items = sheetItemMapper.selectList(new LambdaQueryWrapper<PaSheetItem>()
                .eq(PaSheetItem::getSheetId, sheetId)
                .orderByAsc(PaSheetItem::getSortNo).orderByAsc(PaSheetItem::getId));
        List<PaRating> ratings = ratingMapper.selectList(new LambdaQueryWrapper<PaRating>()
                .eq(PaRating::getSheetId, sheetId));

        // itemId → (raterType → 分值)。一个 360 对一项只能评一次（唯一索引保证），
        // 用 List 只为「脏数据重复提交」时不至于丢分。
        Map<Long, Map<String, List<BigDecimal>>> byItem = new LinkedHashMap<>();
        for (PaRating r : ratings) {
            if (r.getItemId() == null || r.getScore() == null) {
                continue;
            }
            byItem.computeIfAbsent(r.getItemId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.getRaterType() == null ? "" : r.getRaterType(), k -> new ArrayList<>())
                    .add(r.getScore());
        }

        Totals total = new Totals();
        Totals self = new Totals();
        Totals leader = new Totals();
        Totals rater360 = new Totals();
        int scoredItems = 0;

        // 刻度名要按每项自己的等级分值切区间：一次查回本页所有项的等级配置。
        // 算分是批量动作（一页十几项），逐项查库会变成十几趟往返。
        Map<Long, List<PaItemLevelScale.Point>> itemLevels = loadItemLevels(items);

        for (PaSheetItem item : items) {
            Map<String, List<BigDecimal>> itemRatings = byItem.getOrDefault(item.getItemId(), Map.of());
            BigDecimal weight = item.getWeight() == null ? BigDecimal.ZERO : item.getWeight();

            BigDecimal leaderAvg = average(itemRatings.get(PaRating.TYPE_LEADER));
            BigDecimal rater360Avg = average(itemRatings.get(PaRating.TYPE_360));
            BigDecimal selfAvg = average(itemRatings.get(PaRating.TYPE_SELF));
            BigDecimal itemScore = itemScore(itemRatings);
            // 刻度名快照（2026-11-24）：与 itemScore 同一时刻落库，之后等级改名不再回溯改历史
            String itemLevelName = PaItemLevelScale.resolve(
                    itemLevels.get(item.getItemId()), itemScore);

            item.setLeaderScore(leaderAvg);
            item.setRater360Score(rater360Avg);
            item.setSelfScore(selfAvg);
            item.setItemScore(itemScore);
            item.setItemLevelName(itemLevelName);
            item.setRater360Count(sizeOf(itemRatings.get(PaRating.TYPE_360)));
            // 显式写回（含 NULL）：updateById 的默认策略是 NOT_NULL，重算后「这项没人评了」
            // 会让上一次的分数留在行上，报告上看不出是旧的。
            sheetItemMapper.update(null, new UpdateWrapper<PaSheetItem>()
                    .eq("id", item.getId())
                    .set("leader_score", leaderAvg)
                    .set("rater360_score", rater360Avg)
                    .set("self_score", selfAvg)
                    .set("item_score", itemScore)
                    .set("item_level_name", itemLevelName)
                    .set("rater360_count", item.getRater360Count()));

            if (itemScore != null) {
                scoredItems++;
                total.add(itemScore, weight);
            }
            self.add(selfAvg, weight);
            leader.add(leaderAvg, weight);
            rater360.add(rater360Avg, weight);
        }

        BigDecimal totalScore = total.mean();
        String bandName = resolveBand(sheet.getPostId(), totalScore);
        boolean scored = totalScore != null;

        sheet.setItemCount(items.size());
        sheet.setSelfScore(self.mean());
        sheet.setLeaderScore(leader.mean());
        sheet.setRater360Score(rater360.mean());
        sheet.setTotalScore(totalScore);
        sheet.setBandName(bandName);
        if (scored) {
            sheet.setStatus(SHEET_SCORED);
        }
        if (withComment) {
            sheet.setCommentText(mergeComments(sheetId));
        }
        sheet.setSnapshotJson(snapshotJson(sheet, items));
        // 同上：NOT_NULL 策略下「本次算不出总分」不会把上次的成绩清掉
        sheetMapper.update(null, new UpdateWrapper<PaSheet>()
                .eq("id", sheetId)
                .set("item_count", sheet.getItemCount())
                .set("self_score", sheet.getSelfScore())
                .set("leader_score", sheet.getLeaderScore())
                .set("rater360_score", sheet.getRater360Score())
                .set("total_score", totalScore)
                .set("band_name", bandName)
                .set("status", sheet.getStatus())
                .set("comment_text", sheet.getCommentText())
                .set("snapshot_json", sheet.getSnapshotJson()));

        PaSheetScoreVO vo = new PaSheetScoreVO();
        vo.setSheetId(sheetId);
        vo.setStatus(sheet.getStatus());
        vo.setItemCount(items.size());
        vo.setScoredItemCount(scoredItems);
        vo.setSelfScore(sheet.getSelfScore());
        vo.setLeaderScore(sheet.getLeaderScore());
        vo.setRater360Score(sheet.getRater360Score());
        vo.setTotalScore(totalScore);
        vo.setBandName(bandName);
        vo.setScored(scored);
        return vo;
    }

    // ================================================================ 计算工具

    /**
     * 该页所有能力项的等级配置：itemId → 按分值升序的档位（刻度名已剥前缀）。
     *
     * <p>一次 {@code IN} 查回，不逐项查。等级配置读的是<b>当前</b>值 ——
     * 本方法的结果只用于<b>此刻</b>生成快照，之后配置再改也不影响已落的快照。</p>
     */
    private Map<Long, List<PaItemLevelScale.Point>> loadItemLevels(List<PaSheetItem> items) {
        List<Long> itemIds = items.stream()
                .map(PaSheetItem::getItemId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = itemIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, List<PaItemLevelScale.Point>> out = new LinkedHashMap<>();
        jdbc.query("SELECT item_id, level_name, score FROM uf_pa_item_level" +
                        " WHERE item_id IN (" + placeholders + ") ORDER BY item_id, score",
                rs -> {
                    out.computeIfAbsent(rs.getLong("item_id"), k -> new ArrayList<>())
                            .add(new PaItemLevelScale.Point(rs.getBigDecimal("score"),
                                    PaItemLevelScale.stripPrefix(rs.getString("level_name"))));
                }, itemIds.toArray());
        return out;
    }

    /**
     * 项得分 = 上级与全部 360 的<b>扁平</b>平均（自评不进、未评者不进分母）。
     * 全都没评返回 null —— 「没有分」和「0 分」是两件事。
     */
    private static BigDecimal itemScore(Map<String, List<BigDecimal>> itemRatings) {
        List<BigDecimal> all = new ArrayList<>();
        all.addAll(nullSafe(itemRatings.get(PaRating.TYPE_LEADER)));
        all.addAll(nullSafe(itemRatings.get(PaRating.TYPE_360)));
        return average(all);
    }

    private static BigDecimal average(List<BigDecimal> scores) {
        List<BigDecimal> valid = nullSafe(scores);
        if (valid.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal s : valid) {
            sum = sum.add(s);
        }
        return sum.divide(BigDecimal.valueOf(valid.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static List<BigDecimal> nullSafe(List<BigDecimal> list) {
        List<BigDecimal> out = new ArrayList<>();
        if (list != null) {
            for (BigDecimal b : list) {
                if (b != null) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    private static int sizeOf(List<BigDecimal> list) {
        return nullSafe(list).size();
    }

    /**
     * 分值区间命中（§4.5）：{@code [lower, upper)} —— 下界含、上界不含，NULL 表示 ±∞。
     *
     * <p>区间有缺口的配置由 {@code PaModelService.saveBands} 拦下；这里命中不到就返回 null，
     * 不抛异常 —— 算分是批量动作，一个岗位区间配错不该让整份报告 500。</p>
     */
    private String resolveBand(Long postId, BigDecimal totalScore) {
        if (postId == null || totalScore == null) {
            return null;
        }
        List<PaBand> bands = bandMapper.selectList(new LambdaQueryWrapper<PaBand>()
                .eq(PaBand::getPostId, postId)
                .orderByAsc(PaBand::getSort).orderByAsc(PaBand::getId));
        // 切法在 PaBandScale 里，报告侧给每个能力项判档位走的是同一个方法 ——
        // 两处各写一遍，改一处忘另一处就会出现「总得分 3.5 是能手、单项 3.5 却是高手」
        String hit = PaBandScale.resolve(bands, totalScore);
        if (hit == null) {
            log.warn("总得分未命中任何分值区间 postId={}, totalScore={}，等级说明留空", postId, totalScore);
        }
        return hit;
    }

    /**
     * 合并评语（口径 #14）：直接上级 + 各 360 的文字按人拼成一段，
     * <b>不区分「直接上级评语」「客户评语」</b>。
     *
     * <p>拼接顺序固定（上级在前、360 按人员 ID 升序），否则同一份数据两次刷新排版不同，
     * 用户会以为数据变了。</p>
     */
    private String mergeComments(long sheetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.comment" +
                " FROM uf_assess_task_rater tr" +
                " JOIN uf_assess_task t ON t.id = tr.task_id" +
                " WHERE t.sheet_id = ? AND tr.rater_type IN ('LEADER', 'RATER360')" +
                "   AND tr.comment IS NOT NULL AND LTRIM(RTRIM(tr.comment)) <> ''" +
                " ORDER BY CASE tr.rater_type WHEN 'LEADER' THEN 1 ELSE 2 END, tr.rater_resource_id",
                sheetId);
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object c = r.get("comment");
            if (c != null) {
                seen.add(c.toString().trim());
            }
        }
        return seen.isEmpty() ? null : String.join("\n\n", seen);
    }

    /**
     * 算分口径快照：把当时的权重与区间一并存下（口径 #15）。
     *
     * <p>页面行本身已是快照（item_name / weight / category_name），这里多存的是
     * 「命中规则」—— 等级区间后续被改，历史报告仍能解释「当时凭什么给这个等级」。</p>
     */
    private String snapshotJson(PaSheet sheet, List<PaSheetItem> items) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("postId", sheet.getPostId());
        snapshot.put("postName", sheet.getPostName());
        snapshot.put("computedAt", java.time.LocalDateTime.now().toString());
        snapshot.put("itemCount", items.size());
        snapshot.put("bands", bandMapper.selectList(new LambdaQueryWrapper<PaBand>()
                        .eq(PaBand::getPostId, sheet.getPostId())
                        .orderByAsc(PaBand::getSort))
                .stream().map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bandName", b.getBandName());
                    m.put("lower", b.getLowerBound());
                    m.put("upper", b.getUpperBound());
                    return m;
                }).collect(Collectors.toList()));
        List<Map<String, Object>> itemSnapshots = new ArrayList<>(items.size());
        for (PaSheetItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", item.getItemId());
            m.put("itemName", item.getItemName());
            m.put("weight", item.getWeight());
            m.put("raterLeader", item.getRaterLeader());
            m.put("rater360", item.getRater360());
            itemSnapshots.add(m);
        }
        snapshot.put("items", itemSnapshots);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("算分快照序列化失败 sheetId={}: {}", sheet.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> loadTask(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sheet_id, status FROM uf_assess_task WHERE id = ?", taskId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_SHEET_NOT_FOUND, "测评任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 加权累加器：只有真正有值的主体才累加分子与分母（未评不进分母） */
    private static class Totals {
        private BigDecimal weighted = BigDecimal.ZERO;
        private BigDecimal weight = BigDecimal.ZERO;

        void add(BigDecimal value, BigDecimal w) {
            if (value == null) {
                return;
            }
            weighted = weighted.add(value.multiply(w));
            weight = weight.add(w);
        }

        /** 分母为 0 返回 null —— 直接除会抛 ArithmeticException，而「权重全 0」是配错不是崩溃 */
        BigDecimal mean() {
            if (weight.signum() == 0) {
                return null;
            }
            return weighted.divide(weight, SCALE, RoundingMode.HALF_UP);
        }
    }
}
