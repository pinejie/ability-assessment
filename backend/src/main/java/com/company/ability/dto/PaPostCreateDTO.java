package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 测评岗位创建 DTO
 */
@Data
public class PaPostCreateDTO {

    /**
     * 泛微 HrmDepartment.id。<b>传 null = 全公司通用岗位</b>（sql/63）。
     *
     * <p>不做 {@code @NotNull}：通用岗位本来就没有部门。前端用「全公司通用」开关
     * 控制这一项，勾上时不传或传 null。</p>
     */
    private Long departmentId;

    /** 岗位编码（导入对齐用，可空） */
    @Size(max = 50, message = "岗位编码长度不能超过50")
    private String postCode;

    @NotBlank(message = "岗位名称不能为空")
    @Size(max = 100, message = "岗位名称长度不能超过100")
    private String postName;

    @Size(max = 500, message = "岗位描述长度不能超过500")
    private String description;

    /** 1-启用 0-停用 */
    private Integer status = 1;

    private Integer sort = 0;
}
