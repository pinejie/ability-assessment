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
 * 能力项等级：每个能力项自己一套，数量任意。
 *
 * <p>相对旧 {@code uf_ability_element_level} + {@code uf_assess_level_score} 的双表拆分
 * 合成一张：等级号（level_no）概念取消，排序就是 {@code sort}。</p>
 *
 * <ul>
 *   <li>{@code levelName}  入门/新手、精进/能手…</li>
 *   <li>{@code score}      分值。旧库里"维度等级分值"和"要素等级分"两处并存、
 *                          还要靠 rate_value 折算，是算错分的主要来源，现已合并为一处</li>
 *   <li>{@code levelDesc}  行为描述（Excel 每级那一整段文字）。可空，
 *                          但只给"精进/能手"四个字，评价人无从判断「知人善用」该打几分</li>
 * </ul>
 *
 * <p>服务层校验（不落库）：同项内 sort 唯一、score 唯一、至少 2 档、被评级引用后禁删。</p>
 */
@Data
@TableName("uf_pa_item_level")
public class PaItemLevel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long itemId;

    private String levelName;

    private BigDecimal score;

    /** 行为描述，可空 */
    private String levelDesc;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // 等级是配置明细，不做逻辑删除：删除即物理删除，引用保护在服务层
}
