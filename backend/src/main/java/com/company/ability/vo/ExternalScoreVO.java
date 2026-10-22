package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 外部评分导入响应对象。
 */
@Data
public class ExternalScoreVO {

    /** 录入页上下文：某人某年度的非流程要素 + 当前外部值 */
    @Data
    public static class SheetContext {
        private Long sheetId;
        private Integer year;
        private Long resourceId;
        private String resourceName;
        private Integer sheetStatus;
        private BigDecimal actualScore;
        private String gradeName;
        private BigDecimal annualScore;
        private Boolean periodOpen;
        private List<ElementCell> elements;
    }

    /** 非流程要素单元格 */
    @Data
    public static class ElementCell {
        private Long elementId;
        private String elementName;
        private String categoryName;
        private Integer requiredLevelNo;
        /** 当前已导入的外部评级（未导入为空） */
        private Integer currentLevelNo;
        /** 当前外部评级的原始分 */
        private BigDecimal originalScore;
    }

    /** preview 结果 */
    @Data
    public static class PreviewResult {
        /** 逐行校验结果 */
        private List<LineResult> lines;
        /** 按人覆盖与状态摘要 */
        private List<Coverage> coverage;
        /** 无 BLOCK 行时为 true（可提交） */
        private boolean commitable;
    }

    @Data
    public static class LineResult {
        private Integer lineNo;
        /** RATING / ANNUAL */
        private String kind;
        private Long resourceId;
        private Long elementId;
        /** PASS / WARN / BLOCK */
        private String status;
        private String message;
    }

    @Data
    public static class Coverage {
        private Long resourceId;
        private String resourceName;
        private Integer externalTotal;
        /** 本次提交后可覆盖的外部要素数 */
        private Integer externalCoveredAfter;
        /** 提交后是否足以出总分（领导/自评/客户评全）无法预判，仅给覆盖提示 */
        private Integer sheetStatus;
    }

    /** commit 结果（逐人回显，§11 反馈口径） */
    @Data
    public static class CommitResult {
        private List<CommitPersonResult> persons;
    }

    @Data
    public static class CommitPersonResult {
        private Long resourceId;
        /** IMPORT / REFILL / CLEAR / ANNUAL */
        private String action;
        private boolean success;
        private String message;
        private Long sheetId;
        private Integer sheetStatus;
        private BigDecimal actualScore;
        private String gradeName;
        private Integer externalTotal;
        private Integer externalCovered;
    }
}
