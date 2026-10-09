package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 能力类别更新DTO
 */
@Data
public class AbilityCategoryUpdateDTO {

    /**
     * 主键ID
     */
    @NotNull(message = "ID不能为空")
    private Long id;

    /**
     * 类别名称
     */
    @Size(max = 100, message = "类别名称长度不能超过100")
    private String categoryName;

    /**
     * 类别描述
     */
    @Size(max = 500, message = "类别描述长度不能超过500")
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
