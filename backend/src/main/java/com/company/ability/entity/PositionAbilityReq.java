package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 岗位能力要求主表实体
 */
@Data
@TableName("uf_position_ability_req_new")
public class PositionAbilityReq {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long departmentId;

    private Long jobTitleId;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
