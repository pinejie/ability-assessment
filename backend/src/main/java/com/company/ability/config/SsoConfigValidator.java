package com.company.ability.config;

import com.company.ability.security.SecretCipher;
import com.company.ability.service.DimensionIntegrationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动配置校验：登录强校验开启时，必需的配置必须真实有效，
 * 否则启动即失败并给出明确修复指引（避免带错误配置上线后在用户点击时才暴露）。
 *
 * <p><b>改造要点</b>：原来校验的是 {@code oa.sso.*} 那一组固定配置，
 * 现在登录配置逐维度存在数据库里，{@code oa.sso.*} 已不再参与登录。
 * 这里改为校验三件仍然属于「部署级」的东西：JWT 密钥、主密钥、站点地址，
 * 以及「至少有一个维度能登录」。维度里配错了会在登录时按维度号报错，不必在启动期枚举。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoConfigValidator {

    private static final String JWT_SECRET_PLACEHOLDER = "your-secret-key-must-be-at-least-32-characters-long";

    /** 默认值与 SecurityConfig 保持同向（fail-closed）：配置缺失时按强校验处理 */
    @Value("${assess.auth.enforced:true}")
    private boolean authEnforced;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${assess.fill-base-url:}")
    private String siteBaseUrl;

    private final SecretCipher secretCipher;
    private final DimensionIntegrationService integrationService;

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
        if (!secretCipher.isConfigured()) {
            problems.append("\n  - integration.master-key 未配置（32 字符主密钥，用于解密维度里存的客户端密钥；"
                    + "请写进 backend/config/secret.yml）");
        }
        if (!siteBaseUrl.startsWith("http")) {
            problems.append("\n  - assess.fill-base-url 未配置（站点根地址，用于拼维度的 SSO 回调地址，"
                    + "如 http://xcx.zhongda.cn:8000）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "登录强校验已开启（assess.auth.enforced=true），但配置不完整:" + problems
                    + "\n修复后重启；或临时回退：application.yml 设 assess.auth.enforced: false");
        }

        // 配置齐了还不够：库里必须真的有一个能登录的维度，否则所有人都会点进登录页后卡住
        try {
            Long defaultDimension = integrationService.defaultDimensionId();
            log.info("SSO 配置校验通过：默认登录维度={} 站点根={}", defaultDimension, siteBaseUrl);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "没有任何维度配好了单点登录（client_id 与 client_secret 都在才算配好），任何人都无法登录。"
                    + "\n修复：执行 sql/68-dimension-integration-seed.sql 给现有维度补默认配置，"
                    + "\n      或在「维度管理 → 集成配置」里把某个维度的 client_id / client_secret 填全。"
                    + "\n原因：" + e.getMessage(), e);
        }
    }
}
