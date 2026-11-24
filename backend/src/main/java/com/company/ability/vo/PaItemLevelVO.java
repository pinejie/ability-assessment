package com.company.ability.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 能力项等级 VO。
 */
@Data
public class PaItemLevelVO {

    private Long id;

    private String levelName;

    private BigDecimal score;

    private String levelDesc;

    private Integer sort;
}
