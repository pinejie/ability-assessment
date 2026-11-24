package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评岗位 VO。
 *
 * <p>列表页要一眼看出这套模型配得完不完整，所以带上三个统计值：
 * 类别数、能力项数、权重合计。权重合计由 {@code PaPostService} 用求和算出，
 * <b>不落库</b> —— 它必须随能力项改动实时变化，存一列就得维护同步，迟早不一致。</p>
 */
@Data
public class PaPostVO {

    private Long id;

    private Long departmentId;

    /** 部门名快照；全公司通用岗位为 null */
    private String departmentName;

    /**
     * 是否全公司通用岗位（{@code departmentId == null}）。
     *
     * <p>前端不用自己判空：部门列要显示「全公司通用」，发起页要放开部门限制，
     * 都读这一个字段，免得两处写出两种判定。</p>
     */
    private Boolean generic;

    private String postCode;

    private String postName;

    private String description;

    /** 1-启用 0-停用 */
    private Integer status;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // -------- 统计（只读，不落库） --------

    /** 该岗位下启用中的能力类别数 */
    private Integer categoryCount;

    /** 该岗位下启用中的能力项数 */
    private Integer itemCount;

    /** 能力项权重合计（百分数，如 100.00 表示 100%） */
    private BigDecimal weightTotal;

    /**
     * 等级不足 2 档的启用能力项数。
     *
     * <p>列表页要靠这个数在进详情之前就发现「配了能力项、漏了等级」的岗位：
     * 发起测评的闸门只查有没有启用的能力项，不查等级，于是这种岗位能正常发起，
     * 到评价人填表时才发现该项一个档位都点不了，而报错只说「评价不完整」。
     * 口径与 {@code PaModelServiceImpl} 的 unconfiguredItemCount 一致（同为 {@code < 2}）。</p>
     */
    private Integer unconfiguredItemCount;
}
