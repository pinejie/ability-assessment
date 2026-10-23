package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 能力测评配置视图对象
 */
public class AssessConfigVO {

    @Data
    public static class AbilityWeightVO {
        private Long categoryId;
        private String categoryName;
        /** 是否启用（status=1），停用项不计入合计 */
        private Boolean enabled;
        private BigDecimal weight;
    }

    @Data
    public static class AssessorWeightVO {
        private String assessorCode;
        private String assessorName;
        private BigDecimal weight;
    }

    @Data
    public static class GradeLevelVO {
        private String levelName;
        private BigDecimal lowerBound;
        private BigDecimal upperBound;
        private Integer sortOrder;
    }

    @Data
    public static class LevelScoreVO {
        private Integer levelNo;
        private String levelDesc;
        private Integer rateValue;
        private BigDecimal score;
    }
}
