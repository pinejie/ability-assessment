package com.company.ability.utils;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 能力项等级刻度：把项得分落到「这一项自己的等级分值」切出的左闭右开区间上。
 *
 * <p>为什么单独一个工具类：算分时（{@code PaScoreServiceImpl}）要写刻度名快照，
 * 两边的切法必须完全一致 —— 分成两处实现，改一处忘另一处，报告上就会出现
 * 「分数 3.6 却叫能手」这种没人能一眼看出的错。</p>
 *
 * <p><b>与岗位区间表 {@code uf_pa_band} 是两把尺子</b>：那张表按 {@code post_id} 分、
 * 没有 {@code item_id}，解释的是「总得分落在哪一段」。项得分只能按该项自己的等级分值切。</p>
 *
 * <p>区间语义 {@code [s_i, s_{i+1})}：下界含、上界不含，最高档到 {@code +∞}。
 * 例如等级 1/2/3/4/5 切出 {@code [1,2) [2,3) [3,4) [4,5) [5,+∞)}，
 * 2.5 分落在 {@code [2,3)}，4 分落在 {@code [4,5)}。</p>
 */
public final class PaItemLevelScale {

    /**
     * 等级名的「分值前缀」：{@code 3分 · 精进/能手} 里的 {@code 3分 · }。
     *
     * <p>只匹配「数字 + 分 + 可选分隔符」这一种形状。刻度名里自己带点的
     * （如「精进·能手」）不能被剥 —— 一刀切到第一个点后面会把名字削掉一半。</p>
     */
    private static final Pattern LEVEL_PREFIX =
            Pattern.compile("^\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[·・\\-—]?\\s*");

    private PaItemLevelScale() {
    }

    /** 一个等级档：分值点 + 刻度名（<b>已剥前缀</b>，见 {@link #stripPrefix}） */
    public record Point(BigDecimal score, String name) {
    }

    /**
     * 项得分命中哪一档。
     *
     * @param points 该项的等级档，<b>按分值升序</b>
     * @param score  项得分；null 表示这一项没人评，没有刻度可言
     * @return 命中的刻度名；该项没有任何等级配置、或 score 为 null 时返回 null
     *
     * <p>低于最低档（脏数据，如 0.5 分）返回最低档名。等级刻度是从 1 分起配的，
     * 报 null 会让页面显示成「没出分」，而实际是出了分只是跌破刻度 —— 两件事不能混。</p>
     */
    public static String resolve(List<Point> points, BigDecimal score) {
        if (points == null || points.isEmpty() || score == null) {
            return null;
        }
        String hit = null;
        for (Point p : points) {
            if (p.score() == null) {
                continue;
            }
            if (score.compareTo(p.score()) >= 0) {
                hit = p.name();
            } else {
                break;
            }
        }
        // 跌破最低档：给最低档，不给 null
        return hit != null ? hit : points.get(0).name();
    }

    /**
     * 剥掉等级名的「N分 · 」前缀，只留刻度名。
     *
     * <p>库里两种写法并存（实测 62 个能力项里 16 个写 {@code 3分 · 精进/能手}、
     * 46 个写 {@code 精进/能手}）。不剥的话页面会写成「3.67 · 3分 · 精进/能手」，
     * 分值出现两遍，读着像两件事。</p>
     */
    public static String stripPrefix(String levelName) {
        if (levelName == null) {
            return null;
        }
        String stripped = LEVEL_PREFIX.matcher(levelName).replaceFirst("").trim();
        return stripped.isEmpty() ? levelName.trim() : stripped;
    }
}
