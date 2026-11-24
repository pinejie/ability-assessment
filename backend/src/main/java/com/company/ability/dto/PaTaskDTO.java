package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测评任务发起请求（方案 17 §7.2）。
 *
 * <p>与旧 {@code AssessTaskDTO.CreateRequest} 的差别：入参从「客户评价人」换成
 * 「360 测评人」，语义也变了 —— 不再是外部客户，是发起人手工选的一组内部人员（口径 #3）。</p>
 */
public class PaTaskDTO {

    /**
     * 发起测评任务：年度 + 被测人 + 岗位 + 360 测评人。
     *
     * <p>直接上级<b>不在入参里</b>（口径 #11）：它只能来自 OA 组织关系，
     * 让人传一个上级 ID 就等于允许伪造汇报线，报告上「上级评了什么」也就失去了凭证意义。</p>
     *
     * <p>岗位<b>在入参里</b>（2026-11-21 增补）：原先要先到「人员岗位映射」页面把人和岗位
     * 配一遍才能发起，那张表只服务于这一个动作，属于多一道手续。现在发起时直接选。
     * 「一页一人一年」仍由 {@code uf_pa_sheet} 的 {@code (period_id, resource_id)} 唯一索引保证，
     * 同一个人同年重新发起会复用同一页并换成新岗位。</p>
     */
    @Data
    public static class CreateRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;

        @NotNull(message = "被测人不能为空")
        private Long resourceId;

        @NotNull(message = "岗位不能为空")
        private Long postId;

        /**
         * 360 测评人（HrmResource.id）—— <b>旧入参，按「这批人评全部 360 项」理解</b>。
         *
         * <p>2026-11-23 起由 {@link #rater360Items} 取代：能力项是逐项选人的。
         * 保留它只为兼容（老页面、老脚本、既有用例不需要改一行就能继续跑）。
         * 两者都传时以 {@code rater360Items} 为准 —— 让显式的新语义赢，不做合并，
         * 合并会产生「谁被指派到哪一项」的歧义。</p>
         */
        private List<Long> rater360ResourceIds;

        /**
         * 360 测评人按能力项指派（2026-11-23）：一项一组人。
         *
         * <p>只要传了这个字段（哪怕空列表），就按新语义走：<b>没被指派到的项没人评</b>。
         * 因此 {@code itemId} 必须是所选岗位里配了「360 测评人」主体的启用能力项，
         * 否则拒绝 —— 悄悄忽略会让 HR 以为选的人已经生效。</p>
         *
         * <p>同一个人可以出现在多个项里，落库时收成一条 {@code uf_assess_task_rater}
         * （一人一条链接，页面只列他被指派的项），
         * 指派关系落 {@code uf_assess_task_rater_item}。</p>
         */
        private List<Rater360Item> rater360Items;

        /** 备注，写入 uf_assess_task.remark，仅管理端可见 */
        private String remark;
    }

    /** 一个能力项上的 360 测评人 */
    @Data
    public static class Rater360Item {
        @NotNull(message = "能力项不能为空")
        private Long itemId;

        /** 该项的测评人；为空表示这一项没人评（前端会拦，后端按「未指派」处理） */
        private List<Long> resourceIds;
    }

    /**
     * 发起前人员预检：与 create 同参，不含备注与岗位（岗位不影响人员可登录性）。
     *
     * <p>这里刻意<b>不收「按项指派」结构</b>（2026-11-23）：预检问的是「这批人能不能登录」，
     * 由谁评哪一项跟答案无关；而 GET 的查询串本来也传不进来 List&lt;对象&gt;
     * （Spring 会按属性路径绑定，报 InvalidPropertyException）。所以前端把逐项选人
     * 拍平成一份去重后的 resourceId 列表传上来。</p>
     */
    @Data
    public static class PrecheckRequest {
        @NotNull(message = "年度不能为空")
        private Integer year;

        @NotNull(message = "被测人不能为空")
        private Long resourceId;

        /** 360 测评人（去重后）：一份扁平名单，不区分能力项 */
        private List<Long> rater360ResourceIds;
    }
}
