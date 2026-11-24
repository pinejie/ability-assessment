package com.company.ability.service;

import com.company.ability.dto.PaTaskDTO;
import com.company.ability.vo.PaTaskVO;

/**
 * 测评任务发起（方案 17 §7.2）。
 *
 * <p>职责边界：本服务只负责「把一次评价征集发起起来」——
 * 校验 → 装配页面 → 生成评价人 → 发链接。评级写入在 {@link PaFillService}，
 * 算分在 {@link PaScoreService}，撤回/催办在 {@link PaTaskOpService}。</p>
 */
public interface PaTaskService {

    /**
     * 发起。发起前必须全部通过，任一失败整单回滚：
     * <ol>
     *   <li>年度存在且未关闭</li>
     *   <li>被测人当年有人员岗位映射，岗位下有启用中的能力项（装配页面时校验）</li>
     *   <li>被测人在 OA 有直接上级，且不是本人</li>
     *   <li>该页面没有进行中的任务（避免两人同时评、互相覆盖）</li>
     *   <li>被测人 / 上级 / 全部 360 都能登录系统</li>
     * </ol>
     */
    PaTaskVO.CreateResult create(PaTaskDTO.CreateRequest req);

    /** 发起前人员预检：与 create 同一套判据，但把结果全量返回而不是抛异常 */
    PaTaskVO.PrecheckResult precheck(PaTaskDTO.PrecheckRequest req);

    /** 按被测人取直接上级（OA 组织关系，只读） */
    PaTaskVO.LeaderInfo resolveLeader(Long resourceId);

    /**
     * 按岗位取能力模型信息（选岗位后自动带出）。
     *
     * <p>发起页要靠它决定「360 测评人」是否必填 —— 全部能力项都只配了直接上级时，
     * 让人非选一个 360 只会产生一份没人填的链接。</p>
     */
    PaTaskVO.PostInfo resolvePost(Long postId);
}
