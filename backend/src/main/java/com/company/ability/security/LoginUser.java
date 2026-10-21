package com.company.ability.security;

import java.io.Serializable;

/**
 * 登录用户主体（SSO 认证通过后由 JWT 还原）。
 * 身份锚定泛微 HrmResource：loginid 为 OA 登录名，resourceId 为人员主键。
 *
 * @param loginid      OA 登录名（HrmResource.loginid）
 * @param resourceId   人员 ID（HrmResource.id），维度授权等按此过滤
 * @param name         姓名（HrmResource.lastname）
 * @param departmentId 部门 ID
 * @param subcompanyId 分部 ID
 */
public record LoginUser(
        String loginid,
        Long resourceId,
        String name,
        Long departmentId,
        Long subcompanyId
) implements Serializable {
}
