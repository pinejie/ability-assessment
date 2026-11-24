package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 算分结果（一个测评页）。
 *
 * <p>字段与 {@code uf_pa_sheet} 的得分列一一对应，算完即回写，
 * 调用方（提交、重算）直接拿它拼响应。</p>
 */
@Data
public class PaSheetScoreVO {

    private Long sheetId;

    /** 0-未评价 1-部分提交 2-已算分 */
    private Integer status;

    /** 有效能力项数 */
    private Integer itemCount;

    /** 实际上有项得分的能力项数（未评价的项不进分母） */
    private Integer scoredItemCount;

    /** 自评加权平均，只展示 */
    private BigDecimal selfScore;

    /** 直接上级加权平均 */
    private BigDecimal leaderScore;

    /** 360 测评人加权平均（每人先按项平均，再按权重汇总） */
    private BigDecimal rater360Score;

    /** 加权平均总得分 */
    private BigDecimal totalScore;

    /** 命中分值区间得到的等级说明 */
    private String bandName;

    /** 是否本次真正算出了分数（有项得分才算） */
    private Boolean scored;
}
