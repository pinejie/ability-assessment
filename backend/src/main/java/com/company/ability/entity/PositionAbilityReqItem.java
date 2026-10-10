package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 岗位能力要求明细实体
 */
@Data
@TableName("uf_position_ability_req_item")
public class PositionAbilityReqItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reqId;

    private Long categoryId;

    private Long elementId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
