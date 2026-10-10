package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 人员能力要求VO
 */
@Data
public class UserAbilityReqNewVO {

    private Long id;

    private Long resourceId;

    private String resourceLastName;

    private String description;

    private List<UserAbilityReqItemVO> items;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 能力要素明细项VO
     */
    @Data
    public static class UserAbilityReqItemVO {
        private Long id;

        private Long categoryId;

        private String categoryName;

        private Long elementId;

        private String elementName;

        private Long levelId;

        private String levelName;

        private String levelRequirement;

        private BigDecimal score;
    }
}
