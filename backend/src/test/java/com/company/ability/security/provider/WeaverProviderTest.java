package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泛微适配器单测：URL 拼装、错误提示、脱敏。
 * 兑换逻辑依赖真实认证中心，不在单测覆盖范围（靠联调）。
 */
class WeaverProviderTest {

    private final WeaverProvider provider =
            new WeaverProvider(new RestTemplateBuilder(), new com.fasterxml.jackson.databind.ObjectMapper());

    private static ProviderConfig config(String baseUrl, String authorizeUrl, String tokenUrl, String profileUrl) {
        return new ProviderConfig(
                "weaver", "client-1", "secret-1",
                baseUrl, authorizeUrl, tokenUrl, profileUrl,
                "id", null, null, null, null,
                "https://ca.example.com/sso/callback", 3L);
    }

    /** 带第三方参数覆盖的配置（回调参数名 / 请求方式 / 取码字段） */
    private static ProviderConfig paramConfig(String callbackParam, String tokenMethod,
                                              String profileMethod, String accessTokenField) {
        return new ProviderConfig(
                "weaver", "client-1", "secret-1",
                "http://h:8080", null, null, null,
                "id", callbackParam, tokenMethod, profileMethod, accessTokenField,
                "https://ca.example.com/sso/callback", 3L);
    }

    @Test
    @DisplayName("类型标识为 weaver，且与种子数据一致")
    void type() {
        assertEquals("weaver", provider.providerType());
        assertEquals(WeaverProvider.TYPE, provider.providerType());
    }

    @Test
    @DisplayName("authorize 地址由 base-url 拼出，带着 client_id / state / 编码后的回调地址")
    void authorizeUrlFromBase() {
        String url = provider.buildAuthorizeUrl(
                config("http://xcx.zhongda.cn:8080", null, null, null), "abc123");

        assertTrue(url.startsWith("http://xcx.zhongda.cn:8080/sso/oauth2.0/authorize?"), url);
        assertTrue(url.contains("client_id=client-1"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("state=abc123"), url);
        // 回调地址必须编码，否则其中的 :// 会被对方截断
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fca.example.com%2Fsso%2Fcallback"), url);
    }

    @Test
    @DisplayName("base-url 末尾带斜杠不会拼出双斜杠")
    void authorizeUrlTrailingSlash() {
        String url = provider.buildAuthorizeUrl(
                config("http://h:8080/", null, null, null), "s");

        assertTrue(url.contains("http://h:8080/sso/oauth2.0/authorize"), url);
        assertTrue(!url.contains("//sso"), "不应出现双斜杠: " + url);
    }

    @Test
    @DisplayName("显式配置的完整地址优先于 base-url")
    void explicitUrlWins() {
        String url = provider.buildAuthorizeUrl(
                config("http://ignored:1", "http://custom/auth", null, null), "s");

        assertTrue(url.startsWith("http://custom/auth?"), url);
    }

    @Test
    @DisplayName("base-url 与显式地址都为空：报可操作的错，而不是拼出 null/sso/...")
    void missingUrlFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.buildAuthorizeUrl(config(null, null, null, null), "s"));

