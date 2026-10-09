package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人员能力要求VO
 */
@Data
public class UserAbilityReqVO {

    private Long id;

    private Long resourceId;

    private String resourceLastName;

    private Long elementId;

    private String elementName;

    private Long levelId;

    private Integer level;

    private String levelName;

    private String levelRequirement;

    private BigDecimal score;

    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
