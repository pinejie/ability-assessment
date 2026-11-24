package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分值区间 → 等级说明（岗位级各一套）。
 *
 * <p>区间语义 <b>[lower_bound, upper_bound)</b>：下界闭、上界开，
 * 与业务方给的表格写法一致（「[1,2) 是新手，[5] 是专家」）。</p>
 *
 * <ul>
 *   <li>{@code lowerBound == null} → −∞（最低档，写作 (−∞,3)）</li>
 *   <li>{@code upperBound == null} → +∞（最高档，写作 [4,+∞)）</li>
 * </ul>
 *
 * <p>首档下界可以留空也可以写具体分值（写 1 就是「从 1 分起」），末档上界留空表示 +∞。
 * 服务层校验：同岗位内区间不得重叠、不得留缺口
 * （否则会有得分命中不到任何档，报告上就是空白等级）。</p>
 *
 * <p>注意这里比的是<b>分值刻度</b>（如 3~5 分），不再是旧模型的「得分率 %」。</p>
 */
@Data
@TableName("uf_pa_band")
public class PaBand {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long postId;

    /** 等级说明：高手、能手… */
    private String bandName;

    /** 下界（闭区间，含），null = −∞ */
    private BigDecimal lowerBound;

    /** 上界（开区间，不含），null = +∞ */
    private BigDecimal upperBound;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
