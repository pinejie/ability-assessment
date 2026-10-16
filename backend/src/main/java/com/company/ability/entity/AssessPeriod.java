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

    private String periodName;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
