package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 能力类别更新 DTO。
 *
 * <p>不允许改 postId：类别换岗位等于把它下面的能力项全都搬走，
 * 而能力项还带着等级与历史引用，语义上应当「在目标岗位新建 + 删旧的」。</p>
 */
@Data
public class PaCategoryUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    @Size(max = 100, message = "类别名称长度不能超过100")
    private String categoryName;

    @Size(max = 500, message = "类别说明长度不能超过500")
    private String description;

    private Integer sort;

    private Integer status;
}
