package com.company.ability.security;

import com.company.ability.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAuth2 state 签发/校验单测(短随机凭证 + 服务端一次性消费)
 */
class SsoStateServiceTest {

    private final SsoStateService service = new SsoStateService(300);

    @Test
    @DisplayName("签发的 state 可还原回跳地址,且为 16 位十六进制短串")
    void makeThenVerify() {
        String state = service.make("/assess-sheets?tab=1");

        assertTrue(state.matches("[0-9a-f]{16}"), "state 应为 16 位十六进制: " + state);
        assertEquals("/assess-sheets?tab=1", service.verify(state));
    }

    @Test
    @DisplayName("from 为空时 verify 返回 null")
    void emptyFromReturnsNull() {
        String state = service.make(null);

        assertNull(service.verify(state));
    }

    @Test
    @DisplayName("state 一次性:重复使用被拒绝")
    void stateIsOneTimeUse() {
        String state = service.make("/");
        service.verify(state);

        assertThrows(BusinessException.class, () -> service.verify(state));
    }

    @Test
    @DisplayName("伪造 state 被拒绝")
    void forgedStateRejected() {
        assertThrows(BusinessException.class, () -> service.verify("deadbeefdeadbeef"));
    }

    @Test
    @DisplayName("过期的 state 被拒绝")
    void expiredStateRejected() {
        SsoStateService shortTtl = new SsoStateService(-1);
        String state = shortTtl.make("/");

        BusinessException e = assertThrows(BusinessException.class, () -> shortTtl.verify(state));
        assertEquals(401, e.getCode());
    }

    @Test
    @DisplayName("两次签发的 state 不同(随机性)")
    void statesAreUnique() {
        assertNotEquals(service.make("/a"), service.make("/a"));
    }

    @Test
    @DisplayName("空 state 被拒绝")
    void blankStateRejected() {
        assertThrows(BusinessException.class, () -> service.verify(null));
        assertThrows(BusinessException.class, () -> service.verify("  "));
    }
}
