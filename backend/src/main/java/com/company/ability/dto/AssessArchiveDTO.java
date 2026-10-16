package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

/**
 * 测评归档请求（泛微流程后置钩子调用）
 */
public class AssessArchiveDTO {

    @Data
    public static class ArchiveRequest {
        @NotNull(message = "companyId不能为空")
        private Long companyId;
        @NotNull(message = "periodId不能为空")
        private Long periodId;
        @NotNull(message = "workflowRequestId不能为空")
        private Long workflowRequestId;
        @NotNull(message = "resourceId不能为空")
        private Long resourceId;
        private Long departmentId;
        private Long jobTitleId;
        private Long archivedBy;
        @Valid
        private RaterRatings leader;
        @Valid
        private List<RaterRatings> clients;
        @Valid
        private RaterRatings self;
    }

    @Data
    public static class RaterRatings {
        private Long resourceId;
        @Valid
        private List<RatingItem> ratings;
    }

    @Data
    public static class RatingItem {
        private Long elementId;
        private Integer levelNo;
    }
}
