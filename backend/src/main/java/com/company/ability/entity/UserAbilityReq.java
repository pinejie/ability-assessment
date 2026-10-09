package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人员能力要求实体
 */
@Data
@TableName("uf_user_ability_req")
public class UserAbilityReq {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 人员ID（关联 HrmResource）
     */
    private Long resourceId;

    /**
     * 能力要素ID
     */
    private Long elementId;

    /**
     * 等级ID（关联 uf_ability_element_level）
     */
    private Long levelId;

    /**
     * 分数（自动带出）
     */
    private BigDecimal score;

    /**
     * 描述
     */
    private String description;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
