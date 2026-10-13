package com.company.ability.service;

import com.company.ability.vo.AssessSheetVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 用 Excel 成绩分析.xlsx（测评汇总 / 个人测评报告1.19）的数值校验报告计算 */
class AssessReportCalcTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static List<AssessSheetVO.GradeBand> bands() {
        return List.of(
                band("不合格", null, "80.00"),
                band("基本胜任", "80.00", "95.00"),
                band("胜任", "95.00", "110.00"),
                band("优秀", "110.00", "120.00"),
                band("卓越", "120.00", null));
    }

    private static AssessSheetVO.GradeBand band(String name, String lower, String upper) {
        AssessSheetVO.GradeBand b = new AssessSheetVO.GradeBand();
        b.setLevelName(name);
        b.setLowerBound(lower == null ? null : d(lower));
        b.setUpperBound(upper == null ? null : d(upper));
        return b;
    }

    @Test
    @DisplayName("合计：实际得分 70.87、得分率 86.66%、基本胜任（对齐 Excel O10/O12/O13）")
    void totalMatchesExcel() {
        BigDecimal actual = AssessReportCalc.actual(d("70.5"), d("70.4126984126984"), d("78.4285714285714"),
                d("70"), d("25"), d("5"));
        assertEquals(0, actual.compareTo(d("70.8746")), "actual=" + actual);
        BigDecimal rate = AssessReportCalc.rate(actual, d("81.7857142857143"));
        assertEquals(0, rate.compareTo(d("86.66")), "rate=" + rate);
        assertEquals("基本胜任", AssessReportCalc.gradeOf(rate, bands()));
    }

    @Test
    @DisplayName("通用知识：领导5/客户5/自评5，标准5 → 得分率100%，胜任（对齐 Excel G12=1）")
    void categoryMatchesExcel() {
        BigDecimal actual = AssessReportCalc.actual(d("5"), d("5"), d("5"), d("70"), d("25"), d("5"));
        BigDecimal rate = AssessReportCalc.rate(actual, d("5"));
        assertEquals(0, rate.compareTo(d("100.00")));
        assertEquals("胜任", AssessReportCalc.gradeOf(rate, bands()));
    }

    @Test
    @DisplayName("区间左开右闭：95.00 属于基本胜任，95.01 属于胜任，110.00 属于胜任")
    void gradeBoundaries() {
        assertEquals("基本胜任", AssessReportCalc.gradeOf(d("95.00"), bands()));
        assertEquals("胜任", AssessReportCalc.gradeOf(d("95.01"), bands()));
        assertEquals("胜任", AssessReportCalc.gradeOf(d("110.00"), bands()));
        assertEquals("卓越", AssessReportCalc.gradeOf(d("120.01"), bands()));
        assertNull(AssessReportCalc.gradeOf(null, bands()));
    }

    @Test
    @DisplayName("标准分为 0（能力标准类别）得分率与等级为空")
    void zeroStandardHasNoRate() {
        assertNull(AssessReportCalc.rate(d("0"), d("0")));
        assertNull(AssessReportCalc.rate(null, d("3")));
    }

    @Test
    @DisplayName("sumOrNull：任一为空则整体为空")
    void sumOrNullRequiresAllValues() {
        assertEquals(0, AssessReportCalc.sumOrNull(List.of(d("1"), d("2.5"))).compareTo(d("3.5")));
        assertNull(AssessReportCalc.sumOrNull(java.util.Arrays.asList(d("1"), null)));
    }
}
