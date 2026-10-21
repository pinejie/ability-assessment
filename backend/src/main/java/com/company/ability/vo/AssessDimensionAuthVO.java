package com.company.ability.vo;

import lombok.Data;

/**
 * 维度授权条目VO（带名称展示）
 */
@Data
public class AssessDimensionAuthVO {

    /**
     * 授权记录ID
     */
    private Long id;

    /**
     * 授权类型：1-分部，2-人员
     */
    private Integer authType;

    /**
     * 授权对象ID
     */
    private Long authId;

    /**
     * 授权对象名称（分部名/人员姓名）
     */
    private String authName;
}
