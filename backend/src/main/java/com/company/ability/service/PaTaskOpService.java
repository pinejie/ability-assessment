package com.company.ability.service;

import com.company.ability.dto.PaTaskDTO;
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
     * 重新发起的预填数据（只读，2026-11-25）。
     *
     * <p>把已撤回任务当初的年度 / 被测人 / 岗位 / 360 按项名单读回来，供发起页回填。
     * 不校验「现在还能不能发」——那些以 {@code warnings} 的中文提示返回，由人决定。
     * 只校验两件事：任务属于当前维度、任务确实是已撤回状态（否则没什么可「重新」发的）。</p>
     */
    PaTaskVO.RelaunchDraft relaunchDraft(Long taskId);

    /**
     * 按原任务重新发起（2026-11-25）：复用 {@code PaTaskService#create} 走完整校验，
     * 成功后回填旧任务的 {@code replaced_by_task_id}。
     *
     * @param taskId 原任务；必须是当前维度的已撤回任务
     * @param req    页面确认后的参数（可修改过）；年度/被测人/岗位/360 名单都以它为准
     */
    PaTaskVO.CreateResult relaunch(Long taskId, PaTaskDTO.CreateRequest req);

    /**
     * 「我的评价」列表：只按登录身份过滤，不接受任何 resourceId 参数。
     *
     * @param year 年度，可空 —— 空表示不限年度（本人页给的是进行中年度，但接口不强制，
     *             直接访问 /my-assess-tasks 的老书签行为不变）
     */
    List<PaTaskVO.MyTaskItem> myTasks(Integer year);
}
