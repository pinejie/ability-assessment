package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测评结果：AssessSubject（表 uf_assess_subject）
 */
@Data
@TableName("uf_assess_subject")
public class AssessSubject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;

    private Long periodId;

    /**
     * 所属维度（类租户），由期次传导写入
     */
    private Long dimensionId;

    private Long resourceId;

    private Long departmentId;

    private Long jobTitleId;

    private Long workflowRequestId;

    private Integer status;

    private BigDecimal standardScore;

    private BigDecimal leaderScore;

    private BigDecimal clientScore;

    private BigDecimal selfScore;

    private BigDecimal actualScore;

    private BigDecimal scoreRate;

    private String gradeName;

    private String snapshotJson;

    private Long archivedBy;

    private LocalDateTime archiveTime;

    private Integer revision;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
