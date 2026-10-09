package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

/**
 * 部门能力要求创建DTO
 */
@Data
public class OrgAbilityReqCreateDTO {

    @NotNull(message = "部门ID不能为空")
    private Long departmentId;

    @NotNull(message = "能力要素ID不能为空")
    private Long elementId;

    private String description;
}
