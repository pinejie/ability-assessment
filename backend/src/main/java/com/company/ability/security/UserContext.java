package com.company.ability.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文（基于 Spring SecurityContextHolder）。
 * 与 DimensionContext（ThreadLocal 手工维护）不同，这里由 JwtAuthFilter 统一填充。
 */
public final class UserContext {

    private UserContext() {
    }

    /**
     * 当前登录用户，未登录返回 null
     */
    public static LoginUser currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    /**
     * 当前登录人员的 resourceId（HrmResource.id），未登录返回 null
     */
    public static Long currentResourceIdOrNull() {
        LoginUser user = currentOrNull();
        return user == null ? null : user.resourceId();
    }
}
