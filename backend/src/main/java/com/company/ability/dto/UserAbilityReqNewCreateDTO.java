package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * 人员能力要求创建DTO
 */
@Data
public class UserAbilityReqNewCreateDTO {

    @NotNull(message = "人员ID不能为空")
    private Long resourceId;

    @NotEmpty(message = "能力要素配置不能为空")
    private List<ElementItem> items;

    private String description;

    /**
     * 年度，缺省为当前年份
     */
    private Integer assessYear;

    /**
     * 是否同步到测评汇总页面，缺省为 true（新增时始终生成页面）
     */
    private Boolean syncAssess;

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
