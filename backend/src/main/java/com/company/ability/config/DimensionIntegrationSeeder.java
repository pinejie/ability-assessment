package com.company.ability.config;

import com.company.ability.security.SecretCipher;
import com.company.ability.service.DimensionIntegrationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 维度集成配置的启动准备：回填种子数据里的 {@code __PENDING__} 密钥。
 *
 * <p><b>为什么需要这一步</b>：{@code sql/68} 把现有泛微配置灌进表里，
 * 但 client_secret 必须是密文，而密文依赖运行时的主密钥 —— SQL 脚本算不出来，
 * 把明文写进脚本又违背「仓库零明文」（这正是 secret.yml 被 gitignore 的原因）。
 *
 * <p><b>迁移路径</b>（第一轮部署）：
 * <ol>
 *   <li>记下原来 {@code oa.sso.client-secret} 的值（yml 或 secret.yml 里）</li>
 *   <li>执行 sql/68 → 表里有若干行，secret 是 {@code __PENDING__}</li>
 *   <li>把它设成环境变量 {@code OA_SSO_CLIENT_SECRET}，同时配好 {@code INTEGRATION_MASTER_KEY}</li>
 *   <li>启动应用 → 本类读环境变量 → 加密 → 写回 → 清缓存</li>
 *   <li>确认表里不再有待回填行，即可删掉那个环境变量</li>
 * </ol>
 * 之所以只认环境变量、不再回读 yml：{@code oa.sso.*} 已随改造删除（验收项 A7），
 * 再读它等于把刚拆掉的耦合又装回去。</p>
 *
 * <p><b>两轮之后</b>：表里没有 {@code __PENDING__} 了，本类无事可做，直接返回。</p>
 *
 * <p><b>主密钥缺失时不阻断启动</b>：测试环境与纯业务开发不需要它。
 * 等到真的要登录时才报错，错误信息里写清怎么生成和配置。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DimensionIntegrationSeeder {

    private static final String PENDING = "__PENDING__";

    private final JdbcTemplate jdbc;
    private final SecretCipher secretCipher;
    private final DimensionIntegrationService integrationService;

    /**
     * 泛微应用密钥，仅用于第一轮迁移回填。
     *
     * <p>取值顺序（先环境变量，后本地配置）：
     * <ol>
     *   <li>{@code OA_SSO_CLIENT_SECRET} 环境变量</li>
     *   <li>{@code secret.yml} 里遗留的 {@code oa.sso.client-secret}</li>
     * </ol>
     * 第二项之所以还留着：那个文件是 gitignored 的本地敏感配置，不是
     * {@code application.yml} 里被删掉的那段 —— 验收项 A7 要求删的是后者。
     * 让第一轮部署不必额外设环境变量，直接重启即可完成回填，少一步出错机会。
     * 回填完成后这两处都可以删，表里已是密文。</p>
     */
    @Value("${OA_SSO_CLIENT_SECRET:${oa.sso.client-secret:}}")
    private String legacyClientSecret;

    @PostConstruct
    public void backfillPendingSecrets() {
        List<Long> pending;
        try {
            pending = jdbc.queryForList(
                    "SELECT dimension_id FROM uf_assess_dimension_integration WHERE sso_client_secret = ?",
                    Long.class, PENDING);
        } catch (Exception e) {
            // 表还没建（第一次部署、脚本尚未执行）：不阻断启动，只提示
            log.warn("读取维度集成配置失败（表可能尚未创建，请执行 sql/66-68）：{}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }

        String secret = legacyClientSecret == null || legacyClientSecret.isBlank() ? null : legacyClientSecret;
        if (secret == null) {
            throw new IllegalStateException(
                    "维度集成配置有 " + pending.size() + " 行密钥待回填（" + pending + "），"
                            + "但既没有环境变量 OA_SSO_CLIENT_SECRET，"
                            + "secret.yml 里也没有遗留的 oa.sso.client-secret。\n"
                            + "修复：设置环境变量 OA_SSO_CLIENT_SECRET=<泛微应用密钥> 后重启，"
                            + "或在「维度管理 → 集成配置」里手工填写。");
        }
        if (!secretCipher.isConfigured()) {
            throw new IllegalStateException(
                    "维度集成配置的密钥需要加密入库，但 INTEGRATION_MASTER_KEY 未配置。\n"
                            + "生成：openssl rand -base64 32\n"
                            + "然后配置到环境变量 INTEGRATION_MASTER_KEY 并重启。"
                            + "注意：主密钥一旦丢失，所有维度配置需要重录，务必另行备份。");
        }

        String encrypted = secretCipher.encrypt(secret);
        for (Long dimensionId : pending) {
            jdbc.update("UPDATE uf_assess_dimension_integration SET sso_client_secret = ? WHERE dimension_id = ?",
                    encrypted, dimensionId);
        }
        integrationService.invalidate();
        log.info("已回填 {} 个维度的单点登录密钥（维度 {}）", pending.size(), pending);
    }
}
