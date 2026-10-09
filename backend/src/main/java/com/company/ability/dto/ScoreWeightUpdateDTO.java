package com.company.ability.dto;

import lombok.Data;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 评分权重配置更新DTO
 */
@Data
public class ScoreWeightUpdateDTO {

    /**
     * 主键ID
     */
    @NotNull(message = "ID不能为空")
    private Long id;

    /**
     * 权重名称
     */
    @Size(max = 100, message = "权重名称长度不能超过100")
    private String weightName;

    /**
     * 类别权重（百分比）
     */
    @DecimalMin(value = "0.0", message = "类别权重不能小于0")
    @DecimalMax(value = "100.0", message = "类别权重不能大于100")
    private BigDecimal categoryWeight;

    /**
     * 要素权重（百分比）
     */
    @DecimalMin(value = "0.0", message = "要素权重不能小于0")
    @DecimalMax(value = "100.0", message = "要素权重不能大于100")
    private BigDecimal elementWeight;

    /**
     * 描述
     */
    @Size(max = 500, message = "描述长度不能超过500")
    private String description;
}
