package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 维度授权条目DTO（保存授权时整体覆盖）
 */
@Data
public class DimensionAuthItemDTO {

    /**
     * 授权类型：1-分部，2-人员
     */
    @NotNull(message = "授权类型不能为空")
    private Integer authType;

    /**
     * 授权对象ID（分部ID或人员ID）
     */
    @NotNull(message = "授权对象不能为空")
    private Long authId;
}
