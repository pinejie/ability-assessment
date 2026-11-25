package com.company.ability.vo;

import lombok.Data;

import java.util.List;

/**
 * 测评任务与评价填写响应对象（方案 17 §7）。
 *
 * <p>与旧 {@code AssessTaskVO} 的差别：<b>没有标准分、没有得分率、没有胜任度</b>；
 * 「客户评价人」全部换成「360 测评人」。字段名刻意不沿用旧名
 * （{@code totalScore} / {@code bandName} / {@code rater360Count}），
 * 免得新前端从旧字段里读出旧语义。</p>
 */
public class PaTaskVO {

    // ==================== 发起 ====================

    /** 发起结果：任务头 + 每人一条专属链接 */
    @Data
    public static class CreateResult {
        private Long taskId;
        private Long sheetId;
        private Integer status;
        /** 有效能力项数 */
        private Integer itemCount;
        /** 360 测评人配备数 */
        private Integer rater360Count;
        private String fillBaseUrl;
        private List<RaterLink> raters;
        /** 邀请消息写入条数（0 表示未写入或全部失败） */
        private Integer noticeCount;
        private String noticeError;
    }

    /** 单个评价人的链接与身份 */
    @Data
    public static class RaterLink {
        private Long raterId;
        /** SELF / LEADER / RATER360 */
        private String raterType;
        private Long raterResourceId;
        private String raterName;
        /** 相对路径，前端拼域名；消息正文里也用它 */
        private String path;
        private String accessToken;
        /**
         * 这条链接要评几项（2026-11-23）。
         *
         * <p>自评 / 上级为 null（他们评的是「自己身份对应的全部项」，由页面按快照算）；
         * 360 是<b>分到他名下的项数</b> —— 一个人可以被指派到岗位里的一部分能力项，
         * 消息里的「共 N 个能力项」必须说这个数，说整页项数会让人以为漏了半份。</p>
         */
        private Integer itemCount;
    }

    /** 发起页的「直接上级」：由被测人档案带出，页面只读（口径 #11） */
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
     * 发起页的岗位信息（选人后自动带出）。
     *
     * <p>发起页要提前告诉 HR 三件事：这个人按哪套尺子评、有几项、要不要选 360。
     * 最后一件决定「360 测评人」下拉是否必填（口径 #9：前端必填、后端不硬拦）。</p>
     */
    @Data
    public static class PostInfo {
        private Long postId;
        private Long departmentId;
        private String departmentName;
        private String postName;
        /** 是否全公司通用岗位（无部门，任何部门的人都可用它发起，sql/63） */
        private Boolean generic;
        /** 启用中的能力项数 */
        private Integer itemCount;
        /** 其中配置了「360 测评人」主体的项数；0 表示不用选 360 */
        private Integer rater360ItemCount;
        /** 权重合计（百分数），≠100% 时页面给黄色提醒（口径 #2） */
        private java.math.BigDecimal weightTotal;
        /**
         * 需要 360 评价的能力项明细（2026-11-23 起发起页逐项选人）。
         *
         * <p>只回 360 项，不发全量能力项：发起页要渲染的是「哪几项要选人」，
         * 只由上级评的项在这一行没有可填的东西，列出来只会稀释注意力。</p>
         */
        private List<Rater360ItemBrief> rater360Items;
        /** 不能发起时的原因（如「岗位已停用」） */
        private String message;
    }

    /** 发起页「360 测评人」表里的一行：一个能力项 + 它所属类别与评价主体 */
    @Data
    public static class Rater360ItemBrief {
        private Long itemId;
        private String itemName;
        /** 类别名，表格里分组的视觉依据；类别被删过则为 null */
        private String categoryName;
        /**
         * 评价主体说明原文（2026-11-24）：岗位模型里「评价主体」那一列的文字，
         * 如「跨部门360＋直接上级」「内部客户360」。
         *
         * <p>原文照抄，<b>不按 raterLeader / rater360 反推</b>：同样是 360，
         * 写「内部客户」和写「跨部门」不是一回事，反推只能得出「360 测评人」这种
         * 看着像原文、其实是系统编的字。为空（手工新增/老数据）时页面显示「—」。</p>
         *
         * <p>2026-11-24 取代原来的 {@code weight} 字段：这张表的用途是「这一项该问谁」，
         * 权重在页面顶部「权重合计」与模型页已经能看到，占一列反而挤掉有用信息。</p>
         */
        private String raterText;
    }

