package com.company.ability.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增管理员：按泛微人员选择，loginid/姓名由服务端查 HrmResource 获取（不信任前端传身份）
 */
@Data
public class AssessAdminCreateDTO {

    /** 泛微人员 ID（HrmResource.id） */
    @NotNull(message = "请选择人员")
    private Long resourceId;

    /** 备注 */
    private String remark;
}
