package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 测评结果：AssessPeriod（表 uf_assess_period）
 */
@Data
@TableName("uf_assess_period")
public class AssessPeriod {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    /**
     * 表无此列，仅历史保留（修复 selectById 报 Invalid column name 'period_name' 的隐性 bug）
     */
    @TableField(exist = false)
    private String periodName;

    private Integer year;

    private Integer status;

    /**
     * 所属维度（类租户）
     */
    private Long dimensionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
