package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 能力项等级 DTO（新增/更新共用）。
 *
 * <p>{@code id} 为空表示新增（整批覆盖时新建），有值表示更新已有档。
 * 等级不做逻辑删除：整批覆盖时，未出现在请求里的旧档会被<b>物理删除</b>，
 * 删除前由服务层校验有没有被评级引用。</p>
 */
@Data
public class PaItemLevelDTO {

    /** 空 = 新增 */
    private Long id;

    @NotBlank(message = "等级名称不能为空")
    private String levelName;

    @NotNull(message = "等级分值不能为空")
    private BigDecimal score;

    /** 行为描述，可空 */
    private String levelDesc;

    private Integer sort = 0;
}
