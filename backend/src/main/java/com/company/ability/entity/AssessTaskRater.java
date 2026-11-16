package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价人任务：任务下的一位评价人及其专属链接（表 uf_assess_task_rater）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §5.2
 *
 * status：0-待评 1-已提交 3-已作废（随任务撤回）。
 * 无「已过期」（不设截止时间）、无「已驳回」（提交即终态）。
 *
 * draft_json 格式 {"59":3,"60":4}，提交后保留用于回显；
 * 任务撤回时连同 status 一并清空。
 */
@Data
@TableName("uf_assess_task_rater")
public class AssessTaskRater {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 评价人 HrmResource.id（客户评价人也是人员表记录） */
    private Long raterResourceId;

    /** LEADER / CLIENT / SELF */
    private String raterType;

    /** 0-待评 1-已提交 3-已作废 */
    private Integer status;

    /** 草稿，格式 {"elementId": levelNo} */
    private String draftJson;

    private LocalDateTime draftTime;

    /** 草稿版本，多标签页并发保护（§4.2.2） */
    private Integer draftVersion;

    private LocalDateTime submitTime;

    private Integer remindCount;

    private LocalDateTime lastRemindTime;

    /** 专属链接令牌，Base64URL(32B) */
    private String accessToken;

    private LocalDateTime createTime;
}
