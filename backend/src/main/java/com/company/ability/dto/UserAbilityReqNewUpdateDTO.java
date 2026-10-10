package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * 人员能力要求更新DTO
 */
@Data
public class UserAbilityReqNewUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long resourceId;

    private List<ElementItem> items;

    private String description;

    /**
     * 能力要素明细项
     */
    @Data
    public static class ElementItem {
        @NotNull(message = "能力类别ID不能为空")
        private Long categoryId;

        @NotNull(message = "能力要素ID不能为空")
        private Long elementId;

        private Long levelId;

        private BigDecimal score;
    }
}
