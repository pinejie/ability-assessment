package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 评价填写请求（方案 17 §7.2）。
 *
 * <p>与旧 {@code AssessFillDTO} 的核心差别：<b>评的是等级 ID，不是等级号</b>。
 * 新模型里等级是每个能力项自己的一套（可增删、无 level_no），
 * 「第 3 档」这种位置概念不存在了 —— 存位置就会在增删等级后指向别人。</p>
 */
public class PaFillDTO {

    /** 暂存草稿：每次选择等级触发（不阻塞界面） */
    @Data
    public static class DraftRequest {
        /**
         * 评级。草稿允许为空 —— 评价人可能先写评语再评级；
         * 提交接口仍然强制全覆盖（口径 #4）。
         */
        @Valid
        private List<Rating> ratings;

        /**
         * 评价意见（口径 #14）：只有直接上级 / 360 测评人有，自评传 null 会被忽略。
         * 暂存阶段不强制非空（可以最后再写），提交时必须非空 —— 校验在服务层做，
         * 因为「是否必填」取决于评价人身份，注解表达不了。
         */
        @Size(max = 1000, message = "评价意见不能超过 1000 字")
        private String comment;

        /** 客户端看到的草稿版本；与库内 draft_version 不等说明别处已写入，拒绝覆盖（并发保护） */
        @NotNull(message = "草稿版本不能为空")
        private Integer version;
    }

    /** 提交评价：看得见的能力项必须全评（口径 #4），服务端二次校验 */
    @Data
    public static class SubmitRequest {
        @NotEmpty(message = "评级不能为空")
        @Valid
        private List<Rating> ratings;

        /** 评价意见：直接上级 / 360 必填，自评忽略 */
        @Size(max = 1000, message = "评价意见不能超过 1000 字")
        private String comment;
    }

    @Data
    public static class Rating {
        @NotNull(message = "能力项ID不能为空")
        private Long itemId;

        /** 所选等级 uf_pa_item_level.id */
        @NotNull(message = "等级不能为空")
        private Long levelId;
    }
}
