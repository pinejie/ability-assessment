package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评分权重配置VO
 */
@Data
public class ScoreWeightVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 分公司ID
     */
    private Long companyId;

    /**
     * 分公司名称
     */
    private String companyName;

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
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新人ID
     */
    private Long updateBy;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
