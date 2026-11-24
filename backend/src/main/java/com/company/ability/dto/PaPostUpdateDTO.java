package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 测评岗位更新 DTO。
 *
 * <p>部门可改：岗位换部门时，历史测评页已快照部门名，不受影响（口径 #15）。
 * 改部门后要重新校验「同部门内岗位名唯一」。</p>
 *
 * <p>{@code departmentId} 传 null = 改为全公司通用岗位（sql/63）。
 * 已经发起过的测评页不受影响 —— 它们记的是当时的部门快照。</p>
 */
@Data
public class PaPostUpdateDTO {

    @NotNull(message = "ID不能为空")
    private Long id;

    /** null = 全公司通用岗位；不能靠省略字段表达「不变」，本接口是全量更新 */
    private Long departmentId;

    @Size(max = 50, message = "岗位编码长度不能超过50")
    private String postCode;

    @Size(max = 100, message = "岗位名称长度不能超过100")
    private String postName;

    @Size(max = 500, message = "岗位描述长度不能超过500")
    private String description;

    private Integer status;

    private Integer sort;
}
