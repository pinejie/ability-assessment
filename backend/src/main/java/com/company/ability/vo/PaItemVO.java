package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 能力项 VO（含等级明细）。
 *
 * <p>等级随能力项一起返回：页面上每行要显示「3 档」并能在弹窗里直接编辑，
 * 单独拉一次等级列表会让行表渲染多等一个来回。</p>
 */
@Data
public class PaItemVO {

    private Long id;

    private Long postId;

    private Long categoryId;

    private String categoryName;

    private String itemCode;

    private String itemName;

    private String definition;

    /** 权重，百分数：8.0000 = 8% */
    private BigDecimal weight;

    private Integer raterLeader;

    private Integer rater360;

    /** 评价主体描述原文（导入时的 Excel 原文），仅展示 */
    private String raterText;

    private Integer sort;

    private Integer status;

    private LocalDateTime updateTime;

    private List<PaItemLevelVO> levels = new ArrayList<>();

    /** 等级档数（列表列用；未配等级时为 0，页面标黄提醒） */
    private Integer levelCount;
}
