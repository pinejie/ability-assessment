package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 填写页数据（方案 17 §7.2）。
 *
 * <p>与旧 {@code AssessTaskVO.FillPage} 的差别：<b>没有标准分、没有要求等级</b>。
 * 旧页面让人「和标准比」，新页面只让人「按行为描述挑一档」，
 * 把要求等级摆在眼前会诱导评价人往那个等级上靠。</p>
 */
@Data
public class PaFillPageVO {

    /** FILLING / SUBMITTED / VOIDED / CANCELLED / PERIOD_CLOSED */
    private String status;

    private String message;

    private Long taskId;
    private Long raterId;

    /** SELF / LEADER / RATER360 */
    private String raterType;

    private String raterTypeName;

    private String targetName;
    private String deptName;

    /** 岗位名（部门+岗位），报告与页面都要靠它定位「按哪套尺子评」 */
    private String postName;

    private Integer year;
    private String dimensionName;

    /** 该身份能看到的全部能力项（按类别 + 排序号） */
    private List<PaFillItemVO> items;

    /** 已存草稿 {itemId: levelId} */
    private java.util.Map<Long, Long> draft;

    /** 草稿版本，暂存时原样带回 */
    private Integer draftVersion;

    /** 已写的评价意见（直接上级 / 360 才有，自评恒为 null） */
    private String comment;

    /** 该身份是否需要写评语 —— 前端据此决定显示与必填（口径 #14） */
    private Boolean commentRequired;

    /** 本任务整体进度：让评价人知道为什么还要等 */
    private Integer submittedCount;
    private Integer raterTotal;

    /** 权重合计（百分数），≠100% 时页面给黄色提醒（口径 #2） */
    private BigDecimal weightTotal;

    /**
     * 提交时间。已提交（SUBMITTED）时前端展示成回执页的「提交于」，
     * 是评价人对「我到底交了没有」最直接的凭据；未提交时为 null。
     */
    private java.time.LocalDateTime submitTime;
}
