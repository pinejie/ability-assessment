package com.company.ability.security;

import com.company.ability.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 state 参数的签发与校验(防 CSRF + 携带登录后回跳地址)。
 *
 * 形态:16 位十六进制短随机数,回跳地址存服务端内存映射(一次性消费,验证即移除)。
 * 为何不用自包含签名串:state 会被泛微认证中心二次编码嵌入登录中转 URL,
 * 长串 + 双重转义易被入口 WAF/安全设备按攻击特征拦截(实测发生过);短凭证可规避。
 *
 * 约束:内存态映射要求单实例部署(当前形态)。若多实例,需会话粘滞或将映射外置。
 */
@Slf4j
@Component
public class SsoStateService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SsoStateService(@Value("${oa.sso.state-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * 生成 state(16 位十六进制);from 为登录完成后前端要回跳的路径,可为空
     */
    public String make(String from) {
        purgeExpired();
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        pending.put(id, new Entry(from, System.currentTimeMillis() + ttlMillis));
        return id;
    }

    /**
     * 校验并一次性消费 state,返回回跳地址;不存在/已消费/过期均拒绝
     */
    public String verify(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(401, "缺少 state 参数,请重新从泛微入口登录");
        }
        Entry entry = pending.remove(state.trim());
        if (entry == null) {
            throw new BusinessException(401, "state 无效或已被使用,请重新从泛微入口登录");
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            throw new BusinessException(401, "登录请求已超时(超过有效期),请重新从泛微入口登录");
        }
        return entry.from() == null || entry.from().isEmpty() ? null : entry.from();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    /** 待验证登录请求 */
    private record Entry(String from, long expiresAt) {
    }
}
