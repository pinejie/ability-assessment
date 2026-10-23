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

    /** 等级号：等级数量由「能力测评配置-等级分值」决定，此处不再限 1-5 */
    @NotNull(message = "等级不能为空")
    @Min(value = 1, message = "等级最小为1")
    @Max(value = 99, message = "等级最大为99")
    private Integer level;

    @NotBlank(message = "等级名称不能为空")
    private String levelName;

    private String levelRequirement;

    @NotNull(message = "分数不能为空")
    private BigDecimal score;
}
