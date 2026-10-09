package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 评分权重配置创建DTO
 */
@Data
public class ScoreWeightCreateDTO {

    /**
     * 分公司ID
     */
    @NotNull(message = "分公司ID不能为空")
    private Long companyId;

    /**
     * 权重名称
     */
    @NotBlank(message = "权重名称不能为空")
    @Size(max = 100, message = "权重名称长度不能超过100")
    private String weightName;

    /**
     * 类别权重（百分比）
     */
    @NotNull(message = "类别权重不能为空")
    @DecimalMin(value = "0.0", message = "类别权重不能小于0")
    @DecimalMax(value = "100.0", message = "类别权重不能大于100")
    private BigDecimal categoryWeight;

    /**
     * 要素权重（百分比）
     */
    @NotNull(message = "要素权重不能为空")
    @DecimalMin(value = "0.0", message = "要素权重不能小于0")
    @DecimalMax(value = "100.0", message = "要素权重不能大于100")
    private BigDecimal elementWeight;

    /**
     * 描述
     */
    @Size(max = 500, message = "描述长度不能超过500")
    private String description;
}
