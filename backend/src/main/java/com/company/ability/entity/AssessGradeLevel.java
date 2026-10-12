package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 等级标准（区间左开右闭 (lowerBound, upperBound]，null 表示无穷）
 */
@Data
@TableName("uf_assess_grade_level")
public class AssessGradeLevel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private String levelName;

    private BigDecimal lowerBound;

    private BigDecimal upperBound;

    private Integer sortOrder;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