        assertTrue(e.getMessage().contains("维度 3"), "应指明是哪个维度: " + e.getMessage());
        assertTrue(e.getMessage().contains("接口地址未配置"), e.getMessage());
    }

    @Test
    @DisplayName("泛微三个接口的固定路径：配置页靠它把根地址展开成完整地址")
    void protocolPaths() {
        assertEquals("/sso/oauth2.0/authorize", provider.defaultAuthorizePath());
        assertEquals("/sso/oauth2.0/accessToken", provider.defaultTokenPath());
        assertEquals("/sso/oauth2.0/profile", provider.defaultProfilePath());
    }

    @Test
    @DisplayName("接口的路径默认是 null：标准 OAuth2 无法由根地址推出路径")
    void interfacePathsAreNullByDefault() {
        IdentityProvider standard = new IdentityProvider() {
            @Override
            public String providerType() {
                return "test-standard";
            }

            @Override
            public String buildAuthorizeUrl(ProviderConfig config, String state) {
                return "";
            }

            @Override
            public String exchange(ProviderConfig config, String ticket) {
                return "";
            }
        };

        assertNull(standard.defaultAuthorizePath());
        assertNull(standard.defaultTokenPath());
        assertNull(standard.defaultProfilePath());
        // 推不出来时 resolveUrl 返回 null（页面显示为空），而不是拼出「根地址 + null」
        assertNull(standard.resolveUrl(null, "http://h:8080", standard.defaultTokenPath()));
    }

    @Test
    @DisplayName("默认形态：三个地址全必填、不能由根地址推导（标准 OAuth2 没有固定路径）")
    void defaultShapeForPathlessProtocol() {
        IdentityProvider standard = new IdentityProvider() {
            @Override
            public String providerType() {
                return "test-standard";
            }

            @Override
            public String buildAuthorizeUrl(ProviderConfig config, String state) {
                return "";
            }

            @Override
            public String exchange(ProviderConfig config, String ticket) {
                return "";
            }
        };

        var shape = standard.shape();

        assertThat(shape.fields()).extracting(f -> f.key())
                .containsExactly("authorizeUrl", "tokenUrl", "profileUrl");
        assertThat(shape.fields()).allMatch(f -> f.required());
        assertThat(shape.accessTokenStep()).as("标准 OAuth2 也是 code→token→userinfo 三步").isTrue();
        // derivableFromBase 由三个 path 方法推出，不让人手填 —— 否则会出现
        // 「声明说推不出来、但路径方法返回了非空」这种自相矛盾
        assertThat(shape.derivableFromBase()).isFalse();
    }

    @Test
    @DisplayName("泛微形态：两个地址要填完整 URL，可点按钮由根地址代填；用户信息地址不上页面")
    void weaverShape() {
        var shape = provider.shape();

        assertThat(shape.fields()).extracting(f -> f.key())
                .containsExactly("authorizeUrl", "tokenUrl");
        assertThat(shape.fields()).allMatch(f -> f.required());
        assertThat(shape.derivableFromBase()).isTrue();
        assertThat(shape.accessTokenStep()).isTrue();
        // 用户信息地址的路径是协议固定的（根地址 + /sso/oauth2.0/profile），
        // 让每个维度手填一遍只是多一处能配错的地方，所以它不算本协议的配置项
        assertThat(shape.uses("profileUrl")).isFalse();
        assertThat(shape.field("nope")).isNull();
    }

    @Test
    @DisplayName("用户信息地址不进配置项，但请求时仍按根地址 + 固定路径求出")
    void profileUrlStillResolvedFromBase() {
        var config = config("http://oa:8080", null, null, null);

        assertEquals("http://oa:8080/sso/oauth2.0/profile", provider.resolveUrl(
                config.profileUrl(), config.baseUrl(), provider.defaultProfilePath()));
    }

    @Test
    @DisplayName("resolveUrl：填了完整地址就用它，否则用根地址 + 路径，末尾斜杠不产生双斜杠")
    void resolveUrlRules() {
        assertEquals("http://custom/auth",
                provider.resolveUrl("http://custom/auth", "http://ignored:1", provider.defaultAuthorizePath()));
        assertEquals("http://h:8080/sso/oauth2.0/profile",
                provider.resolveUrl(null, "http://h:8080", provider.defaultProfilePath()));
        assertEquals("http://h:8080/sso/oauth2.0/profile",
                provider.resolveUrl("  ", "http://h:8080/", provider.defaultProfilePath()));
        assertNull(provider.resolveUrl(null, null, provider.defaultProfilePath()));
    }

    @Test
    @DisplayName("注销地址取自认证中心 origin，回调到前端站点根")
    void logoutUrl() {
        String url = provider.buildLogoutUrl(
                config("http://xcx.zhongda.cn:8080", null, null, null));

        assertTrue(url.startsWith("http://xcx.zhongda.cn:8080/sso/logout?service="), url);
        assertTrue(url.contains("service=https%3A%2F%2Fca.example.com"), url);
    }

    @Test
    @DisplayName("ProviderConfig.toString 不含密钥明文")
    void configToStringMasksSecret() {
        ProviderConfig c = config("http://h", null, null, null);
        String text = c.toString();

        assertNotNull(text);
        assertTrue(text.contains("clientSecret=***"), text);
        assertTrue(!text.contains("secret-1"), "不应出现密钥明文: " + text);
    }

    @Test
    @DisplayName("default buildLogoutUrl 对不支持统一注销的协议返回 null")
    void defaultLogoutIsNull() {
        IdentityProvider minimal = new IdentityProvider() {
            @Override public String providerType() { return "test"; }
            @Override public String buildAuthorizeUrl(ProviderConfig c, String s) { return ""; }
            @Override public String exchange(ProviderConfig c, String t) { return ""; }
        };

        assertNull(minimal.buildLogoutUrl(config("http://h", null, null, null)));
    }

    // ==================== 第三方提供的参数（2026-11-25 起必须显式配置） ====================

    @Test
    @DisplayName("兑换请求方式没配：报明确一条，不再静默按 GET 走")
    void missingTokenMethodFailsClearly() {
        // token 地址配全了、票据也给了，唯一缺的就是请求方式
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(paramConfig(null, null, null, null), "T"));

        assertTrue(e.getMessage().contains("维度 3"), e.getMessage());
        assertTrue(e.getMessage().contains("sso_token_method"), e.getMessage());
    }

    @Test
    @DisplayName("空白字符串也算没配：不做 trim 后兜底，缺就报错")
    void blankConfigIsMissingNotDefault() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(paramConfig("   ", "  ", "", null), "T"));

        assertTrue(e.getMessage().contains("sso_token_method"), e.getMessage());
    }
}
