package com.company.ability.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 年度分录入（人工），传 null 表示清空 */
@Data
public class AssessAnnualScoreDTO {
    private BigDecimal annualScore;
}
