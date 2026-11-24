package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 能力项（原「能力要素」，2026-11-21 全局改名）。
 *
 * <p>权重挂在能力项上，类别不再有权重。</p>
 *
 * <p><b>weight 单位是百分数</b>：8.0000 表示 8%。不存 0.08 是因为页面上人看的是「8%」，
 * 存小数就得在每处显示做 ×100 换算，而"输入 8 存成 800%"是这类换算最经典的线上事故。
 * 换算只发生在导入器一处（Excel 的 0.08 × 100）。</p>
 *
 * <p>不校验权重合计 = 100%：业务明确只提醒不阻断（Excel「人事专员」那份合计 110%）。
 * 算分用加权平均，合计不为 100% 不影响结果。</p>
 *
 * <p>评级主体只有固定两项，用两个布尔列而非子表：天然支持组合（都为 1 = 上级和 360 都评），
 * 数据库 CK 保证至少选一个 —— 两项都为 0 的能力项没有任何人能评，页面永远推不动。</p>
 */
@Data
@TableName("uf_pa_item")
public class PaItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long postId;

    private Long categoryId;

    /** 岗位内唯一（Excel 里 L1/S1 等编码跨岗位大量重号） */
    private String itemCode;

    private String itemName;

    /** 能力项定义 */
    private String definition;

    /** 权重，百分数：8.0000 = 8% */
    private BigDecimal weight;

    /** 评级主体：直接上级（1-是 0-否） */
    private Integer raterLeader;

    /** 评级主体：360 测评人（1-是 0-否）。列名带下划线，须显式声明——驼峰转换不会在数字前插下划线 */
    @TableField("rater_360")
    private Integer rater360;

    /**
     * 评价主体描述：导入时 Excel 那一列的<b>原文</b>（「内部客户360＋直接上级」）。
     *
     * <p>只用于显示与追溯，<b>不参与任何计算</b> —— 算分和生成待评人一律看 raterLeader / rater360。
     * 保留原文是因为同样是 360，写「内部客户」和写「跨部门」业务含义不同，
     * 拆成两个开关后这个区别在页面上就看不出来了。</p>
     */
    private String raterText;

    private Integer sort;

    /** 1-启用 0-停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
