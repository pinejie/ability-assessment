package com.company.ability.vo;

import lombok.Data;

import java.util.List;

/**
 * 能力结构导入的预览与写入回执（方案 14 §四/§六）。
 * 状态口径：CREATE=将新增 / SKIP=已存在将跳过 / PENDING=需人确认（不写入） / BLOCK=阻断整批 / WARN=写入但需告知。
 */
@Data
public class AbilityStructureVO {

    /** 单行校验/计划结果 */
    @Data
    public static class Line {
        /** CATEGORY / ELEMENT / LEVEL / POSITION / USER */
        private String section;
        private String name;
        private String status;
        private String message;
        /** 归属说明：类别 / 部门·岗位 / 工号 */
        private String parent;
    }

    @Data
    public static class Counts {
        private int categoryCreate;
        private int categorySkip;
        private int elementCreate;
        private int elementSkip;
        private int levelCreate;
        private int positionCreate;
        private int positionKeep;
        private int positionOverwrite;
        private int userCreate;
        private int userPending;
        private int unresolved;
        private int blocked;
    }

    /** 预览结果 */
    @Data
    public static class PreviewResult {
        private List<Line> lines;
        private Counts counts;
        /** 无 BLOCK 行时为 true（可提交）；PENDING 不阻断提交，但其对象不写入 */
        private boolean commitable;
        private Integer year;
        private Long periodId;
    }

    /** 写入回执 */
    @Data
    public static class CommitResult {
        private Counts counts;
        private Long batchId;
        private List<Line> skipped;
        /** 汇总同步失败的人（要求已写入，汇总可重试） */
        private List<SyncFailure> failed;
    }

    @Data
    public static class SyncFailure {
        private Long resourceId;
        private String resourceName;
        private String message;
    }
}
