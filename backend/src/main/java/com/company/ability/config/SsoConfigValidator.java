package com.company.ability.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSO / JWT 配置启动校验：登录强校验开启时，密钥与地址必须真实有效，
 * 否则启动即失败并给出明确修复指引（避免带错误配置上线后在用户点击时才暴露）。
 */
@Slf4j
@Component
public class SsoConfigValidator {

    private static final String JWT_SECRET_PLACEHOLDER = "your-secret-key-must-be-at-least-32-characters-long";

    /** 默认值与 SecurityConfig 保持同向（fail-closed）：配置缺失时按强校验处理 */
    @Value("${assess.auth.enforced:true}")
    private boolean authEnforced;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${oa.sso.base-url:}")
    private String oaBaseUrl;

    @Value("${oa.sso.client-id:}")
    private String oaClientId;

    @Value("${oa.sso.client-secret:}")
    private String oaClientSecret;

    @Value("${oa.sso.redirect-uri:}")
    private String oaRedirectUri;

    @PostConstruct
    public void validate() {
        if (!authEnforced) {
            log.warn("assess.auth.enforced=false：接口未启用登录校验，仅限开发调试，禁止暴露公网");
            return;
        }
        StringBuilder problems = new StringBuilder();
        if (jwtSecret.isBlank() || JWT_SECRET_PLACEHOLDER.equals(jwtSecret) || jwtSecret.length() < 32) {
            problems.append("\n  - jwt.secret 未配置或为占位值（需至少 32 字符随机串）");
        }
        if (!oaBaseUrl.startsWith("http")) {
            problems.append("\n  - oa.sso.base-url 未配置（泛微认证中心地址，如 http://xcx.zhongda.cn:8080）");
        }
        if (oaClientId.isBlank()) {
            problems.append("\n  - oa.sso.client-id 未配置（认证中心注册的应用标识）");
        }
        if (oaClientSecret.isBlank()) {
            problems.append("\n  - oa.sso.client-secret 未配置（请创建 backend/config/secret.yml，参考 docs）");
        }
        if (oaRedirectUri.isBlank()) {
            problems.append("\n  - oa.sso.redirect-uri 未配置（本系统 SSO 回调地址，须与泛微注册一致）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "登录强校验已开启（assess.auth.enforced=true），但配置不完整:" + problems
                    + "\n修复后重启；或临时回退：application.yml 设 assess.auth.enforced: false");
        }
        log.info("SSO 配置校验通过：base-url={} client-id={} redirect-uri={}", oaBaseUrl, oaClientId, oaRedirectUri);
    }
}
