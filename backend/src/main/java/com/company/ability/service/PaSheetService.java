package com.company.ability.service;

import com.company.ability.entity.PaSheet;

/**
 * 测评页装配：把「年度 + 被测人」翻译成一张可评价的页面。
 *
 * <p>这是发起链路里唯一读配置的地方 —— 入参直接给岗位，把岗位的能力项
 * <b>快照</b>成 {@code uf_pa_sheet_item} 行。
 * 快照之后岗位再改权重/改名/删项，历史报告都不受影响（口径 #15）。</p>
 *
 * <p>为什么单独一个服务：任务发起（{@code PaTaskService}）、填写页（{@code PaFillService}）、
 * 算分（{@code PaScoreService}）三处都要「这页有哪些能力项」，
 * 各自拼一遍 SQL 必然漂移成三套口径。</p>
 */
public interface PaSheetService {

    /**
     * 装配（或复用）测评页。
     *
     * <p>同一人同一年度只有一页（唯一索引 {@code UX_pa_sheet_period_res}）：
     * 撤回后重新发起是「复用该页 + 新开任务」，不是新开页面 ——
     * 否则报告上会出现同一个人的两份 2026 报告。</p>
     *
     * <p>每次发起都会<b>重建</b>页面行：以发起那一刻的岗位配置为准。
     * 这与「历史报告不受岗位改动影响」并不矛盾 —— 快照在算分完成时冻结，
     * 而重新发起本身就是一次新的评价征集。</p>
     *
     * @param year       测评年度（{@code uf_assess_period.year}）
     * @param resourceId 被测人
     * @param postId     岗位（{@code uf_pa_post.id}），发起时由 HR 选定
     * @return 装配好的页面（已落库）
     */
    PaSheet prepare(int year, long resourceId, long postId);
}
