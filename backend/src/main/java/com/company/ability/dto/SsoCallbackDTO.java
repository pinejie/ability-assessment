package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SSO 回调请求体（前端 /sso/callback 页解析 URL 后提交）
 */
@Data
public class SsoCallbackDTO {

    /** 泛微回调携带的临时授权码（URL 参数名为 ticket，即文档中的 code，60 秒一次有效） */
    @NotBlank(message = "ticket 不能为空")
    private String ticket;

    /** 登录发起时签发、认证中心原样返回的 state */
    private String state;
}
