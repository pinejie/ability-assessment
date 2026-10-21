package com.company.ability.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 签发/校验单测（不依赖 Spring 上下文）
 */
class JwtTokenProviderTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 7200);

    @Test
    @DisplayName("签发的 JWT 可还原出完整登录用户")
    void generateThenParse() {
        LoginUser user = new LoginUser("yhm01", 3L, "演示用户", 2L, 5L);

        String token = provider.generate(user);
        LoginUser parsed = provider.parse(token);

        assertEquals(user.loginid(), parsed.loginid());
        assertEquals(user.resourceId(), parsed.resourceId());
        assertEquals(user.name(), parsed.name());
        assertEquals(user.departmentId(), parsed.departmentId());
        assertEquals(user.subcompanyId(), parsed.subcompanyId());
    }

    @Test
    @DisplayName("被篡改的 JWT 校验失败")
    void tamperedTokenRejected() {
        String token = provider.generate(new LoginUser("yhm01", 3L, "用户", 2L, 5L));
        // 篡改签名段首位（base64url 完整段内每一位都承载数据，改动必然导致验签失败；
        // 末位存在填充等价字符，翻转可能恰好无效）
        int sigStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(sigStart);
        char replaced = first == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, sigStart) + replaced + token.substring(sigStart + 1);

        assertThrows(Exception.class, () -> provider.parse(tampered));
    }

    @Test
    @DisplayName("用其他密钥无法校验")
    void wrongKeyRejected() {
        String token = provider.generate(new LoginUser("yhm01", 3L, "用户", 2L, 5L));
        JwtTokenProvider other = new JwtTokenProvider("f".repeat(64), 7200);

        assertThrows(Exception.class, () -> other.parse(token));
    }
}
