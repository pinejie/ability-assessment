package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 填写页的一个能力项（含该项自己的等级卡片）。
 *
 * <p>等级一次给全（名称 + 分值 + 描述）：评价人要看着行为描述判断该打几分，
 * 只给「精进/能手」四个字等于让人凭感觉（口径 #6）。</p>
 */
@Data
public class PaFillItemVO {

    private Long itemId;

    private Long categoryId;

    private String categoryName;

    private String itemCode;

    private String itemName;

    /** 能力项定义 */
    private String definition;

    private Integer sortNo;

    /** 权重（百分数），页面只提示「本项占比」，不参与评价人决策 */
    private BigDecimal weight;

    /** 该等级清单 */
    private java.util.List<PaItemLevelVO> levels;
}
