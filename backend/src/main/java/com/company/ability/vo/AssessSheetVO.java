package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测评汇总页面的展示对象（只读）
 */
public class AssessSheetVO {

    /** 列表行 */
    @Data
    public static class ListItem {
        private Long id;
        private Integer year;
        private Long resourceId;
        private String resourceName;
        private Long departmentId;
        /** 0 待评价 1 评价中 2 已完成 */
        private Integer status;
        private Boolean needRecalc;
        private Boolean reqChanged;
        private Integer clientCount;
        private BigDecimal standardScore;
        private BigDecimal leaderScore;
        private BigDecimal clientScore;
        private BigDecimal selfScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
        private LocalDateTime updateTime;
    }

    /** 详情（弹窗） */
    @Data
    public static class Detail {
        private Long id;
        private Integer year;
        private Long resourceId;
        private String resourceName;
        private Long departmentId;
        private Long leaderResourceId;
        private String leaderName;
        private Integer status;
        private Boolean needRecalc;
        private Boolean reqChanged;
        private Integer clientCount;
        private BigDecimal standardScore;
        private BigDecimal leaderScore;
        private BigDecimal clientScore;
        private BigDecimal selfScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
        /** 客户序号列表，用于动态生成"客户N"列 */
        private List<Integer> clientSeqs;
        /** 每个客户在有效要素上的得分合计，与 clientSeqs 顺序一致（合计行使用） */
        private List<BigDecimal> clientSeqTotals;
        private List<Row> rows;
    }

    /** 要素行（对齐 Excel 测评汇总 sheet） */
    @Data
    public static class Row {
        private Long elementId;
        private String categoryName;
        private BigDecimal categoryWeight;
        private String elementName;
        private Integer requiredLevelNo;
        private BigDecimal standardScore;
        private Integer leaderLevelNo;
        private BigDecimal leaderScore;
        /** 每个客户的评级与得分，顺序与 clientSeqs 一致 */
        private List<Rating> clients;
        private BigDecimal clientAvgScore;
        private Integer selfLevelNo;
        private BigDecimal selfScore;
        /** 非流程要素行且有外部导入评级（sql/25 外部导入通道） */
        private Boolean external;
        /** 外部导入的原始分（仅 external 行；页面显示"x 级（原始分 y）"） */
        private BigDecimal originalScore;
    }

    @Data
    public static class Rating {
        private Integer seq;
        private Integer levelNo;
        private BigDecimal score;
    }

    /** 年度选项 */
    @Data
    public static class PeriodItem {
        private Long id;
        private Integer year;
        private Integer status;
    }

    /** 个人测评报告（对齐 Excel "个人测评报告1.19"） */
    @Data
    public static class Report {
        private Long sheetId;
        private Integer year;
        private String companyName;
        private String resourceName;
        private String departmentName;
        private String jobTitleName;
        private Integer age;
        /** 年度已关闭 */
        private Boolean closed;
        /** 读取的是年度关闭时冻结的快照 */
        private Boolean frozen;
        /** 年度分（人工录入，留空） */
        private BigDecimal annualScore;
        /** 是否所有要素都已评级（否则合计与等级显示"未完成"） */
        private Boolean complete;
        private List<CategoryRow> categories;
        private Total total;
        /** 等级区间，用于图表参考线与文字 */
        private List<GradeBand> gradeBands;
        /** 上级评价（泛微钩子写入） */
        private String leaderComment;
        /** 客户评价（泛微钩子写入） */
        private String clientComment;
    }

    /** 报告中的类别行 */
    @Data
    public static class CategoryRow {
        private String categoryName;
        private BigDecimal weight;
        private BigDecimal standardScore;
        private BigDecimal leaderScore;
        private BigDecimal clientScore;
        private BigDecimal selfScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
    }

    /** 报告合计行（取自页面头，与页面一致） */
    @Data
    public static class Total {
        private BigDecimal standardScore;
        private BigDecimal leaderScore;
        private BigDecimal clientScore;
        private BigDecimal selfScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
    }

    /** 等级区间：(lowerBound, upperBound]，null 表示无穷 */
    @Data
    public static class GradeBand {
        private String levelName;
        private BigDecimal lowerBound;
        private BigDecimal upperBound;
    }
}
