package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力类别（岗位内）。
 *
 * <p>Excel 里的「维度」列（领导力/管理力/质检专业…）落在这里。
 * <b>不要叫"维度"</b>：系统里 {@code dimension_id} 已经是租户，
 * 业务上再来一个"维度"会让前端、后端、库三处语义打架。</p>
 *
 * <p>类别本身不再带权重（口径变更 2026-11-21：权重下沉到能力项）。</p>
 */
@Data
@TableName("uf_pa_category")
public class PaCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long postId;

    private String categoryName;

    private String description;

    private Integer sort;

    /** 1-启用 0-停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
