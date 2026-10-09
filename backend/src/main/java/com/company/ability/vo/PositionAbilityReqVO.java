package com.company.ability.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 岗位能力要求VO
 */
@Data
public class PositionAbilityReqVO {

    private Long id;

    private Long jobTitleId;

    private String jobTitleName;

    private Long elementId;

    private String elementName;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
