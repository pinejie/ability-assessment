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
}
