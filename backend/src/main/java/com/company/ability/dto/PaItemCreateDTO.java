package com.company.ability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 能力项创建 DTO。
 *
 * <p><b>基本字段与等级一起提交</b>：等级是能力项的固有部分（没等级的能力项在测评页上
 * 一张卡片都渲染不出来），分两次请求保存会出现「能力项已存在但没有等级」的中间态。
 * 一次事务里先存能力项再整批写等级。</p>
 *
 * <p>postId 走路径参数，不在请求体里（避免「body 填 8、路径写 9」时不知道该信哪个）。</p>
 */
@Data
public class PaItemCreateDTO {

    @NotNull(message = "能力类别不能为空")
    private Long categoryId;

    /** 岗位内唯一，可空 */
    @Size(max = 50, message = "能力项编码长度不能超过50")
    private String itemCode;

    @NotBlank(message = "能力项名称不能为空")
    @Size(max = 100, message = "能力项名称长度不能超过100")
    private String itemName;

    private String definition;

    /** 权重，百分数：8 = 8% */
    @NotNull(message = "权重不能为空")
    private BigDecimal weight;

    /** 评级主体：直接上级 */
    private Integer raterLeader = 1;

    /** 评级主体：360 测评人 */
    private Integer rater360 = 0;

    /**
     * 评价主体描述（选填）：Excel 那列的原文或手工补充的说明，如「内部客户360＋直接上级」。
     * 只做展示与追溯，不参与算分。
     */
    @Size(max = 100, message = "评价主体描述长度不能超过100")
    private String raterText;

    private Integer sort = 0;

    private Integer status = 1;

    /** 等级列表（至少 2 档，见 PaItemDTO.PaItemLevelDTO） */
    @Valid
    private List<PaItemLevelDTO> levels = new ArrayList<>();
}
