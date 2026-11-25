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
 * OAuth2 state 参数的签发与校验(防 CSRF + 携带登录后回跳地址与维度号)。
 *
 * 形态:16 位十六进制短随机数,回跳地址与维度号存服务端内存映射(一次性消费,验证即移除)。
 * 为何不用自包含签名串:state 会被泛微认证中心二次编码嵌入登录中转 URL,
 * 长串 + 双重转义易被入口 WAF/安全设备按攻击特征拦截(实测发生过);短凭证可规避。
 *
 * <b>为什么维度号要跟着 state 走</b>:回调发生时浏览器只带回 ticket 和 state,不知道
 * 这次登录是从哪套配置发起的。若让前端在回调时再传一次维度号,等于让客户端决定用哪套
 * 密钥兑换票据 —— 虽然传错只会导致兑换失败、无法越权,但由服务端签发时记下更直接,
 * 也省掉前端在回调页再做一次透传。
 *
 * 约束:内存态映射要求单实例部署(当前形态)。若多实例,需会话粘滞或将映射外置。
 */
@Slf4j
@Component
public class SsoStateService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SsoStateService(@Value("${integration.state-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * 生成 state(16 位十六进制);from 为登录完成后前端要回跳的路径,可为空
     */
    public String make(String from) {
        return make(from, null);
    }

    /**
     * 生成 state,同时记下本次登录用的是哪个维度的配置
     *
     * @param from        登录完成后前端要回跳的路径,可为空
     * @param dimensionId 维度 ID,可为空(空表示发起时就没指定,回调时按默认维度处理)
     */
    public String make(String from, Long dimensionId) {
        purgeExpired();
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        pending.put(id, new Entry(from, dimensionId, System.currentTimeMillis() + ttlMillis));
        return id;
    }

    /**
     * 校验并一次性消费 state,返回回跳地址;不存在/已消费/过期均拒绝
     */
    public String verify(String state) {
        return consume(state).from();
    }

    /**
     * 校验并一次性消费 state,返回完整内容(回跳地址 + 维度号)
     *
     * <p>只能调用一次 —— 消费即移除。需要同时拿两个值的地方用这个,
     * 否则第二次调用会因已消费而失败。</p>
     */
    public Payload consume(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(401, "缺少 state 参数,请重新从入口登录");
        }
        Entry entry = pending.remove(state.trim());
        if (entry == null) {
            throw new BusinessException(401, "state 无效或已被使用,请重新从入口登录");
        }
        if (entry.expiresAt() < System.currentTimeMillis()) {
            throw new BusinessException(401, "登录请求已超时(超过有效期),请重新从入口登录");
        }
        String from = entry.from() == null || entry.from().isEmpty() ? null : entry.from();
        return new Payload(from, entry.dimensionId());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    /** 待验证登录请求 */
    private record Entry(String from, Long dimensionId, long expiresAt) {
    }

    /**
     * 校验通过的 state 内容
     *
     * @param from        回跳地址(已归一,空串转 null)
     * @param dimensionId 发起登录时的维度 ID;发起时未指定则为 null
     */
    public record Payload(String from, Long dimensionId) {
    }
}
