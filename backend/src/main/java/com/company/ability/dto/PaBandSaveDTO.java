package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 分值区间维护 DTO（岗位级）。
 *
 * <p>页面上区间是整块编辑的，所以一次提交整个岗位的区间列表，服务层校验后整批替换。
 * 逐条增删会让「中间态区间重叠」短暂落库，虽然看不见但一旦出错就是脏数据。</p>
 *
 * <p>区间语义 <b>[lowerBound, upperBound)</b>；null 表示 ±∞。</p>
 */
@Data
public class PaBandSaveDTO {

    /** 行 ID，仅用于回显，保存时以整批替换为准 */
    private Long id;

    @NotBlank(message = "等级说明不能为空")
    private String bandName;

    /** 下界（含），null = −∞ */
    private BigDecimal lowerBound;

    /** 上界（不含），null = +∞ */
    private BigDecimal upperBound;

    private Integer sort = 0;
}
