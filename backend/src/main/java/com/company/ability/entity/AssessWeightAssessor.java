package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评对象权重配置（LEADER / CLIENT / SELF）
 */
@Data
@TableName("uf_assess_weight_assessor")
public class AssessWeightAssessor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /** 所属维度（类租户） */
    private Long dimensionId;

    private String assessorCode;

    private BigDecimal weight;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
