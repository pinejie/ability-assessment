package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 能力要素等级VO
 */
@Data
public class AbilityElementLevelVO {

    private Long id;

    private Long elementId;

    private Integer level;

    private String levelName;

    private String levelRequirement;

    private BigDecimal score;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
