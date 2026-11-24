package com.company.ability.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 任务管理与撤回请求体。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.1、§8.6.3
 */
public class PaTaskOpDTO {

    /**
     * 撤回请求（§7.1）。
     *
     * reason 必填且 5–200 字：它会被原样写进消息正文发给已提交的评价人（§8.6.2、§8.6.3），
     * 不是内部备注。长度与前端双重校验，防止出现「撤回原因：」后空白。
     */
    @Data
    public static class VoidRequest {
        @NotBlank(message = "撤回原因不能为空")
        @Size(min = 5, max = 200, message = "撤回原因需 5-200 字")
        private String reason;

        /** 任务已完成（status=2）时必须显式传 true，避免误撤回已出分任务（§4.3.3） */
        private Boolean force;
    }

    /** 催办请求：不传则推全部未提交者 */
    @Data
    public static class RemindRequest {
        /** 指定只催某几人（可选）；为空则催全部未提交者 */
        private java.util.List<Long> raterResourceIds;
    }

    /** 任务列表查询条件 */
    @Data
    public static class TaskQuery {
        @Min(value = 1, message = "页码从 1 开始")
        private Integer page = 1;
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页最多 200 条")
        private Integer size = 20;
        private Integer year;
        private Integer status;
        private Long departmentId;
        /** 被测人姓名/工号模糊匹配 */
        private String keyword;
    }
}
