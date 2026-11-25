package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SSO 回调请求体（前端 /sso/callback 页解析 URL 后提交）
 */
@Data
public class SsoCallbackDTO {

    /** 认证中心回调携带的临时授权码（URL 参数名为 ticket，即文档中的 code，60 秒一次有效） */
    @NotBlank(message = "ticket 不能为空")
    private String ticket;

    /** 登录发起时签发、认证中心原样返回的 state */
    private String state;

    /**
     * 维度 ID：从哪套配置发起的登录，就用哪套配置兑换票据。
     *
     * <p>可以为空。空时按默认维度处理 —— 泛微门户里已经挂出去的旧链接不带维度号，
     * 这些链接必须还能登进来。</p>
     *
     * <p>这个值只用来<b>找配置行</b>，不参与任何身份判断：认证完成后用户属于哪个维度
     * 由人员表反查。所以前端传错顶多是配置找错、兑换失败，不会造成越权。</p>
     */
    private Long dimensionId;
}
