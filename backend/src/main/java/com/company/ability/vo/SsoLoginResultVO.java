package com.company.ability.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SSO 登录成功响应：本系统 JWT + 用户信息 + 回跳路径
 */
@Data
@AllArgsConstructor
public class SsoLoginResultVO {

    /** 本系统 JWT（前端存 localStorage，请求带 Authorization: Bearer） */
    private String token;

    /** 登录用户 */
    private CurrentUserVO user;

    /** 登录发起时记录的前端回跳路径（可为 null） */
    private String redirect;
}
