package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 分值区间 VO（岗位级）。
 *
 * <p>{@code lowerBound}/{@code upperBound} 为 null 表示 ±∞，
 * 前端展示成「(,3]」「(4,)」，避免把 null 显示成空字符串让用户以为区间没填。</p>
 */
@Data
public class PaBandVO {

    private Long id;

    private Long postId;

    private String bandName;

    private BigDecimal lowerBound;

    private BigDecimal upperBound;

    private Integer sort;
}
