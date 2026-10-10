package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人员能力要求明细表实体
 */
@Data
@TableName("uf_user_ability_req_item")
public class UserAbilityReqItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联主表ID
     */
    private Long reqId;

    /**
     * 能力类别ID
     */
    private Long categoryId;

    /**
     * 能力要素ID
     */
    private Long elementId;

    /**
     * 等级ID
     */
    private Long levelId;

    /**
     * 分数
     */
    private BigDecimal score;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
