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
 * 票据模式适配器单测：地址拼装、形态声明、错误翻译。
 *
 * <p>真实兑换要连对方系统，不在单测覆盖范围（靠联调 §9 清单）。
 * 这里只测「不依赖网络也能确定对错」的部分 —— 而这些恰好是最容易写错的地方：
 * 多带一个 response_type、回调参数名用成 code、把 workcode 写成 id。</p>
 */
class TicketProviderTest {

    private final TicketProvider provider =
            new TicketProvider(new RestTemplateBuilder(), new ObjectMapper());

    private static ProviderConfig config(String authorizeUrl, String tokenUrl, String userinfoField) {
        return new ProviderConfig(
                "ticket", "client-1", "secret-1",
                null,                       // 票据模式没有「认证中心根地址」这个概念
                authorizeUrl, tokenUrl,
                null,                       // 没有第三个地址
                userinfoField,
                null, null, null, null,
                "https://ca.example.com/sso/callback/7", 7L);
    }

    @Test
    @DisplayName("类型标识为 ticket")
    void type() {
        assertEquals("ticket", provider.providerType());
        assertEquals(TicketProvider.TYPE, provider.providerType());
    }

    @Test
    @DisplayName("发起地址带 client_id / redirect_uri / state，且不带 response_type")
    void authorizeUrl() {
        String url = provider.buildAuthorizeUrl(
                config("https://portal.example.com/api/sso/authorize", "https://portal.example.com/api/sso/userinfo", null),
                "abc123");

        assertTrue(url.startsWith("https://portal.example.com/api/sso/authorize?"), url);
        assertTrue(url.contains("client_id=client-1"), url);
        assertTrue(url.contains("state=abc123"), url);
        // 回调地址必须编码，否则其中的 :// 会被对方截断
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fca.example.com%2Fsso%2Fcallback%2F7"), url);
        // response_type 是 OAuth2 的参数，票据模式没有；多传只会给对方添一个没定义的入参
        assertFalse(url.contains("response_type"), "票据模式不该带 response_type: " + url);
    }

    @Test
    @DisplayName("对方地址自带 query 时用 & 拼接，不出现第二个 ?")
    void authorizeUrlWithExistingQuery() {
        String url = provider.buildAuthorizeUrl(
                config("https://portal.example.com/api/sso/authorize?tenant=t1", "https://x/userinfo", null), "s");

        assertEquals(1, url.chars().filter(c -> c == '?').count(), "只应有一个问号: " + url);
        assertTrue(url.contains("?tenant=t1&"), url);
    }

    @Test
    @DisplayName("state 为空时不拼空的 state 参数")
    void authorizeUrlWithoutState() {
        String url = provider.buildAuthorizeUrl(
                config("https://p/authorize", "https://p/userinfo", null), null);

        assertFalse(url.contains("state="), url);
    }

    @Test
    @DisplayName("发起地址没配：报可操作的错，指明该填哪一栏，而不是拼出 null?...")
    void missingAuthorizeUrlFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.buildAuthorizeUrl(config(null, "https://p/userinfo", null), "s"));

        assertTrue(e.getMessage().contains("维度 7"), "应指明是哪个维度: " + e.getMessage());
        assertTrue(e.getMessage().contains("sso_authorize_url"), e.getMessage());
        assertTrue(e.getMessage().contains("没有固定路径"), "应说清为什么不能用根地址推导: " + e.getMessage());
    }

    @Test
    @DisplayName("票据兑换地址没配：同样报错，不拿 null 去请求")
    void missingTokenUrlFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(config("https://p/authorize", null, null), "T"));

        assertTrue(e.getMessage().contains("sso_token_url"), e.getMessage());
    }

    @Test
    @DisplayName("票据为空：直接拒，不发一次注定失败的请求")
    void blankTicketRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(config("https://p/authorize", "https://p/userinfo", null), "  "));

        assertTrue(e.getMessage().contains("票据"), e.getMessage());
    }

    @Test
    @DisplayName("票据模式的形态：两个地址、都要填、都推不出来、没有 access_token 这一步")
    void shape() {
        SsoShape shape = provider.shape();

        assertThat(shape.fields()).extracting(SsoField::key)
                .containsExactly("authorizeUrl", "tokenUrl");
        assertThat(shape.fields()).allMatch(SsoField::required);
        // 没有 access_token 这一步：配置页据此不显示「access_token 字段名」「用户信息请求方式」
        assertFalse(shape.accessTokenStep());
        // 推不出来：配置页据此不显示认证中心根地址框和「用根地址重新填」（留着只会让人以为填了有用）
        assertFalse(shape.derivableFromBase());
        // 票据模式没有「用户信息地址」这个概念，页面不该出现这个框
        assertFalse(shape.uses("profileUrl"));
    }

    @Test
    @DisplayName("三个固定路径都是 null：本协议无法由根地址推导任何地址")
    void noFixedPaths() {
        assertNull(provider.defaultAuthorizePath());
        assertNull(provider.defaultTokenPath());
        assertNull(provider.defaultProfilePath());
        // 因此 resolveUrl 在没配完整地址时返回 null，页面就显示空框
        assertNull(provider.resolveUrl(null, "https://portal.example.com", provider.defaultTokenPath()));
    }

    @Test
    @DisplayName("兑换请求方式没配：报明确一条，不再静默按 GET 走")
    void missingTokenMethodFailsClearly() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.exchange(config("https://p/authorize", "https://p/userinfo", "workcode"), "T"));

        assertTrue(e.getMessage().contains("维度 7"), e.getMessage());
        assertTrue(e.getMessage().contains("sso_token_method"), e.getMessage());
    }

    @Test
    @DisplayName("不支持统一注销：不拼注销地址（规范里 logout 是可选项）")
    void noLogoutUrl() {
        assertNull(provider.buildLogoutUrl(config("https://p/authorize", "https://p/userinfo", null)));
    }
}
