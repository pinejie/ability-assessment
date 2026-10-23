package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 等级分值（等级唯一定义源：等级 → 描述/对应的评价/分值；等级数量可配置）
 */
@Data
@TableName("uf_assess_level_score")
public class AssessLevelScore {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /** 所属维度（类租户） */
    private Long dimensionId;

    /** 等级号（要求等级按此匹配分值） */
    private Integer levelNo;

    /** 等级描述（如"知识级"，配置等级弹窗展示的等级名称） */
    private String levelDesc;

    /** 对应的评价（评价人打出的评级值；评级按此匹配分值，维度内唯一） */
    private Integer rateValue;

    private BigDecimal score;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
