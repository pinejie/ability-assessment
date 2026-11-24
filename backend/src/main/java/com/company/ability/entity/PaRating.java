package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评级：某个评价人对某个能力项打的那一档。
 *
 * <p>rater_type：SELF（自评）/ LEADER（直接上级）/ RATER360（360 测评人）。
 * 旧 CLIENT 退役 —— 360 不再是"客户"，是发起人手工选的一组内部人员，语义已变。</p>
 *
 * <p>{@code levelName} / {@code score} 是<b>快照</b>：等级配置被改分值后，
 * 历史评级仍按当时的刻度解释，不会因为改配置而让旧报告变数。</p>
 */
@Data
@TableName("uf_pa_rating")
public class PaRating {

    /** 自评 */
    public static final String TYPE_SELF = "SELF";
    /** 直接上级 */
    public static final String TYPE_LEADER = "LEADER";
    /** 360 测评人 */
    public static final String TYPE_360 = "RATER360";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long sheetId;

    private Long itemId;

    /** SELF / LEADER / RATER360 */
    private String raterType;

    private Long raterResourceId;

    /** uf_pa_item_level.id */
    private Long levelId;

    /** 等级名快照 */
    private String levelName;

    /** 分值快照 */
    private BigDecimal score;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
