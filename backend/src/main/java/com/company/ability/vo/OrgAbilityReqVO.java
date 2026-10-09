package com.company.ability.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 部门能力要求VO
 */
@Data
public class OrgAbilityReqVO {

    private Long id;

    private Long departmentId;

    private String departmentName;

    private Long elementId;

    private String elementName;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
