package com.company.ability.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 测评结果：AssessSubjectItem（表 uf_assess_subject_item）
 */
@Data
@TableName("uf_assess_subject_item")
public class AssessSubjectItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long subjectId;

    private Long categoryId;

    private String categoryName;

    private Long elementId;

    private String elementName;

    private Integer requiredLevelNo;

    private BigDecimal standardScore;

    private Integer leaderLevelNo;

    private BigDecimal leaderScore;

    private Integer selfLevelNo;

    private BigDecimal selfScore;

}
