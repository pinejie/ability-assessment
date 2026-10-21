package com.company.ability.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测评维度VO
 */
@Data
public class AssessDimensionVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 维度名称
     */
    private String dimensionName;

    /**
     * 维度编码
     */
    private String dimensionCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status;

    /**
     * 排序号
     */
    private Integer sort;

    /**
     * 授权条目数（0 表示不限制，所有人可用）
     */
    private Long authCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
