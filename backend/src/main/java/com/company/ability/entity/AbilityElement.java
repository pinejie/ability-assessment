package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 能力要素实体
 */
@Data
@TableName("uf_ability_element")
public class AbilityElement {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 要素名称
     */
    private String elementName;

    /**
     * 要素编码
     */
    private String elementCode;

    /**
     * 所属类别ID
     */
    private Long categoryId;

    /**
     * 要素描述
     */
    private String description;

    /**
     * 测评流程中是否需要评价：1-需评价（默认），0-不需评价
     * 为 0 时要素不进测评页面（页面行置 inactive、不参与计分），且服务端联动 allowClientAssess 归 0
     */
    private Integer assessInFlow;

    /**
     * 是否允许客户评价：1-允许，0-不允许（不允许时客户分按 0 计入加权总分）
     */
    private Integer allowClientAssess;

    /**
     * 状态：1-启用，0-禁用
     */
    private Integer status;

    /**
     * 排序号
     */
    private Integer sort;

    /**
     * 创建人ID
     */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新人ID
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 是否删除：1-已删除，0-未删除
     */
    @TableLogic
    private Integer deleted;
}
