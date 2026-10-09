package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 人员能力要求更新DTO
 */
@Data
public class UserAbilityReqUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long resourceId;

    private Long elementId;

    private Long levelId;

    private BigDecimal score;

    private String description;
}
