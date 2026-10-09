package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * 能力要素等级创建DTO
 */
@Data
public class AbilityElementLevelCreateDTO {

    @NotNull(message = "能力要素ID不能为空")
    private Long elementId;

    @NotNull(message = "等级不能为空")
    @Min(value = 1, message = "等级最小为1")
    @Max(value = 5, message = "等级最大为5")
    private Integer level;

    @NotBlank(message = "等级名称不能为空")
    private String levelName;

    private String levelRequirement;

    @NotNull(message = "分数不能为空")
    private BigDecimal score;
}
