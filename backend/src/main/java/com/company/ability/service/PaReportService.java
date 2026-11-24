package com.company.ability.service;

import com.company.ability.dto.PageResult;
import com.company.ability.vo.PaReportVO;

import java.util.List;

/**
 * 个人测评报告（方案 17 §7.2）：只读，读的是算分落库后的 {@code uf_pa_*_sheet}。
 *
 * <p>为什么不复用 {@code AssessSheetQueryService}：那份 SQL 里全是旧模型的
 * 「标准分 / 客户 N 号 / 得分率」列，新模型没有这些字段，硬合并只会得到一堆
 * {@code LEFT JOIN} 出来的 NULL 列和两套状态口径。</p>
 *
 * <p>与算分的关系：本服务<b>不做任何计算</b>。分数在算分时已落到
 * {@code uf_pa_sheet} / {@code uf_pa_sheet_item}，报告只是把它们读出来 ——
 * 「页面上的分数和算分结果不一致」这类 bug 在这个结构下不可能出现。</p>
 */
public interface PaReportService {

    /**
     * 报告列表（分页）。
     *
     * @param year         年度，必填 —— 报告天然按年看
     * @param resourceId   被测人，可空
     * @param departmentId 部门，可空
     * @param status       页面状态，可空
     */
    PageResult<PaReportVO.ListItem> page(Integer year, Long resourceId, Long departmentId,
                                         Integer status, int pageNum, int pageSize);

    /** 报告详情：头 + 每个能力项一行 */
    PaReportVO.Detail detail(Long sheetId);

    /**
     * 「我的测评」——本人自查自己的测评报告（2026-11-23）。
     *
     * <p>为什么单独两个方法而不是复用 {@link #page}/{@link #detail}：那一对按
     * 「维度上下文 + 维度授权」过滤，被测评人是普通员工，通常没有维度授权，
     * 连页面都进不来。本人的报告只能按「登录人 == 被测评人」这一条判定，
     * 跨维度取（一个人可能在不同维度下各有一张页面）。</p>
     */

    /** 本人可看的年度（跨维度去重，标记哪个是进行中） */
    List<PaReportVO.MyYear> myYears();

    /** 本人在指定年度下的报告列表；year 为空时取当前进行中的年度 */
    List<PaReportVO.ListItem> myReports(Integer year);

    /** 本人某张页面的报告；校验「这张页面是不是我的」，姓名一律不返回 */
    PaReportVO.Detail myDetail(Long sheetId);

    /**
     * 「我的测评」——看<b>直接下属</b>的测评报告（2026-11-24）。
     *
     * <p>为什么单开一组方法而不复用 {@link #page}/{@link #detail}：那条链路的可见范围是
     * 「维度 + 维度授权」，而基层管理者通常没有维度授权；反过来，有维度授权的人
     * 用那组接口本来就能看到全维度 —— 拿它来判「是不是我下属」两边都不对。</p>
     *
     * <p>边界写死在这里（改之前先读）：</p>
     * <ol>
     *   <li><b>只认直接下属</b>：口径是泛微 {@code HrmResource.managerid == 登录人}
     *       （与判定「直接上级」同一字段，口径 #11）。不做隔级、不做递归下钻、不做部门可见 ——
     *       一样能看全公司，就等于把这套报告在组织内完全公开。</li>
     *   <li><b>只读</b>：给的报告明细与本人视角完全同构，但不含任何可写操作。</li>
     *   <li><b>不回姓名</b>：{@code showRaterName} 恒为 false，与本人视角一致。
     *       下属的 360 名单里有谁不是上级该通过报告知道的事；真需要看，
     *       走管理员报告页（那里本来就有权限判定）。</li>
     * </ol>
     */

    /** 我有没有下属（没有就整个 tab 不渲染，不留一个常年空的入口） */
    boolean hasSubordinates();

    /** 直接下属在指定年度下的报告列表；year 为空时取当前进行中年度 */
    List<PaReportVO.ListItem> subordinateReports(Integer year);

    /** 某位下属某张页面的报告；校验「这个页面的人是不是我的直接下属」 */
    PaReportVO.Detail subordinateDetail(Long sheetId);

    /**
     * 封存 / 解除封存（2026-11-24，sql/64）。
     *
     * <p>封存是<b>自助视角的可见性开关</b>：没封存的报告只有管理端看得到。
     * 同时它把整份报告存成快照，之后所有人读到的都是同一份 ——
     * 只开关不冻内容的话，封存后任何一次重算都会让「已封存」变成一句空话。</p>
     *
     * <p>前置与权限（改之前先读）：</p>
     * <ol>
     *   <li>只有系统管理员 / 该维度的维度管理员能封存（与「能看测评人姓名」同一口径）；</li>
     *   <li>页面必须已算出分（{@code status = 2}），没分数的报告没有可封存的内容；</li>
     *   <li><b>全部评价人都已提交</b>（2026-11-24 需求变更，含自评）——
     *       页面状态只表示「出分了」，自评不阻塞算分、部分项没评也能出分，
     *       所以「已出分」不等于「评完了」。差人的报告封出去，缺的那一格再也补不回来；</li>
     *   <li>封存后不允许撤回 / 重算 / 重新发起，要先解除封存（拦在各自的入口上）。</li>
     * </ol>
     */

    /** 确认封存；返回封存后的报告（含封存信息） */
    PaReportVO.Detail seal(Long sheetId);

    /** 解除封存：自助视角立刻重新不可见，快照一并清空 */
    void unseal(Long sheetId);
}
