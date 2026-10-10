package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人员能力要求主表实体
 */
@Data
@TableName("uf_user_ability_req_new")
public class UserAbilityReqNew {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 人员ID
     */
    private Long resourceId;

    /**
     * 描述
     */
    private String description;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
