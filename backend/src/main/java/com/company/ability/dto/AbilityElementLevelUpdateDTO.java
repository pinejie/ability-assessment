package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 能力要素等级更新DTO
 */
@Data
public class AbilityElementLevelUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private String levelName;

    private String levelRequirement;

    private BigDecimal score;
}
