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
 * 维度没有任何授权行 = 仅管理员可用（2026-10-22 语义调整，原为"所有人可用"）。
 */
@Data
@TableName("uf_assess_dimension_auth")
public class AssessDimensionAuth {

    /** 授权类型：分部（HrmSubCompany） */
    public static final int TYPE_SUBCOMPANY = 1;
    /** 授权类型：人员（HrmResource） */
    public static final int TYPE_RESOURCE = 2;

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
