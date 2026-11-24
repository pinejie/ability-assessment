package com.company.ability.utils;

import com.company.ability.entity.PaBand;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 岗位分值区间刻度：把分值落到 {@code uf_pa_band} 配的 {@code [lower, upper)} 区间上。
 *
 * <p>为什么单独一个工具类：算分时（{@code PaScoreServiceImpl}）用它定<b>总得分</b>的等级说明，
 * 拼报告时（{@code PaReportServiceImpl}）用同一套规则给<b>每个能力项的得分</b>也判一次。
 * 两处各写一遍，改一处忘另一处，报告上就会出现「总得分 3.5 是能手、单项 3.5 却是高手」
 * 这种没人能一眼看出的错。</p>
 *
 * <p><b>与项刻度 {@link PaItemLevelScale} 是两把尺子</b>：那个按每个能力项<b>自己的</b>等级
 * 分值切区间（{@code uf_pa_item_level}，名字形如「精进/能手」）；这里按<b>岗位</b>的固定档位切
 * （{@code uf_pa_band}，名字形如「能手」）。同一份报告里两套名字<b>故意各不相同</b>，
 * 2026-11-24 需求确认：页面统一用这里这套（新手/熟手/能手/高手/专家）。</p>
 *
 * <p>区间语义 {@code [lower, upper)}：下界含、上界不含，null 表示 ±∞。
 * 例：{@code [1,2) 新手、[2,3) 熟手 … [5,+∞) 专家}，5.00 分落「专家」，4.99 分落「高手」。</p>
 */
public final class PaBandScale {

    /**
     * 比对前先按 2 位小数四舍五入。
     *
     * <p>与业务看到的数字对齐：项得分 3.9999 在页面写成 4.00，若按原值判区间会落进
     * {@code [3,4)} 成为「能手」，而同一行显示的是 4.00 —— 用户按 4 分去查档位表，
     * 查出来是「高手」，页面却说能手。</p>
     */
    private static final int SCALE = 2;

    private PaBandScale() {
    }

    /**
     * 分值命中哪一档。
     *
     * @param bands 该岗位的档位，<b>按 sort 升序</b>（调用方负责排序，见下方说明）
     * @param score 要判的分值；null 表示没出分，没有等级可言
     * @return 命中的档位名；没配档位、score 为 null、命中不到任何区间都返回 null
     *
     * <p>为什么不在这里排序：档位是每个岗位一套、每次调用查一次库，调用方本来就带着
     * {@code ORDER BY sort} 查到手里，再排一次是白跑。区间有缺口/重叠由
     * {@code PaModelService.saveBands} 在保存时拦下，走到这里命中不到只可能是脏数据 ——
     * 算分是批量动作，一个岗位配错不该让整份报告 500，所以返回 null 留给页面显示「—」。</p>
     */
    public static String resolve(List<PaBand> bands, BigDecimal score) {
        if (bands == null || bands.isEmpty() || score == null) {
            return null;
        }
        BigDecimal value = score.setScale(SCALE, RoundingMode.HALF_UP);
        for (PaBand band : bands) {
            if (band == null) {
                continue;
            }
            BigDecimal lower = band.getLowerBound();
            BigDecimal upper = band.getUpperBound();
            boolean atOrAboveLower = lower == null || value.compareTo(lower) >= 0;
            boolean belowUpper = upper == null || value.compareTo(upper) < 0;
            if (atOrAboveLower && belowUpper) {
                return band.getBandName();
            }
        }
        return null;
    }
}
