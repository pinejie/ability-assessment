package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 能力项更新 DTO。
 *
 * <p>不允许改 postId：能力项跨岗位搬家会连带等级与历史引用，语义上应删了重建。</p>
 *
 * <p>{@code levels} 传 null 表示「不动等级」；传空列表/有内容的列表表示
 * <b>整批覆盖</b> —— 页面上等级是在同一个弹窗里改完一起保存的，
 * 逐档增删接口反而会出现「删了旧档但新档没存上」的中间态。</p>
 */
@Data
public class PaItemUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    private Long categoryId;

    @Size(max = 50, message = "能力项编码长度不能超过50")
    private String itemCode;

    @Size(max = 100, message = "能力项名称长度不能超过100")
    private String itemName;

    private String definition;

    private BigDecimal weight;

    private Integer raterLeader;

    private Integer rater360;

    /** 评价主体描述（选填）；传空串表示清空，传 null 表示不改 */
    @Size(max = 100, message = "评价主体描述长度不能超过100")
    private String raterText;

    private Integer sort;

    private Integer status;

    @Valid
    private List<PaItemLevelDTO> levels;
}
