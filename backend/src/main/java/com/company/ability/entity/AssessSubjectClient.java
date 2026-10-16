package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 测评结果：AssessSubjectClient（表 uf_assess_subject_client）
 */
@Data
@TableName("uf_assess_subject_client")
public class AssessSubjectClient {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long subjectId;

    private Long clientResourceId;

    private Integer clientSeq;

    private Long elementId;

    private Integer levelNo;

    private BigDecimal score;

}
