package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 能力类别创建 DTO（岗位内）。
 *
 * <p>postId 走路径参数 {@code /pa/posts/{postId}/categories}，不在请求体里 ——
 * 两处都给会让「body 里填 8、路径写 9」成为合法请求，服务层就无法判断该信哪个。</p>
 */
@Data
public class PaCategoryCreateDTO {

    @NotBlank(message = "类别名称不能为空")
    @Size(max = 100, message = "类别名称长度不能超过100")
    private String categoryName;

    @Size(max = 500, message = "类别说明长度不能超过500")
    private String description;

    private Integer sort = 0;

    /** 1-启用 0-停用 */
    private Integer status = 1;
}
