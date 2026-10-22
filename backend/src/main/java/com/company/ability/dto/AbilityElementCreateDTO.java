package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 能力要素创建DTO
 */
@Data
public class AbilityElementCreateDTO {

    /**
     * 要素名称
     */
    @NotBlank(message = "要素名称不能为空")
    @Size(max = 100, message = "要素名称长度不能超过100")
    private String elementName;

    /**
     * 要素编码
     */
    @Size(max = 50, message = "要素编码长度不能超过50")
    private String elementCode;

    /**
     * 所属类别ID
     */
    @NotNull(message = "所属类别不能为空")
    private Long categoryId;

    /**
     * 要素描述
     */
    @Size(max = 500, message = "要素描述长度不能超过500")
    private String description;

    /**
     * 测评流程中是否需要评价：1-需评价（默认），0-不需评价（联动 allowClientAssess=0）
     */
    private Integer assessInFlow = 1;

    /**
     * 是否允许客户评价：1-允许，0-不允许
     */
    private Integer allowClientAssess = 1;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status = 1;

    /**
     * 排序号
     */
    private Integer sort = 0;
}
