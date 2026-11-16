package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 评价填写请求对象（评价人使用）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.2、§4.2.2
 */
public class AssessFillDTO {

    /** 暂存草稿：每次选择要素触发（不阻塞界面） */
    @Data
    public static class DraftRequest {
        @NotEmpty(message = "评级不能为空")
        @Valid
        private List<Rating> ratings;

        /**
         * 客户端看到的草稿版本。服务端要求与库内 draft_version 相等，
         * 不等说明其他标签页已写入，拒绝本次覆盖并返回最新草稿（§4.2.2 并发保护）。
         * 首次暂存传 0。
         */
        @NotNull(message = "草稿版本不能为空")
        private Integer version;
    }

    /** 提交评价：全部要素必须评完，服务端二次校验（§4.2.3） */
    @Data
    public static class SubmitRequest {
        @NotEmpty(message = "评级不能为空")
        @Valid
        private List<Rating> ratings;
    }

    @Data
    public static class Rating {
        @NotNull(message = "要素ID不能为空")
        private Long elementId;

        @NotNull(message = "等级不能为空")
        private Integer levelNo;
    }
}
