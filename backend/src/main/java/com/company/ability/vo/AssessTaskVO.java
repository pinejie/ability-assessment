package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 测评任务与评价填写响应对象。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.1、§7.2、§6.2、§6.4
 */
public class AssessTaskVO {

    /** 发起结果：任务头 + 每人一条专属链接（S1 无消息通道，靠此链接手工验证） */
    @Data
    public static class CreateResult {
        private Long taskId;
        private Long sheetId;
        private Integer status;
        /** 有效要素数（领导/自评视角），客户视角另计 */
        private Integer ratingCount;
        /** 客户评价人配备数 */
        private Integer clientCount;
        private String fillBaseUrl;
        private List<RaterLink> raters;
        /** 邀请消息写入条数（0 表示未写入或全部幂等跳过） */
        private Integer noticeCount;
        private String noticeError;
    }

    /** 单个评价人的链接与身份 */
    @Data
    public static class RaterLink {
        private Long raterId;
        private String raterType;
        private Long raterResourceId;
        private String raterName;
        /** 相对路径，前端拼域名；消息正文里也用它 */
        private String path;
        private String accessToken;
    }

    /**
     * 发起页的「直接上级」（GET /assess-tasks/leader）。
     * 上级由被测人档案自动带出，不允许人工挑选：取值口径与发起时写入
     * uf_assess_sheet.leader_resource_id 的来源同一处（HrmResource.managerid）。
     */
    @Data
    public static class LeaderInfo {
        /** 上级人员 ID；null 表示被测人在 OA 中未维护直接上级，此时不能发起 */
        private Long leaderResourceId;
        private String leaderName;
        /** 上级所在部门，用于表单上区分同名的人 */
        private String deptName;
        /** 不可发起时的原因（如「未维护直接上级」），可直接展示 */
        private String message;
    }

    /**
     * 发起前的人员预检结果（GET /assess-tasks/precheck）。
     * 只读不写，前端据此在点「发起」前展示问题人员表格。
     */
    @Data
    public static class PrecheckResult {
        /** 是否全员可参与；false 时 create 必然被拒 */
        private boolean ok;
        /** 不可用人员清单，空表示无问题 */
        private List<IneligiblePerson> problems;
        /** 一句话汇总，可直接放提示条 */
        private String message;
    }

    /** 一个登不进系统的人 */
    @Data
    public static class IneligiblePerson {
        private Long resourceId;
        private String name;
        /** 被测人 / 直接上级 / 客户评价人 */
        private String role;
        /** NOT_FOUND / NO_LOGINID / RESIGNED / ... 见 RaterEligibilityService.Reason */
        private String reason;
        private String message;
    }

    /**
     * 填写页数据（GET /assess-fill/{token}）。
     * VOIDED / CANCELLED 时只返回 status + message，不含姓名、要素、草稿（§4.3.8）。
     */
    @Data
    public static class FillPage {
        /** FILLING / SUBMITTED / VOIDED / CANCELLED */
        private String status;
        private String message;

        private Long taskId;
        private Long raterId;
        private String raterType;
        private String raterTypeName;
        private String targetName;
        private String deptName;
        private Integer year;
        private String dimensionName;

        /** 该角色的应有要素集合（§6.5 按要素开关过滤，不读 active） */
        private List<ElementItem> elements;
        /** 已存草稿 {elementId: levelNo}，前端回填选中态（§4.2.2） */
        private java.util.Map<Long, Integer> draft;
        /** 草稿版本，暂存时原样带回 */
        private Integer draftVersion;

        /** 已写的评价意见（上级 / 客户才有；自评恒为 null） */
        private String comment;
        /** 该身份是否需要写评语（前端据此决定显示/必填） */
        private Boolean commentRequired;

        /** 本任务整体进度：已提交人数 / 总人数（让评价人知道为什么还要等） */
        private Integer submittedCount;
        private Integer raterTotal;

        /** 客户视角遮蔽：true 时不返回 requiredLevelNo / 权重 / 标准分（§6.4） */
        private Boolean masked;
    }

    /** 单个要素 */
    @Data
    public static class ElementItem {
        private Long elementId;
        private String elementName;
        private String description;
        private Long categoryId;
        private String categoryName;
        /** 客户视角为 null（遮蔽） */
        private Integer requiredLevelNo;
        /**
         * 该要素**自己**的档位清单（§6.3）。
         *
         * <p>档位数量按要素配置，不按维度配置：要素在 uf_ability_element_level 配了几档，
         * 页面就出几个选项。维度级的 uf_assess_level_score 只负责「等级号 → 评价号 → 分值」
         * 的换算，不决定档位个数。
         */
        private List<LevelItem> levels;
    }

    /** 单个等级（档位） */
    @Data
    public static class LevelItem {
        /** 等级号，对应 uf_ability_element_level.level / uf_assess_level_score.level_no */
        private Integer levelNo;
        /** 评价号，提交时回传的就是它（uf_assess_level_score.rate_value） */
        private Integer rateValue;
        /** 等级名（uf_ability_element_level.level_name，缺省回退等级分值的 level_desc） */
        private String levelDesc;
        /** 该等级的行为要求全文（uf_ability_element_level.level_requirement），评价人的判断依据 */
        private String requirement;
    }

