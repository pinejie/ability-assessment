package com.company.ability.service;

import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.vo.PaTaskVO;

import java.util.List;
import java.util.Map;

/**
 * 测评任务的运营操作（方案 17 §7.2）：列表、详情、撤回、催办、重算、删除。
 *
 * <p>权限口径：撤回 / 重算 / 催办 一律要求「维度管理员或 HR」，
 * 由 {@code DimensionContextFilter} 在入口把关；发起人身份不作为依据 ——
 * 撤回会物理删除评级并通知所有当事人，是不可逆操作，权限应跟随当前身份而不是历史行为。</p>
 */
public interface PaTaskOpService {

    /** 任务列表（分页）：含总得分 / 等级说明 / 360 测评人人数 */
    Map<String, Object> list(PaTaskOpDTO.TaskQuery query);

    /** 任务详情 + 每个评价人的进度 */
    PaTaskVO.TaskDetail detail(Long taskId);

    /** 撤回：删评级 + 重置页面 + 作废评价人 + 通知已提交者。已完成任务需 force=true */
    PaTaskVO.VoidResult voidTask(Long taskId, PaTaskOpDTO.VoidRequest req);

    /** 补发撤回通知（仅已撤回任务可用，靠 void_notice_json 判重） */
    PaTaskVO.VoidResult resendVoidNotice(Long taskId);

    /** 催办：只推未提交的人 */
    PaTaskVO.RemindResult remind(Long taskId, PaTaskOpDTO.RemindRequest req);

    /** 重新计算得分：仅进行中 / 已完成可用 */
    PaTaskVO.RetryResult retry(Long taskId);

    /** 删除任务（仅已撤回可用）：物理删任务与评价人，测评页保留 */
    PaTaskVO.DeleteResult delete(Long taskId);

    /**
     * 「我的评价」列表：只按登录身份过滤，不接受任何 resourceId 参数。
     *
     * @param year 年度，可空 —— 空表示不限年度（本人页给的是进行中年度，但接口不强制，
     *             直接访问 /my-assess-tasks 的老书签行为不变）
     */
    List<PaTaskVO.MyTaskItem> myTasks(Integer year);
}
