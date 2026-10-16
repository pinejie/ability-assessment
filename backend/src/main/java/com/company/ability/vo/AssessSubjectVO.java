package com.company.ability.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测评结果详情（对齐"测评汇总"sheet 的结构）
 */
@Data
public class AssessSubjectVO {

    private Long id;
    private Long periodId;
    private Long resourceId;
    private Long workflowRequestId;
    private BigDecimal standardScore;
    private BigDecimal leaderScore;
    private BigDecimal clientScore;
    private BigDecimal selfScore;
    private BigDecimal actualScore;
    /** 得分率（百分比，如 86.66） */
    private BigDecimal scoreRate;
    private String gradeName;
    private LocalDateTime archiveTime;
    private List<ItemVO> items;
    private List<ClientVO> clients;

    @Data
    public static class ItemVO {
        private Long elementId;
        private String categoryName;
        private String elementName;
        private Integer requiredLevelNo;
        private BigDecimal standardScore;
        private Integer leaderLevelNo;
        private BigDecimal leaderScore;
        private Integer selfLevelNo;
        private BigDecimal selfScore;
    }

    @Data
    public static class ClientVO {
        private Long clientResourceId;
        private Integer clientSeq;
        private BigDecimal totalScore;
    }
}