    /** 提交结果：明确不返回分数（§7.2） */
    @Data
    public static class SubmitResult {
        private Boolean submitted;
        private Progress taskProgress;
        /** 本次提交是否触发了算分（全员齐备才为 true） */
        private Boolean calculated;
        private String message;
    }

    @Data
    public static class Progress {
        private Integer submitted;
        private Integer total;
    }

    /** 暂存结果 */
    @Data
    public static class DraftResult {
        private Integer draftVersion;
        private Integer ratedCount;
        /** 版本冲突时返回库内最新草稿，前端据此合并 */
        private java.util.Map<Long, Integer> latestDraft;
        private Boolean conflict;
    }

    /** 算分后页面终态（usp_assess_task_finish 的返回） */
    @Data
    public static class SheetState {
        private Long taskId;
        private Integer taskStatus;
        private Long sheetId;
        private Integer sheetStatus;
        private BigDecimal standardScore;
        private BigDecimal leaderScore;
        private BigDecimal clientScore;
        private BigDecimal selfScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
    }

    // ==================== S2 / S3 ====================

    /**
     * 撤回结果（§7.1 响应）。
     * fullRollback=false 表示撤回时本就无评级，只做了任务作废（§4.3.2 计算未开始）。
     */
    @Data
    public static class VoidResult {
        private Long taskId;
        private Long sheetId;
        /** 是否执行了完整回退（删评级 + 重置页面 + 删快照） */
        private Boolean fullRollback;
        private Integer voidedRatingCount;
        private Integer voidedRaterCount;
        /** 一并删除的评价意见条数（2026-11-17：撤回要回到评价初，评语不留） */
        private Integer voidedCommentCount;
        /** 已提交人数 —— 撤回通知只发给这些人（§8.6.1） */
        private Integer submittedRaterCount;
        private Boolean snapshotDeleted;
        /** 撤回后页面状态（§7.1 响应 sheetAfter） */
        private SheetAfter sheetAfter;

        /** 通知写入情况（Q14：撤回与通知分两个事务，失败不回滚撤回） */
        private Boolean noticeWritten;
        private Integer notifiedRaterCount;
        /** 通知失败原因，供前端「撤回成功但通知失败」提示 */
        private String noticeError;
        /** 幂等：已撤回过，本次未执行任何写操作 */
        private Boolean alreadyVoided;
    }

    @Data
    public static class SheetAfter {
        private Long sheetId;
        private Integer status;
        private BigDecimal standardScore;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
    }

    /** 删除结果（2026-11-14）：只删任务与评价人，测评页保留 */
    @Data
    public static class DeleteResult {
        private Long taskId;
        /** 一并删除的评价人条数（token 随之失效） */
        private Integer deletedRaterCount;
        /** 关联的测评页 id —— 保留不删，可能仍被其他任务引用 */
        private Long sheetId;
        /** 恒为 true，明确告知调用方测评页未删 */
        private Boolean sheetKept;
    }

    /** 催办结果（§8.5：只推未提交的人） */
    @Data
    public static class RemindResult {
        private Long taskId;
        /** 本次实际插入的消息条数（受幂等约束，重复催同一轮为 0） */
        private Integer pushedCount;
        private Integer pendingRaterCount;
        private Integer round;
        private String message;
    }

    /** 重算结果（§7.1.2） */
    @Data
    public static class RetryResult {
        private Long taskId;
        private Long sheetId;
        private Integer taskStatus;
        private BigDecimal actualScore;
        private BigDecimal scoreRate;
        private String gradeName;
    }

    /** 任务列表行（GET /assess-tasks） */
    @Data
    public static class TaskItem {
        private Long taskId;
        private Long sheetId;
        private Integer year;
        private Long resourceId;
        private String targetName;
        private String deptName;
        private String dimensionName;
        private Integer status;
        private String statusName;
        private Integer ratingCount;
        private Integer clientCount;
        /** 评价人进度 */
        private Integer raterTotal;
        private Integer submittedCount;
        private Long leaderResourceId;
        private String leaderName;
        private String remark;
        private String voidReason;
        private java.time.LocalDateTime createTime;
        private BigDecimal actualScore;
        private String gradeName;
    }

    /** 任务详情：任务头 + 每个评价人的进度（GET /assess-tasks/{id}） */
    @Data
    public static class TaskDetail {
        private TaskItem task;
        private List<RaterProgress> raters;
    }

    @Data
    public static class RaterProgress {
        private Long raterId;
        private String raterType;
        private String raterTypeName;
        private Long raterResourceId;
        private String raterName;
        private Integer status;
        private String statusName;
        private java.time.LocalDateTime submitTime;
        private Integer remindCount;
        private java.time.LocalDateTime lastRemindTime;
        /** 不返回 token 原文：详情页用于排查，不需要可点击的填写入口 */
        private Boolean hasDraft;
    }

    /** 「我的评价」列表行（§7.2.1，无需维度头，只按登录身份过滤） */
    @Data
    public static class MyTaskItem {
        private Long raterTaskId;
        private Long taskId;
        private String targetName;
        private String deptName;
        private Integer year;
        private String dimensionName;
        private String raterType;
        private String raterTypeName;
        private Integer raterStatus;
        private Integer taskStatus;
        private String accessToken;
        /** 前端直接可用的落地路径 */
        private String path;
        /** 映射后的展示文案（§7.2.1 状态文案映射） */
        private String statusText;
    }
}
