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

    /**
     * 是否系统管理员（超管 ∪ 表管理员）。服务端实时判定，
     * 前端菜单/路由门控用；权威拦截仍在各接口 requireAdmin，此字段仅体验层。
     */
    private boolean admin;

    /**
     * 是否超级管理员（仅 assess.bootstrap-admins 配置名单）：管理员配置页专属
     */
    private boolean superAdmin;

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
