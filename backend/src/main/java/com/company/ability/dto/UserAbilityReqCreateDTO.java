package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * 人员能力要求创建DTO
 */
@Data
public class UserAbilityReqCreateDTO {

    @NotNull(message = "人员ID不能为空")
    private Long resourceId;

    @NotEmpty(message = "能力要素配置不能为空")
    private List<UserAbilityElementConfig> elementConfigs;

    /**
     * 能力要素配置
     */
    @Data
    public static class UserAbilityElementConfig {
        @NotNull(message = "能力要素ID不能为空")
        private Long elementId;

        @NotNull(message = "等级ID不能为空")
        private Long levelId;

        @NotNull(message = "分数不能为空")
        private BigDecimal score;

        private String description;
    }
}
