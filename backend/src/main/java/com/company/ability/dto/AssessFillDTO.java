package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        /**
         * 评级。草稿允许为空 —— 评价人可能先写评语后评级（2026-11-17 加评语后放开，
         * 原为 @NotEmpty）；提交接口仍然强制非空。
         */
        @Valid
        private List<Rating> ratings;

        /**
         * 评价意见（2026-11-17 新增）。
         *
         * <p>只有直接上级 / 客户两种身份有评语，自评传 null 会被忽略。
         * 暂存阶段不强制非空（评语可以最后再写），但提交时必须非空 —— 校验在服务层做，
         * 因为「是否必填」取决于评价人身份，无法用注解表达。
         */
        @Size(max = 1000, message = "评价意见不能超过 1000 字")
        private String comment;

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

        /**
         * 评价意见（2026-11-17 新增）：直接上级 / 客户必填，自评忽略。
         * 非空校验在服务层做（取决于评价人身份，注解表达不了）。
         */
        @Size(max = 1000, message = "评价意见不能超过 1000 字")
        private String comment;
    }

    @Data
    public static class Rating {
        @NotNull(message = "要素ID不能为空")
        private Long elementId;

        @NotNull(message = "等级不能为空")
        private Integer levelNo;
    }
}
