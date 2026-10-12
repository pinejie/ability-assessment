package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 等级分值（等级序号 1-5 → 分值）
 */
@Data
@TableName("uf_assess_level_score")
public class AssessLevelScore {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Integer levelNo;

    private BigDecimal score;

    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
