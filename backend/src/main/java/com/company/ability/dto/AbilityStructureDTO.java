package com.company.ability.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 能力结构导入交付稿（方案 14 §三，kind = ABILITY_STRUCTURE）。
 * 由 agent 解析部门表格后提交，写入前由 AbilityStructureImportService 做确定性校验；
 * 分值只认 uf_assess_level_score，载荷里的 score 仅在人员要求行显式给分时使用。
 */
@Data
public class AbilityStructureDTO {

    /** 固定为 ABILITY_STRUCTURE（载荷里回显，便于回落原稿时识别） */
    private String kind;
    private String sessionId;
    private Long dimensionId;
    /** 人员能力要求年度（结构导入的年度在载荷内，draft.year 可为空） */
    private Integer year;

    private List<CategoryRow> categories;
    private List<ElementRow> elements;
    private List<PositionReqRow> positionReqs;
    private List<UserReqRow> userReqs;

    /** agent 交代的文件行去向（对账用，格式与评分导入一致） */
    /** 行号带表名：「表名!行号」——结构工作簿多表，纯行号会撞（见 AgentBridgeService.reconcileStructureCoverage） */
    private List<String> coveredRows;
    private List<SkippedRow> skipped;
    private Boolean unreconciled;

    @Data
    public static class CategoryRow {
        private String name;
        private Integer sort;
        private String description;
    }

    @Data
    public static class ElementRow {
        private String categoryName;
        private String name;
        /** 是否进流程评价：1=需评价（默认），0=仅任职标准 */
        private Integer assessInFlow;
        private Integer allowClientAssess;
        private String description;
        private List<LevelRow> levels;
    }

    @Data
    public static class LevelRow {
        /** 等级号，必须命中当前维度 uf_assess_level_score 的 level_no */
        private Integer levelNo;
        /** 等级描述，写入 uf_ability_element_level.level_requirement（绝不放分值） */
        private String desc;
        /** 等级名称，缺省取 uf_assess_level_score.level_desc */
        private String levelName;
    }

    @Data
    public static class PositionReqRow {
        private String departmentName;
        private String jobTitleName;
        private String description;
        /** 已存在时是否覆盖（默认 false：保留已有，跳过本行） */
        private Boolean overwrite;
        private List<PositionItem> items;
    }

    @Data
    public static class PositionItem {
        private String categoryName;
        private String elementName;
        /** 能力类别权重 0~100，同类别必须一致 */
        private BigDecimal weight;
    }

    @Data
    public static class UserReqRow {
        /** 工号优先（HrmResource.loginid），可空 */
        private String resourceNo;
        private String resourceName;
        private String departmentName;
        private String description;
        private List<UserItem> items;
    }

    @Data
    public static class UserItem {
        private String elementName;
        private Integer levelNo;
        /** 显式给分（表里写了分才用），缺省按 levelNo 取等级分 */
        private BigDecimal score;
    }

    @Data
    public static class SkippedRow {
        private String rowNo;
        private String raw;
        private String reason;
    }
}
