package com.company.ability.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力类别 VO（岗位内）。
 */
@Data
public class PaCategoryVO {

    private Long id;

    private Long postId;

    private String categoryName;

    private String description;

    private Integer sort;

    /** 1-启用 0-停用 */
    private Integer status;

    private LocalDateTime updateTime;

    /** 该类别下启用中的能力项数（左树的角标用） */
    private Integer itemCount;
}
