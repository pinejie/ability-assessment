package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 协议适配器注册表：按 {@code providerType} 找到对应实现。
 *
 * <p><b>Spring 自动收集</b>：构造函数注入 {@code List<IdentityProvider>}，
 * 新增实现类只要标 {@code @Component} 就自动进表，不需要改这个类。</p>
 *
 * <p><b>启动即校验</b>：类型名重复、或配置里出现未注册的类型，都在启动时失败。
 * 这是与「存实现类名」相比最实际的好处 —— 类名写错的话，启动不报错，
 * 要等用户点登录才崩，而且错误信息是 NoClassDefFoundError，看不出是哪条配置的问题。</p>
 */
@Slf4j
@Component
public class IdentityProviderRegistry {

    private final Map<String, IdentityProvider> providers = new HashMap<>();

    /**
     * @param found  Spring 收集到的全部实现
     * @param active 配置里实际用到的类型（逗号分隔，可空）。
     *               提供了一个「配了但没实现」的早期发现手段：
     *               数据库里写了 oidc 但当时代码还没实现，启动就告诉他，而不是等用户点登录。
     */
    public IdentityProviderRegistry(List<IdentityProvider> found,
                                    @Value("${integration.provider-types-in-use:}") String active) {
        for (IdentityProvider p : found) {
            IdentityProvider prev = providers.put(p.providerType(), p);
            if (prev != null) {
                throw new IllegalStateException("IdentityProvider 类型重复：" + p.providerType()
                        + "（" + prev.getClass().getSimpleName() + " 与 " + p.getClass().getSimpleName() + "）");
            }
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException("没有找到任何 IdentityProvider 实现，登录无法工作");
        }
        verifyActiveTypes(active);
        log.info("IdentityProvider 注册完成：{}", new TreeSet<>(providers.keySet()));
    }

    /**
     * 按类型取实现
     *
     * @throws BusinessException 类型未注册（配置写错了）
     */
    public IdentityProvider get(String providerType) {
        IdentityProvider p = providerType == null ? null : providers.get(providerType.trim());
        if (p == null) {
            throw new BusinessException(500, "不支持的单点登录类型：" + providerType
                    + "，已注册：" + new TreeSet<>(providers.keySet()));
        }
        return p;
    }

    /** 已注册的类型，供配置页下拉框使用 */
    public Set<String> supportedTypes() {
        return new TreeSet<>(providers.keySet());
    }

    private void verifyActiveTypes(String active) {
        if (active == null || active.isBlank()) {
            return;
        }
        Set<String> declared = new TreeSet<>();
        for (String t : active.split(",")) {
            String type = t.trim();
            if (!type.isEmpty()) {
                declared.add(type);
            }
        }
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(providers.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("配置声明要用的登录类型没有实现：" + missing
                    + "，已注册：" + new TreeSet<>(providers.keySet()));
        }
    }
}
