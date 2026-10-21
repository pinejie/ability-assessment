package com.company.ability.service;

import com.company.ability.security.LoginUser;

/**
 * 泛微 OAuth2 单点登录服务
 */
public interface SsoLoginService {

    /**
     * 生成跳转到泛微认证中心的 authorize 地址（内部签发 state）
     *
     * @param from 登录完成后前端回跳路径（站内相对路径，可为空）
     */
    String buildLoginRedirectUrl(String from);

    /**
     * 处理回调：校验 state → ticket 换 access_token → 取 OA 登录名 →
     * 匹配在职人员 → 签发本系统 JWT
     *
     * @return 登录结果（token + 用户 + 回跳地址）
     */
    SsoLoginResult authenticate(String ticket, String state);

    /**
     * 登录结果
     *
     * @param token    本系统 JWT
     * @param user     登录用户
     * @param redirect 前端回跳路径（已校验，仅站内相对路径）
     */
    record SsoLoginResult(String token, LoginUser user, String redirect) {
    }
}
