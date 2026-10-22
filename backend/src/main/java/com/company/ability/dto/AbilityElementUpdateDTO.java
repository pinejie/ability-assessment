package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 能力要素更新DTO
 */
@Data
public class AbilityElementUpdateDTO {

    /**
     * 主键ID
     */
    @NotNull(message = "ID不能为空")
    private Long id;

    /**
     * 要素名称
     */
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
    private Long categoryId;

    /**
     * 要素描述
     */
    @Size(max = 500, message = "要素描述长度不能超过500")
    private String description;

    /**
     * 测评流程中是否需要评价：1-需评价，0-不需评价（联动 allowClientAssess=0）
     */
    private Integer assessInFlow;

    /**
     * 是否允许客户评价：1-允许，0-不允许
     */
    private Integer allowClientAssess;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status;

    /**
     * 排序号
     */
    private Integer sort;
}
