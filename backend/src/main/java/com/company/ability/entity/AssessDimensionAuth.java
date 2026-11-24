package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 维度授权：控制哪些分部/哪些人可使用哪个维度。
 * 维度没有任何授权行 = 仅系统管理员可用（2026-10-22 语义调整，原为"所有人可用"）。
 *
 * <p><b>2026-11-23 起这是维度权限的唯一真相源</b>（合并见 sql/58）：
 * 原先单列的「所属分部」（uf_assess_dimension.subcompany_id）和「维度管理员」
 * （uf_assess_dimension_admin）都已并入本表 ——</p>
 * <ul>
 *   <li>{@code TYPE_SUBCOMPANY}：既是维度归属哪几家，也是这些分部的人可用（子树语义）</li>
 *   <li>{@code TYPE_EXCLUDED_SUBCOMPANY}：从上面那片范围里挖掉的分支（含下级，sql/59）</li>
 *   <li>{@code TYPE_RESOURCE}：这些人可用，同时是维度管理员，能在报告里看到 360 测评人姓名</li>
 * </ul>
 */
@Data
@TableName("uf_assess_dimension_auth")
public class AssessDimensionAuth {

    /** 授权类型：分部（HrmSubCompany） */
    public static final int TYPE_SUBCOMPANY = 1;
    /** 授权类型：人员（HrmResource） */
    public static final int TYPE_RESOURCE = 2;
    /**
     * 排除分部（sql/59）：在所授权分部之内、但<b>不归本维度</b>的下级分支。
     *
     * <p>为什么需要它：授权是「勾父含子」的正向规则，没有任何减法。国际贸易授权给
     * 维度 A 之后，中达科技作为它的下级必然被带进来 —— 而中达科技要单独一个维度。
     * 没有排除项时，唯一的办法是把国际贸易的下级逐个列出来，下次组织调整就漏。</p>
     *
     * <p>排除同样<b>含下级</b>：排除中达科技，它下面新设的公司也不再归本维度。</p>
     */
    public static final int TYPE_EXCLUDED_SUBCOMPANY = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 维度ID
     */
    private Long dimensionId;

    /**
     * 授权类型：1-分部，2-人员
     */
    private Integer authType;

    /**
     * 授权对象ID（分部ID或人员ID）
     */
    private Long authId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
