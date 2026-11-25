package com.company.ability.security.provider;

import com.company.ability.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器注册表单测：按类型查找、重复类型、未实现类型
 */
class IdentityProviderRegistryTest {

    /** 最小实现，只为占一个类型名 */
    private static IdentityProvider stub(String type) {
        return new IdentityProvider() {
            @Override public String providerType() { return type; }
            @Override public String buildAuthorizeUrl(ProviderConfig c, String s) { return ""; }
            @Override public String exchange(ProviderConfig c, String t) { return ""; }
        };
    }

    @Test
    @DisplayName("按类型取到对应实现")
    void getByType() {
        IdentityProvider weaver = stub("weaver");
        IdentityProviderRegistry reg = new IdentityProviderRegistry(List.of(weaver), "");

        assertSame(weaver, reg.get("weaver"));
        assertEquals(List.of("weaver"), List.copyOf(reg.supportedTypes()));
    }

    @Test
    @DisplayName("类型名前后空格被忽略")
    void trimsType() {
        IdentityProvider weaver = stub("weaver");
        IdentityProviderRegistry reg = new IdentityProviderRegistry(List.of(weaver), "");

        assertSame(weaver, reg.get("  weaver  "));
    }

    @Test
    @DisplayName("未知类型：明确报错并列出已注册的类型")
    void unknownTypeFails() {
        IdentityProviderRegistry reg = new IdentityProviderRegistry(List.of(stub("weaver")), "");

        BusinessException e = assertThrows(BusinessException.class, () -> reg.get("saml"));
        assertTrue(e.getMessage().contains("saml"), e.getMessage());
        assertTrue(e.getMessage().contains("weaver"), "应列出已支持类型: " + e.getMessage());
    }

    @Test
    @DisplayName("null 类型也走同一个报错，不抛 NPE")
    void nullTypeFails() {
        IdentityProviderRegistry reg = new IdentityProviderRegistry(List.of(stub("weaver")), "");

        assertThrows(BusinessException.class, () -> reg.get(null));
    }

    @Test
    @DisplayName("重复类型名：启动即失败（否则后注册的会静默覆盖前一个）")
    void duplicateTypeFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new IdentityProviderRegistry(List.of(stub("weaver"), stub("weaver")), ""));

        assertTrue(e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    @DisplayName("一个实现都没有：启动即失败")
    void emptyFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new IdentityProviderRegistry(List.of(), ""));
    }

    @Test
    @DisplayName("配置声明要用的类型没实现：启动即失败，指出缺哪个")
    void declaredButMissingFails() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new IdentityProviderRegistry(List.of(stub("weaver")), "weaver,oidc"));

        assertTrue(e.getMessage().contains("oidc"), e.getMessage());
    }

    @Test
    @DisplayName("配置声明的类型全部有实现：正常启动")
    void declaredAndImplementedOk() {
        IdentityProviderRegistry reg =
                new IdentityProviderRegistry(List.of(stub("weaver"), stub("oidc")), "weaver, oidc");

        assertEquals(2, reg.supportedTypes().size());
    }
}
