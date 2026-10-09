package com.company.ability.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 能力类别VO
 */
@Data
public class AbilityCategoryVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 类别名称
     */
    private String categoryName;

    /**
     * 类别描述
     */
    private String description;

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
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新人ID
     */
    private Long updateBy;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
