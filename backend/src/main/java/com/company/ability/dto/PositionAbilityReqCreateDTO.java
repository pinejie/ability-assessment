package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * 岗位能力要求创建DTO
 */
@Data
public class PositionAbilityReqCreateDTO {

    @NotNull(message = "部门ID不能为空")
    private Long departmentId;

    @NotNull(message = "岗位ID不能为空")
    private Long jobTitleId;

    @NotEmpty(message = "能力要素列表不能为空")
    @Valid
    private List<ElementItem> items;

    private String description;

    @Data
    public static class ElementItem {
        @NotNull(message = "类别ID不能为空")
        private Long categoryId;

        @NotNull(message = "要素ID不能为空")
        private Long elementId;

        /** 实现岗位能力类别权重（%）：同一类别下的要素共用该值，不按要素数均分 */
        @NotNull(message = "权重不能为空")
        @DecimalMin(value = "0.00", message = "权重不能小于 0")
        @DecimalMax(value = "100.00", message = "权重不能大于 100")
        private BigDecimal weight;
    }
}
