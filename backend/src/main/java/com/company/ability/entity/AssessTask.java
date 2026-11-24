package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测评任务：年度 × 维度 × 被测人的一次评价征集（表 uf_assess_task）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §5.1
 *
 * <p>与 {@code uf_pa_sheet} 多对一：一张页面（一人一年度）可被多次发起（撤回后重发），
 * 故任务独立成表，带自己的进度与撤回痕迹。旧 {@code uf_assess_sheet} 已于 P3（sql/51）DROP，
 * 表名与实体名沿用旧词只是为了避免一次没有收益的全库改名。</p>
 *
 * status：0-进行中 2-已完成 3-已取消 4-已撤回。无「已截止」（不设截止时间）、
 * 无「待审核」（提交即生效，Q3）。
 */
@Data
@TableName("uf_assess_task")
public class AssessTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long dimensionId;

    /** uf_assess_period.id */
    private Long periodId;

    /** uf_pa_sheet.id，发起时同步生成 */
    private Long sheetId;

    /** 被测人 HrmResource.id */
    private Long resourceId;

    /** 快照：部门，岗位权重匹配用 */
    private Long departmentId;

    /** 快照：岗位 */
    private Long jobTitleId;

    /** 快照：直接领导（可由发起人覆盖） */
    private Long leaderResourceId;

    /** 0-进行中 2-已完成 3-已取消 4-已撤回 */
    private Integer status;

    /** 有效能力项数快照 */
    private Integer ratingCount;

    /** 360 测评人数量快照（列名沿用旧词 client_count，方案 17 起语义是 360 测评人） */
    private Integer clientCount;

    private String remark;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 撤回与替换链（§4.3），S3 使用，S1 发起时为空 ----

    private Long voidBy;

    private LocalDateTime voidTime;

    /** 必填，会原样外发给评价人（§8.6.3） */
    private String voidReason;

    /** 撤回通知正文快照，便于事后核对 */
    private String voidNoticeJson;

    /** 被哪个新任务替换 */
    private Long replacedByTaskId;
}
