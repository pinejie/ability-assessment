package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.util.List;

/**
 * 岗位能力要求更新DTO
 */
@Data
public class PositionAbilityReqUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long departmentId;

    private Long jobTitleId;

    private List<PositionAbilityReqCreateDTO.ElementItem> items;

    private String description;
}
