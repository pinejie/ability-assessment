package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 能力要素等级配置
 */
@Data
@TableName("uf_ability_element_level")
public class AbilityElementLevel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("element_id")
    private Long elementId;

    @TableField("level")
    private Integer level;

    @TableField("level_name")
    private String levelName;

    @TableField("level_requirement")
    private String levelRequirement;

    @TableField("score")
    private BigDecimal score;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
