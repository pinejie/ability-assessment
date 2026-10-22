package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 外部评分导入（非流程要素评级 / 年度分）请求对象。
 * 方案：project/能力测评/10-外部评分导入与Agent交互方案.md（§11 最终数据要求）。
 */
public class ExternalScoreDTO {

    /** preview / commit 共用载荷 */
    @Data
    public static class ImportRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;
        /** 要素评级行（可空；与 annuals 至少一项） */
        @Valid
        private List<RatingRow> ratings;
        /** 年度分行（可空） */
        @Valid
        private List<AnnualRow> annuals;
    }

    @Data
    public static class RatingRow {
        @NotNull(message = "被测人ID不能为空")
        private Long resourceId;
        @NotNull(message = "要素ID不能为空")
        private Long elementId;
        /** 评级 1–5（换算在外部完成，本系统不做分数换算） */
        @NotNull(message = "评级不能为空")
        private Integer levelNo;
        /** 外部换算前的原始分（仅留存追溯，不参与计算） */
        private BigDecimal originalScore;
    }

    @Data
    public static class AnnualRow {
        @NotNull(message = "被测人ID不能为空")
        private Long resourceId;
        @NotNull(message = "年度分不能为空")
        private BigDecimal annualScore;
    }

    /** refill / clear 请求 */
    @Data
    public static class PersonRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;
        @NotNull(message = "被测人ID不能为空")
        private Long resourceId;
    }
}