    /** 发起前的人员预检结果 */
    @Data
    public static class PrecheckResult {
        /** 是否全员可参与；false 时 create 必然被拒 */
        private boolean ok;
        private List<IneligiblePerson> problems;
        private String message;
    }

    /** 一个登不进系统的人 */
    @Data
    public static class IneligiblePerson {
        private Long resourceId;
        private String name;
        /** 被测人 / 直接上级 / 360 测评人 */
        private String role;
        /** NOT_FOUND / NO_LOGINID / RESIGNED / ... 见 RaterEligibilityService.Reason */
        private String reason;
        private String message;
    }

    // ==================== 填写 ====================

    /** 提交结果：明确不返回分数（评分出来前谁都不该看到中间数） */
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
        private java.util.Map<Long, Long> latestDraft;
        private Boolean conflict;
    }

    // ==================== 任务管理 ====================

    /** 任务列表行（GET /assess-tasks） */
    @Data
    public static class TaskItem {
        private Long taskId;
        private Long sheetId;
        private Integer year;
        private Long resourceId;
        private String targetName;
        private String deptName;
        /** 岗位名（部门+岗位），列表里一眼看出按哪套尺子评 */
        private String postName;
        private String dimensionName;
        private Integer status;
        private String statusName;
        /** 待自评：自评未交不阻断出分，但要让 HR 看到还有人没交（口径 #13） */
        private Boolean selfPending;
        private Integer itemCount;
        /** 360 测评人人数（旧「客户人数」） */
        private Integer rater360Count;
        private Integer raterTotal;
        private Integer submittedCount;
        private Long leaderResourceId;
        private String leaderName;
        private String remark;
        private String voidReason;
        private java.time.LocalDateTime createTime;
        /** 总得分；未算分为 null */
        private java.math.BigDecimal totalScore;
        /** 等级说明（命中分值区间得到） */
        private String bandName;
    }

    /** 任务详情：任务头 + 每个评价人的进度 */
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
        /**
         * 这条链接要评几项（2026-11-23）。自评 / 上级为 null（按身份算全部）；
         * 360 是被指派的项数 —— 详情页要能看出「这个人只评 2 项」，否则
         * 进度条显示「已提交」但整页只多了 2 个评级，看起来像漏数据。
         */
        private Integer itemCount;
    }

    /** 撤回结果 */
    @Data
    public static class VoidResult {
        private Long taskId;
        private Long sheetId;
        /** 是否执行了完整回退（删评级 + 重置页面） */
        private Boolean fullRollback;
        private Integer voidedRatingCount;
        private Integer voidedRaterCount;
        /** 一并删除的评价意见条数（撤回要回到评价初，评语不留） */
        private Integer voidedCommentCount;
        /** 已提交人数 —— 撤回通知只发给这些人 */
        private Integer submittedRaterCount;
        private SheetAfter sheetAfter;
        private Boolean noticeWritten;
        private Integer notifiedRaterCount;
        private String noticeError;
        /** 幂等：已撤回过，本次未执行任何写操作 */
        private Boolean alreadyVoided;
    }

    @Data
    public static class SheetAfter {
        private Long sheetId;
        private Integer status;
        private java.math.BigDecimal totalScore;
        private String bandName;
    }

    /** 催办结果 */
    @Data
    public static class RemindResult {
        private Long taskId;
        private Integer pushedCount;
        private Integer pendingRaterCount;
        private Integer round;
        private String message;
    }

    /**
     * 重新发起的预填数据（2026-11-25）：把已撤回任务当初选的东西读回来，供发起页回填。
     *
     * <p><b>为什么要有这个接口，而不是让页面读任务详情再自己拼</b>：详情里有年度、被测人、
     * 岗位名，但<b>没有 360 名单</b>（任务表只存了人数快照），更没有「谁评哪几项」
     * （在 uf_assess_task_rater_item 里）。让页面自己拼就得再加两个接口，
     * 或者让页面拿人名去反查 —— 而人一旦离职/改名就查不回来了。</p>
     *
     * <p><b>这里只回「原任务当初是什么」，不判断「现在还能不能用」</b>：哪些项已经不可用、
     * 年度是否已关闭、岗位是否已停用，都由 {@link #warnings} 用中文说出来，
     * 由人决定改哪一项。后端不替人做决定，也不悄悄丢掉带不出来的部分 ——
     * 丢了不说，用户会以为「按原样重新发起了」，发出去却是缺人的。</p>
     */
    @Data
    public static class RelaunchDraft {
        /** 原任务 id（提示语里要显示「按任务 #N 重新发起」） */
        private Long taskId;
        private Long sheetId;
        /** 原任务的年度；年度不存在的极端情况为 null（页面由人重选） */
        private Integer year;
        private Long resourceId;
        private String targetName;
        /**
         * 被测人（姓名 + 组织信息）。页面要把它塞进人员下拉的候选项里：
         * remote 下拉只渲染当次搜索结果，预填进来的 ID 没有对应 option，
         * 标签就会退化成裸数字。
         */
        private RelaunchPerson target;
        /** 被测人当前所在部门（页面用它来收窄岗位候选；与旧任务快照可能不同） */
        private Long departmentId;
        private Long postId;
        private String postName;
        /** 原任务的「按项指派」；老任务（本表无行）回退成「这些人评全部 360 项」 */
        private List<RelaunchRaterItem> rater360Items;
        /** 360 名单（去重后的人，按项指派为空时页面仍能显示要带出谁） */
        private List<RelaunchPerson> rater360People;
        /**
         * 原任务当初用的 360 名单是否来自「旧口径」（这批人评全部 360 项）。
         *
         * <p>页面据此把同一个人在每一项上都勾上 —— 而不是只勾在某几项里，
         * 那会把「当时这批人评了全部项」改写成另一件事。</p>
         */
        private Boolean legacyAllItems;
        /** 提示：原配置里哪些东西已经不能原样带出，或带出来需要人确认 */
        private List<String> warnings;
        /** 原任务是否已经被重新发起过；是的话页面提示一句，避免重复发起 */
        private Long replacedByTaskId;
    }

    /** 重新发起预填：一条「谁评这一项」 */
    @Data
    public static class RelaunchRaterItem {
        private Long itemId;
        private String itemName;
        private String categoryName;
        private String raterText;
        /** 这一项现在是否仍可指派 360（项还在、仍是启用、仍配了 360 主体） */
        private Boolean usable;
        private List<RelaunchPerson> people;
    }

    /** 重新发起预填：一个人（页面显示「姓名（公司·部门）」，不能显示裸 ID） */
    @Data
    public static class RelaunchPerson {
        private Long resourceId;
        private String name;
        private Long departmentId;
        private String departmentName;
        private String companyName;
    }

    /** 重算结果 */
    @Data
    public static class RetryResult {
        private Long taskId;
        private Long sheetId;
        private Integer taskStatus;
        private Integer sheetStatus;
        private java.math.BigDecimal totalScore;
        private String bandName;
    }

    /** 删除结果：只删任务与评价人，测评页保留 */
    @Data
    public static class DeleteResult {
        private Long taskId;
        private Integer deletedRaterCount;
        /** 关联的测评页 id —— 保留不删，可能仍被其他任务引用 */
        private Long sheetId;
        /** 恒为 true，明确告知测评页未删 */
        private Boolean sheetKept;
    }

    /** 「我的评价」列表行：只按登录身份过滤，不接受任何 resourceId 参数 */
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
        private String path;
        private String statusText;
    }
}
