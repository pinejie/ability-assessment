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
     * 为登录用户签发 JWT
     */
    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.loginid())
                .claim("uid", user.resourceId())
                .claim("name", user.name())
                .claim("dept", user.departmentId())
                .claim("subco", user.subcompanyId())
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
        return new LoginUser(
                claims.getSubject(),
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
