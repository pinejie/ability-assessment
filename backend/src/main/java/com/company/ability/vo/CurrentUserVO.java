package com.company.ability.vo;

import com.company.ability.security.LoginUser;
import lombok.Data;

/**
 * 当前登录用户信息
 */
@Data
public class CurrentUserVO {

    /** OA 登录名 */
    private String loginid;

    /** 泛微人员 ID（HrmResource.id） */
    private Long resourceId;

    /** 姓名 */
    private String name;

    /** 部门 ID */
    private Long departmentId;

    /** 分部 ID */
    private Long subcompanyId;

    public static CurrentUserVO from(LoginUser user) {
        CurrentUserVO vo = new CurrentUserVO();
        vo.setLoginid(user.loginid());
        vo.setResourceId(user.resourceId());
        vo.setName(user.name());
        vo.setDepartmentId(user.departmentId());
        vo.setSubcompanyId(user.subcompanyId());
        return vo;
    }
}
