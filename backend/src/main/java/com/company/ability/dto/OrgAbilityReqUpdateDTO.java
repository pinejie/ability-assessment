package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

/**
 * 部门能力要求更新DTO
 */
@Data
public class OrgAbilityReqUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long departmentId;

    private Long elementId;

    private String description;
}
