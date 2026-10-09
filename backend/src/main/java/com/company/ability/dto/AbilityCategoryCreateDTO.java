package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 能力类别创建DTO
 */
@Data
public class AbilityCategoryCreateDTO {

    /**
     * 类别名称
     */
    @NotBlank(message = "类别名称不能为空")
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
    private Integer status = 1;

    /**
     * 排序号
     */
    private Integer sort = 0;
}
