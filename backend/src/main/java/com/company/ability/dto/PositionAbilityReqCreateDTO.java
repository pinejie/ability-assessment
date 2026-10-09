package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

/**
 * 岗位能力要求创建DTO
 */
@Data
public class PositionAbilityReqCreateDTO {

    @NotNull(message = "岗位ID不能为空")
    private Long jobTitleId;

    @NotNull(message = "能力要素ID不能为空")
    private Long elementId;

    private String description;
}
