package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

/**
 * 岗位能力要求更新DTO
 */
@Data
public class PositionAbilityReqUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long jobTitleId;

    private Long elementId;

    private String description;
}
