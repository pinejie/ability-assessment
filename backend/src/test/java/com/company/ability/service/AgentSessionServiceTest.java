package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话运行时守卫测试：属主隔离（review P0#2 会话侧）。
 * 不派生真实 claude 进程，仅验证鉴权与生命周期判定。
 */
class AgentSessionServiceTest {

    @TempDir
    Path tmp;

    private AgentSessionService service;

    @BeforeEach
    void setUp() {
        service = new AgentSessionService(new ObjectMapper(), tmp.toString(),
                "/bin/true", 30, 2, 6);
        asUser("alice", 11L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void asUser(String loginid, long resourceId) {
        LoginUser user = new LoginUser(loginid, resourceId, loginid, 1L, 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    @DisplayName("他人 sessionId：history/stop/messages 一律 403")
    void otherUsersSessionRejected() {
        String sid = (String) service.createSession().get("sessionId");
        asUser("bob", 22L);
        assertThatThrownBy(() -> service.history(sid))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.stopSession(sid))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.sendMessage(sid, "hi", List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权");
    }

    @Test
    @DisplayName("属主本人：可创建并读取历史（空）")
    void ownerCanAccess() {
        String sid = (String) service.createSession().get("sessionId");
        assertThat(service.history(sid)).isEmpty();
        Map<String, Object> again = service.createSession();
        assertThat(again.get("sessionId")).isNotEqualTo(sid);
    }
}
