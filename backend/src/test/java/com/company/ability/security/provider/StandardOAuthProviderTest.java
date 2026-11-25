package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标准 OAuth2 适配器单测：地址拼装、形态声明、缺配置时的报错。
 *
 * <p>真实换 token 要连对方认证中心，不在单测覆盖范围（靠联调）。
 * 这里只测「不依赖网络也能确定对错」的部分 —— 恰好是最容易写错的地方：
 * 授权地址里少了 response_type、state 没编码、缺配置时拼出 {@code null?...}。</p>
 */
class StandardOAuthProviderTest {

    private final StandardOAuthProvider provider =
            new StandardOAuthProvider(new RestTemplateBuilder(), new ObjectMapper());

    private static ProviderConfig config(String authorizeUrl, String tokenUrl, String profileUrl) {
        return new ProviderConfig(
                StandardOAuthProvider.TYPE, "client-1", "secret-1",
                null,                       // 标准 OAuth2 没有「认证中心根地址」这个概念
                authorizeUrl, tokenUrl, profileUrl,
                "userid",
                "code", "GET", "GET", "access_token",
                "https://ca.example.com/sso/callback/7", 7L);
    }

    @Test
    @DisplayName("类型标识为 oauth2")
    void type() {
        assertEquals("oauth2", provider.providerType());
        assertEquals(StandardOAuthProvider.TYPE, provider.providerType());
    }

    @Test
    @DisplayName("发起地址带 client_id / response_type=code / 编码后的回调地址 / state")
    void authorizeUrl() {
        String url = provider.buildAuthorizeUrl(
                config("https://id.example.com/oauth/authorize",
                        "https://id.example.com/oauth/token",
                        "https://id.example.com/oauth/userinfo"),
                "abc123");

        assertTrue(url.startsWith("https://id.example.com/oauth/authorize?"), url);
        assertTrue(url.contains("client_id=client-1"), url);
        // 少了 response_type，标准 OAuth2 认证中心会直接判参数错误
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("state=abc123"), url);
        // 回调地址必须编码，否则其中的 :// 会被对方截断
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fca.example.com%2Fsso%2Fcallback%2F7"), url);
        // 不主动带 scope：有的认证中心会校验它是否在注册时声明的列表里，多传直接 invalid_scope
        assertFalse(url.contains("scope="), "本协议不主动带 scope: " + url);
    }

    @Test
    @DisplayName("对方地址自带 query 时用 & 拼接，不出现第二个 ?")
    void authorizeUrlWithExistingQuery() {
        String url = provider.buildAuthorizeUrl(
                config("https://id.example.com/authorize?tenant=t1", "https://x/token", "https://x/userinfo"),
                "s");

        assertEquals(1, url.chars().filter(c -> c == '?').count(), "只应有一个问号: " + url);
        assertTrue(url.contains("?tenant=t1&"), url);
    }

    @Test
    @DisplayName("state 为空时不拼空的 state 参数")
    void authorizeUrlWithoutState() {
        String url = provider.buildAuthorizeUrl(
                config("https://id.example.com/authorize", "https://x/token", "https://x/userinfo"), null);

        assertFalse(url.contains("state="), url);
    }

    @Test
    @DisplayName("发起地址没配：报可操作的错，指明该填哪一栏，而不是拼出 null?...")
    void missingAuthorizeUrlFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.buildAuthorizeUrl(
                        config(null, "https://x/token", "https://x/userinfo"), "s"));

        assertTrue(e.getMessage().contains("维度 7"), "应指明是哪个维度: " + e.getMessage());
        assertTrue(e.getMessage().contains("sso_authorize_url"), e.getMessage());
    }

    @Test
    @DisplayName("票据兑换地址没配：报错，不拿 null 去请求")
    void missingTokenUrlFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(
                        config("https://x/authorize", null, "https://x/userinfo"), "C"));

        assertTrue(e.getMessage().contains("sso_token_url"), e.getMessage());
    }

    @Test
    @DisplayName("授权码为空：直接拒，不发一次注定失败的请求")
    void blankTicketRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(
                        config("https://x/authorize", "https://x/token", "https://x/userinfo"), "  "));

        assertTrue(e.getMessage().contains("授权码"), e.getMessage());
    }

    @Test
    @DisplayName("兑换请求方式没配：报明确一条，不再静默按 GET 走")
    void missingTokenMethodFailsClearly() {
        ProviderConfig noMethod = new ProviderConfig(
                StandardOAuthProvider.TYPE, "client-1", "secret-1",
                null, "https://x/authorize", "https://x/token", "https://x/userinfo",
                "userid", "code", null, "GET", "access_token",
                "https://ca.example.com/sso/callback", 7L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(noMethod, "C"));

        assertTrue(e.getMessage().contains("维度 7"), e.getMessage());
        assertTrue(e.getMessage().contains("sso_token_method"), e.getMessage());
    }

    @Test
    @DisplayName("形态：三个地址全必填、有 access_token 这一步、不能由根地址推导")
    void shape() {
        SsoShape shape = provider.shape();

        assertThat(shape.fields()).extracting(SsoField::key)
                .containsExactly("authorizeUrl", "tokenUrl", "profileUrl");
        assertThat(shape.fields()).allMatch(SsoField::required);
        // 三步：页面要显示「access_token 字段名」「用户信息请求方式」
        assertTrue(shape.accessTokenStep());
        // 推不出来：页面不显示根地址框和「用根地址重新填」
        assertFalse(shape.derivableFromBase());
        // 标准 OAuth2 用得到用户信息接口，这个框必须出现
        assertTrue(shape.uses("profileUrl"));
    }

    @Test
    @DisplayName("三个固定路径都是 null：本协议无法由根地址推导任何地址")
    void noFixedPaths() {
        assertNull(provider.defaultAuthorizePath());
        assertNull(provider.defaultTokenPath());
        assertNull(provider.defaultProfilePath());
        assertNull(provider.resolveUrl(null, "https://id.example.com", provider.defaultTokenPath()));
    }

    @Test
    @DisplayName("不支持统一注销：不拼注销地址（标准 OAuth2 没有这个概念）")
    void noLogoutUrl() {
        assertNull(provider.buildLogoutUrl(
                config("https://x/authorize", "https://x/token", "https://x/userinfo")));
    }
}
