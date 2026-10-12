package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 能力类型权重配置（粒度：能力类别）
 */
@Data
@TableName("uf_assess_weight_ability")
public class AssessWeightAbility {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long categoryId;

    /** 权重（百分比），null 表示未填写 */
    private BigDecimal weight;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
