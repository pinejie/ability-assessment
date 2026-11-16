package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测评任务管理请求对象（S1：仅发起；列表/催办/撤回在 S3）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.1
 */
public class AssessTaskDTO {

    /**
     * 发起测评任务。
     * 年度 + 被测人 + 客户评价人确定即可发起；直接上级由 OA 组织关系带出，不接受人工指定。
     * 评价人名单发起后不可追加或替换（方案 Q1），选错只能「撤回 → 重新发起」。
     */
    @Data
    public static class CreateRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;

        @NotNull(message = "被测人不能为空")
        private Long resourceId;

        /**
         * 直接上级。2026-11-14 起发起页已把上级改成只读展示，服务端只认 OA 组织关系
         * （HrmResource.managerid），本字段不再参与决策，只为兼容旧调用方保留：
         * 传了且与 OA 不一致时只记 WARN，不会改变落库的上级。
         */
        private Long leaderResourceId;

        /** 客户评价人（HrmResource.id）。该被测人有客户评价要素时必填，否则必须为空 */
        private List<Long> clientResourceIds;

        /** 备注，写入 uf_assess_task.remark，仅管理端可见 */
        private String remark;
    }

    /**
     * 发起前的人员预检（GET /assess-tasks/precheck）。
     * 与 create 共用同一套判据（RaterEligibilityService），区别只是不抛异常、把结果全量返回，
     * 供前端在点「发起」之前就列出所有登不进系统的人。
     */
    @Data
    public static class PrecheckRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;

        @NotNull(message = "被测人不能为空")
        private Long resourceId;

        private Long leaderResourceId;

        private List<Long> clientResourceIds;
    }
}
