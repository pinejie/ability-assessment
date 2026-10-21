package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 测评维度更新DTO
 */
@Data
public class AssessDimensionUpdateDTO {

    /**
     * 主键ID
     */
    @NotNull(message = "ID不能为空")
    private Long id;

    /**
     * 维度名称
     */
    @Size(max = 100, message = "维度名称长度不能超过100")
    private String dimensionName;

    /**
     * 维度编码
     */
    @Size(max = 50, message = "维度编码长度不能超过50")
    private String dimensionCode;

    /**
     * 描述
     */
    @Size(max = 500, message = "描述长度不能超过500")
    private String description;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status;

    /**
     * 排序号
     */
    private Integer sort;
}
