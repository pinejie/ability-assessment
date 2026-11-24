package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 360 测评人 ↔ 能力项指派（表 uf_assess_task_rater_item，sql/60）。
 *
 * <p>为什么需要它：发起时能力项是逐项选人的（「沟通能力」让甲评、「协作能力」让乙评），
 * 而链接是<b>按人</b>发的 —— 一个人被指派到几项就一条链接填几项。
 * 「人 ↔ 项」是多对多，只能另起一张关系表。</p>
 *
 * <p><b>存量任务没有本表的行</b>：{@code sql/60} 之前的任务一律是「这批人评全部 360 项」，
 * 填写侧对「查不到指派行」退回旧口径（页面行快照 {@code rater_360 = 1} 的全部项）。
 * 不要为了整齐去回填 —— 那会把当时的口径改写成它本来不是的样子。</p>
 */
@Data
@TableName("uf_assess_task_rater_item")
public class AssessTaskRaterItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 冗余的任务号：报告/撤回都按任务过滤，少一层关联 */
    private Long taskId;

    /** uf_assess_task_rater.id */
    private Long raterId;

    /** uf_pa_item.id（与 uf_pa_sheet_item.item_id 同一口径） */
    private Long itemId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
