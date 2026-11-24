package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入预览：把「点了确认会发生什么」逐条列清楚，用户第二次确认看的就是它。
 *
 * <p><b>为什么预览要单独一个 DTO，而不是复用草稿</b>：草稿说的是「文件里有什么」，
 * 预览说的是「库里会怎么变」。后者要合并三件草稿里没有的信息：</p>
 * <ol>
 *   <li>同部门同名岗位是否已存在、差异在哪（新增/改动/移除哪些能力项）</li>
 *   <li>改模型会波及哪些测评：进行中的要撤回、已封存的要重算</li>
 *   <li>被移除的能力项有没有评级引用（决定「删」还是「停用」）</li>
 * </ol>
 * <p>这三件事每一条都可能让用户改主意，混在草稿里就没人看得见了。</p>
 */
@Data
public class PaImportPreview {

    private List<PostPreview> posts = new ArrayList<>();

    /** 测评影响汇总（跨岗位合并） */
    private Impact impact = new Impact();

    /** 阻断项：有的话「确认导入」必须灰掉。复用待确认项的文案 */
    private List<PaImportQuestion> blockers = new ArrayList<>();

    /** 提醒项：不阻断，但要让用户看见后再点 */
    private List<String> warnings = new ArrayList<>();

    /** 是否需要用户为「重算封存报告 / 撤回进行中任务」再做决定（前端据此弹二级确认） */
    private boolean needsImpactDecision;

    // ------------------------------------------------------------------ 岗位

    @Data
    public static class PostPreview {
        private String sheetName;
        private String postName;
        private Long departmentId;
        private String departmentName;

        /** 库里已有同部门同名岗位 */
        private boolean exists;
        private Long postId;

        private int categoryCount;
        private int itemCount;
        private int levelCount;
        private int bandCount;

        /** 权重合计（百分数），给用户核一眼 */
        private java.math.BigDecimal weightTotal;

        /** 有差异时逐项列出；一致时 changed/added/removed 全空 */
        private List<String> unchanged = new ArrayList<>();
        private List<String> changed = new ArrayList<>();
        private List<String> added = new ArrayList<>();
        private List<String> removed = new ArrayList<>();

        /** 被移除的能力项里有评级引用的数量：有的话只能停用不能删 */
        private int removedButReferenced;

        /** 这一岗位会怎么写：CREATE / OVERWRITE / SKIP */
        private String action;

        /** 一句话结论，前端直接展示 */
        private String summary;
    }

    // ------------------------------------------------------------------ 影响面

    /**
     * 修改这些岗位能力模型会波及的测评数据。
     *
     * <p>两类分开报，因为它们要用户做的决定不一样：</p>
     * <ul>
     *   <li><b>进行中</b>：问卷已经发出去、评的是旧模型 → 撤回（并可选按原参数重发）</li>
     *   <li><b>已封存</b>：报告已出分 → 可保持原样，也可按新模型重算</li>
     * </ul>
     */
    @Data
    public static class Impact {
        /** 进行中的任务 */
        private List<RunningTask> running = new ArrayList<>();
        /** 已封存（已出分）的报告 */
        private List<FrozenReport> frozen = new ArrayList<>();

        private int runningCount;
        private int frozenCount;

        /** 被影响到的年度（前端拿它渲染「按年度勾选重算」） */
        private List<Integer> years = new ArrayList<>();

        /** 已提交过内容的评价人数量：撤回会作废这些内容，必须让用户看见 */
        private int submittedRaterCount;
    }

    @Data
    public static class RunningTask {
        private Long taskId;
        private Long sheetId;
        private int year;
        private String resourceName;
        private String departmentName;
        private String postName;
        /** 该任务已提交的评价人数量 */
        private int submittedRaters;
        private int totalRaters;
    }

    @Data
    public static class FrozenReport {
        private Long sheetId;
        private int year;
        private String resourceName;
        private String departmentName;
        private String postName;
        private java.math.BigDecimal totalScore;
        private String bandName;
        /**
         * 是否已封存（sql/64）。
         *
         * <p>已封存的报告导入后不会被重算 —— 快照里存的是封存那一刻的分数，
         * 拿新模型重算一遍就会让「已封存」晃晃悠悠地变。导入页据此提示需要先解除封存。</p>
         */
        private boolean sealed;
    }
}
