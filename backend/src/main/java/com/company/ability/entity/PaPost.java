package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 测评岗位：部门 + 岗位，或「全公司通用岗位」（{@code departmentId == null}）。
 *
 * <p>能力模型的唯一锚点：每个岗位配一套能力类别/能力项/等级/分值区间。
 * 普通岗位按「部门 + 岗位名」唯一，跨部门同名各建各的；
 * 通用岗位全公司只有一套，不限部门（如会计、出纳、行政专员）。</p>
 *
 * <p>{@code departmentName} 是快照：泛微部门改名后，历史报告里的部门名不应跟着变。</p>
 */
@Data
@TableName("uf_pa_post")
public class PaPost {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属维度（类租户），插入时由租户拦截器自动填充 */
    private Long dimensionId;

    /**
     * 泛微 HrmDepartment.id。
     *
     * <p><b>null = 全公司通用岗位</b>（sql/63）：这种岗位不限部门，任何部门的人都能用它发起测评，
     * 适合「会计」「行政专员」这种每个部门都有、内容又一样的岗位。
     * 部门名快照同为空 —— 报告上的部门由测评页按被测人自己的部门记录。</p>
     */
    private Long departmentId;

    /** 部门名快照；通用岗位为 null */
    private String departmentName;

    /** 岗位编码（导入对齐用，可空） */
    private String postCode;

    /** 岗位名称（项目自有） */
    private String postName;

    private String description;

    /** 1-启用 0-停用 */
    private Integer status;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 是否删除：1-已删除，0-未删除 */
    @TableLogic
    private Integer deleted;

    /** 是否全公司通用岗位（不落库，由 departmentId 推导） */
    public boolean isGeneric() {
        return departmentId == null;
    }
}
