package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评分权重配置实体
 */
@Data
@TableName("uf_score_weight")
public class ScoreWeight {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 分公司ID
     */
    private Long companyId;

    /**
     * 权重名称
     */
    private String weightName;

    /**
     * 类别权重（百分比）
     */
    private BigDecimal categoryWeight;

    /**
     * 要素权重（百分比）
     */
    private BigDecimal elementWeight;

    /**
     * 描述
     */
    private String description;

    /**
     * 创建人ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新人ID
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 是否删除：1-已删除，0-未删除
     */
    @TableLogic
    private Integer deleted;
}
