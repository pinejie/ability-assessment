package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评页：一个被测人一个年度一页。
 *
 * <p>替代旧 {@code uf_assess_sheet}。旧表最大的坑是「active 与 assess_in_flow 双口径」
 * （见《15-评价网页化改造方案》§14）：存储过程按 row.active 数要素、
 * 网页按 element.assess_in_flow 收评级，两者漂移就出现「页面收了 19 条、
 * 算分认为应有 20 条，全员提交也不出分」。新模型只有
 * {@link PaSheetItem} 一个口径，不存在双开关。</p>
 *
 * <p>status：0 未评价 / 1 部分提交 / 2 已算分。
 * <b>自评未交不改变本列</b>（口径 #13：自评不参与计算，不阻断出分，任务侧另标「待自评」）。</p>
 */
@Data
@TableName("uf_pa_sheet")
public class PaSheet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long dimensionId;

    private Long periodId;

    private Long resourceId;

    private Long postId;

    /** 部门快照（来自岗位） */
    private Long departmentId;

    private String departmentName;

    private String postName;

    /** 直接上级快照（来自 OA 组织关系，不可人工指定） */
    private Long leaderResourceId;

    /** 0-未评价 1-部分提交 2-已算分 */
    private Integer status;

    /** 有效能力项数 */
    private Integer itemCount;

    /** 自评总分，只展示不进公式 */
    private BigDecimal selfScore;

    private BigDecimal leaderScore;

    /** 360 测评人均分汇总 */
    private BigDecimal rater360Score;

    /** 加权平均总得分 */
    private BigDecimal totalScore;

    /** 等级说明（命中 uf_pa_band） */
    private String bandName;

    /** 合并评语：直接上级 + 各 360 的文字按人拼接，不区分来源（口径 #14） */
    private String commentText;

    /** 算分口径快照（权重/等级/区间），保证历史报告可复现 */
    private String snapshotJson;

    /**
     * 封存状态（sql/64）：0 未封存 / 1 已封存。
     *
     * <p>封存是<b>可见性开关</b>：未封存的报告只有管理端看得到，被测评人、直接上级、
     * 下属这些自助视角一律看不到。管理端不受影响。</p>
     */
    private Integer sealStatus;

    private LocalDateTime sealTime;

    /** 封存操作人（OA 登录名 / 姓名），用于报告上「已封存于 X · 张三」那行 */
    private String sealByLoginid;

    private String sealByName;

    /**
     * 封存那一刻的整份报告 JSON（{@code PaReportVO.Detail}）。
     *
     * <p>为什么存整份而不只存状态位：封存后只要有人重算或重新导入评级，
     * 本人看到的报告就悄悄变了，「已封存」这个标签就等于撒谎。
     * 已封存的页面一律读这里，解除封存后回到实时数据。</p>
     */
    private String sealedJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
