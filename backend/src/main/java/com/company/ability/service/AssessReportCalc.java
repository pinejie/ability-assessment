package com.company.ability.service;

import com.company.ability.vo.AssessSheetVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 报告用的纯计算函数（无数据库访问）。
 * 实际得分 = 直接领导×领导权重/100 + 客户×客户权重/100 + 自评×自评权重/100（4 位小数）
 * 得分率 = 实际得分 ÷ 标准分 × 100（2 位小数）
 * 等级：(lower, upper]，与 usp_assess_recalc 一致。
 */
public final class AssessReportCalc {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AssessReportCalc() {
    }

    public static BigDecimal actual(BigDecimal leader, BigDecimal client, BigDecimal self,
                                    BigDecimal wLeader, BigDecimal wClient, BigDecimal wSelf) {
        return leader.multiply(wLeader)
                .add(client.multiply(wClient))
                .add(self.multiply(wSelf))
                .divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }

    /** 标准分为 0 或任一参数为空时返回 null */
    public static BigDecimal rate(BigDecimal actual, BigDecimal standard) {
        if (actual == null || standard == null || standard.signum() == 0) return null;
        return actual.divide(standard, 6, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /** 按 sort_order 顺序取第一个命中的区间；得分率为空返回 null */
    public static String gradeOf(BigDecimal rate, List<AssessSheetVO.GradeBand> bands) {
        if (rate == null) return null;
        for (AssessSheetVO.GradeBand b : bands) {
            boolean aboveLower = b.getLowerBound() == null || rate.compareTo(b.getLowerBound()) > 0;
            boolean belowUpper = b.getUpperBound() == null || rate.compareTo(b.getUpperBound()) <= 0;
            if (aboveLower && belowUpper) return b.getLevelName();
        }
        return null;
    }

    /** 任一为空则返回 null，否则求和 */
    public static BigDecimal sumOrNull(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v == null) return null;
            sum = sum.add(v);
        }
        return sum;
    }
}
