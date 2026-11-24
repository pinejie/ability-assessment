package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 测评页的能力项行。
 *
 * <p>能力项名、权重、类别名都是<b>快照</b>：岗位后续被改，历史报告不受影响（口径 #15）。</p>
 *
 * <p>三列得分口径（对应方案 §5.1）：
 * {@code leaderScore} / {@code rater360Score} / {@code selfScore}，
 * 其中 {@code itemScore} 是真正进入总分的项得分。
 * 自评只落 {@code selfScore}，<b>永远不进入 {@code itemScore}</b>。</p>
 */
@Data
@TableName("uf_pa_sheet_item")
public class PaSheetItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dimensionId;

    private Long sheetId;

    private Long itemId;

    private Long categoryId;

    /** 类别名快照 */
    private String categoryName;

    /** 能力项编码快照 */
    private String itemCode;

    /** 能力项名快照 */
    private String itemName;

    /** 能力项定义快照 */
    private String definition;

    private Integer sortNo;

    /** 权重快照，百分数 */
    private BigDecimal weight;

    private Integer raterLeader;

    /** 评级主体快照：360。列名 rater_360，驼峰转换不会在数字前插下划线，须显式声明 */
    @TableField("rater_360")
    private Integer rater360;

    /** 评价主体描述快照：发起测评时岗位模型里的原文，之后改模型不影响本页 */
    private String raterText;

    /** 直接上级给出的分值 */
    private BigDecimal leaderScore;

    /** 360 测评人均分（未评的人不进分母） */
    private BigDecimal rater360Score;

    /** 自评分，只展示 */
    private BigDecimal selfScore;

    /** 进入总分的项得分 = 上级与 360 已评分值的平均 */
    private BigDecimal itemScore;

    /**
     * 项得分对应的刻度名快照（2026-11-24），如「精进/能手」。
     *
     * <p>算分时按这项自己的等级分值切区间写入，之后等级被改名/改分值，
     * 历史报告仍按当时的刻度解释 —— 与 {@code leaderScore} 同属口径 #15 的快照。</p>
     *
     * <p>前缀已剥掉（见 {@code PaItemLevelScale.stripPrefix}）：库里等级名两种写法并存，
     * 不统一会让页面写成「3.67 · 3分 · 精进/能手」。项得分没算出来时为 null。</p>
     */
    private String itemLevelName;

    /** 实际参与打分的 360 人数 */
    private Integer rater360Count;
}
