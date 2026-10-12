package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * 能力测评配置保存请求体
 */
public class AssessConfigDTO {

    @Data
    public static class AbilityWeightSaveDTO {
        @NotNull(message = "companyId不能为空")
        private Long companyId;
        @Valid
        private List<AbilityWeightItem> items;
    }

    @Data
    public static class AbilityWeightItem {
        private Long categoryId;
        private BigDecimal weight;
    }

    @Data
    public static class AssessorWeightSaveDTO {
        @NotNull(message = "companyId不能为空")
        private Long companyId;
        @Valid
        private List<AssessorWeightItem> items;
    }

    @Data
    public static class AssessorWeightItem {
        private String assessorCode;
        private BigDecimal weight;
    }

    @Data
    public static class GradeLevelSaveDTO {
        @NotNull(message = "companyId不能为空")
        private Long companyId;
        @Valid
        private List<GradeLevelItem> items;
    }

    @Data
    public static class GradeLevelItem {
        private String levelName;
        private BigDecimal lowerBound;
        private BigDecimal upperBound;
    }

    @Data
    public static class LevelScoreSaveDTO {
        @NotNull(message = "companyId不能为空")
        private Long companyId;
        @Valid
        private List<LevelScoreItem> items;
    }

    @Data
    public static class LevelScoreItem {
        private Integer levelNo;
        private BigDecimal score;
    }
}
