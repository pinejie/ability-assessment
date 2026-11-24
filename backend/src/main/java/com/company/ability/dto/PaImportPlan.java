package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入执行计划：用户在预览页做出的四类决定。
 *
 * <p><b>为什么这些必须是显式入参，而不是服务端默认</b>：这四件事都会造成不可逆的副作用 ——
 * 覆盖既有能力模型、重算已封存报告、撤回进行中的测评、给评价人重发邀请。
 * 其中任何一件「猜」错了，客户看到的都是「我没让你动，你动了」。
 * 所以服务端一个默认值都不给：前端不传就按「什么都不做」处理。</p>
 */
@Data
public class PaImportPlan {

    /**
     * 同部门同名岗位已存在时怎么办。
     *
     * <p>{@code OVERWRITE} 是业务确认的口径（一致也好不一致也好，都以文件为准）。
     * {@code SKIP} 用于「我只想补建新岗位」的场景。</p>
     */
    private String existing = OVERWRITE;

    /** 覆盖：以导入文件为准 */
    public static final String OVERWRITE = "OVERWRITE";
    /** 跳过：已存在的岗位原样不动 */
    public static final String SKIP = "SKIP";

    /**
     * 是否重新生成已封存（已出分）的报告。
     *
     * <p>默认 false = 保持原样。已封存报告是发给过业务方的东西，
     * 导入改模型后把它悄悄重算成另一个分数，是最不能接受的一类静默变更。</p>
     */
    private boolean regenerateFrozen;

    /**
     * 按年度勾选要重算的封存报告（{@code uf_assess_period.year}）。
     * 为空 = 全选（前端「全部年度」勾选时就不传）。</p>
     */
    private List<Integer> regenerateYears = new ArrayList<>();

    /**
     * 是否撤回进行中的测评任务。
     *
     * <p>模型改了、问卷却还是旧的，那些任务评出来的分数没有意义 ——
     * 但撤回本身会作废评价人已提交的内容，所以必须用户点头。</p>
     */
    private boolean voidRunning;

    /**
     * 撤回后是否按原参数重新发起（业务选的「乙」方案）。
     *
     * <p>代价必须让用户知道：评价人会<b>收到两条消息</b>（一条撤回、一条新邀请），
     * 已提交的内容作废。换成「静默刷新」看起来更省事，但那等于把评价人填过的东西偷偷换掉。</p>
     */
    private boolean relaunchRunning = true;

    /**
     * 撤回原因（会原样写进发给评价人的消息）。必填性由服务层校验：
     * 只有真的要撤回时才要求，不撤回时前后端都不该拦。
     */
    private String voidReason;

    /** 未传时的安全默认：跳过已存在的岗位，什么都不动 */
    public static PaImportPlan safeDefault() {
        PaImportPlan plan = new PaImportPlan();
        plan.setExisting(SKIP);
        plan.setRegenerateFrozen(false);
        plan.setVoidRunning(false);
        plan.setRelaunchRunning(false);
        return plan;
    }

    // ------------------------------------------------------------------ 会话内的其他状态

    /**
     * 用户勾选要导入的 sheet。
     *
     * <p>计划对象同时承载「这一轮的输入」（勾选、回答），因为它们都是「用户在本次导入里做过的选择」，
     * 分两个字段存会在前端回显时打架（哪个才是最新的？）。</p>
     */
    private List<String> selectedSheets = new ArrayList<>();

    /**
     * 已确认的回答：slot → 值。
     *
     * <p>值是「已规范化」的：部门存成 {@code 名称#id}（名字给人看、id 给代码用），
     * 权重类存 {@code keep} / {@code normalize} 这类指令而不是数字（数字由代码算，不由模型给）。</p>
     */
    private Map<String, String> resolved = new java.util.LinkedHashMap<>();

    public boolean isOverwrite() {
        return !SKIP.equalsIgnoreCase(existing);
    }

    /** 某个年度是否需要重算：没勾年份就全都要 */
    public boolean shouldRegenerate(int year) {
        if (!regenerateFrozen) {
            return false;
        }
        return regenerateYears == null || regenerateYears.isEmpty() || regenerateYears.contains(year);
    }
}
