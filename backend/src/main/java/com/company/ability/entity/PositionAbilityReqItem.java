package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
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

    /**
     * 岗位能力类别权重（%）：同一类别下的每条明细都冗余同一个值，
     * 由全局能力类型权重带出后可在岗位上按类别调整；不要求岗位合计 100。
     */
    private BigDecimal weight;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
