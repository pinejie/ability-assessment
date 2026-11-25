package com.company.ability.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 * SSO 认证通过后签发本地 JWT，后续请求凭 Bearer Token 恢复登录态。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration:7200}") long expirationSeconds) {
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 为登录用户签发 JWT。
     *
     * <p>subject 用 {@code sessionId} 而不是 loginid：按工号登录的人没有登录名，
     * 空 subject 会让整个会话失去稳定标识。真实登录名单独放 {@code loginid} 声明里，
     * 供管理员判定使用（可为空）。</p>
     */
    public String generate(LoginUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.sessionId())
                .claim("uid", user.resourceId())
                .claim("name", user.name())
                .claim("dept", user.departmentId())
                .claim("subco", user.subcompanyId());
        // jjwt 会忽略 value 为 null 的 claim（等于没写），parse 那边就分不清
        // 「老 token 没这个声明」和「新 token 明确没有登录名」了。所以显式写空串占位。
        builder.claim("loginid", user.loginid() == null ? "" : user.loginid());
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 JWT，非法/过期抛出签名校验异常
     */
    public LoginUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String subject = claims.getSubject();
        String loginidClaim = claims.get("loginid", String.class);
        // 老 token（改造前签发）没有 loginid 声明，那时 subject 就是登录名，直接回退。
        // 空串表示「这个人确实没有登录名」，必须回 null 而不是空串 ——
        // 管理员判定等处用 isBlank 兜底，但保留 null 语义更清楚。
        String loginid = loginidClaim == null ? subject
                : (loginidClaim.isBlank() ? null : loginidClaim);
        return new LoginUser(
                loginid,
                subject,
                asLong(claims.get("uid")),
                claims.get("name", String.class),
                asLong(claims.get("dept")),
                asLong(claims.get("subco"))
        );
    }

    private Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
