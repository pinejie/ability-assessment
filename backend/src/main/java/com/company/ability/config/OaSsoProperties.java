package com.company.ability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 泛微 OAuth2 单点登录配置（前缀 oa.sso）。
 * client-secret 由 backend/config/secret.yml 提供（已 gitignore，不提交）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oa.sso")
public class OaSsoProperties {

    /** 认证中心基地址，如 http://xcx.zhongda.cn:8080 */
    private String baseUrl;

    /** OAuth2 应用标识（泛微 weaver_sso_app 注册获得） */
    private String clientId;

    /** OAuth2 应用密钥 */
    private String clientSecret;

    /** 本系统回调地址，须与泛微注册的应用地址匹配 */
    private String redirectUri;

    /** 连接超时（毫秒） */
    private int connectTimeout = 5000;

    /** 读取超时（毫秒） */
    private int readTimeout = 10000;

    /** state 有效期（秒），文档要求授权码 60 秒一次有效，登录跳转应在分钟内完成 */
    private long stateTtlSeconds = 300;
}
