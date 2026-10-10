package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
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
    private List<ElementItem> items;

    private String description;

    @Data
    public static class ElementItem {
        @NotNull(message = "类别ID不能为空")
        private Long categoryId;

        @NotNull(message = "要素ID不能为空")
        private Long elementId;
    }
}
