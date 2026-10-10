package com.company.ability.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 岗位能力要求VO
 */
@Data
public class PositionAbilityReqVO {

    private Long id;

    private Long departmentId;

    private String departmentName;

    private Long jobTitleId;

    private String jobTitleName;

    private String description;

    private List<PositionAbilityReqItemVO> items;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Data
    public static class PositionAbilityReqItemVO {
        private Long id;
        private Long categoryId;
        private String categoryName;
        private Long elementId;
        private String elementName;
    }
}
