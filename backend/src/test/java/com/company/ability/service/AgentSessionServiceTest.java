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

    @Test
    @DisplayName("isLiveSession：新建会话为活；未知/空 id 为死（交付投毒防线）")
    void liveSessionRegistry() {
        String sid = (String) service.createSession().get("sessionId");
        assertThat(service.isLiveSession(sid)).isTrue();
        assertThat(service.isLiveSession("no-such-session")).isFalse();
        assertThat(service.isLiveSession(null)).isFalse();
    }

    @Test
    @DisplayName("会话回收（闲置超窗）→ 上传目录连带删除，防弃用文件堆积")
    void reapDeletesSessionUploadsDir() throws Exception {
        // idleMinutes=0：任何会话一经巡检即过期回收
        AgentSessionService expiring = new AgentSessionService(new ObjectMapper(),
                tmp.resolve("cp2").toString(), "/bin/true", 0, 2, 6);
        asUser("alice", 11L);
        String sid = (String) expiring.createSession().get("sessionId");
        java.nio.file.Path dir = tmp.resolve("cp2/uploads").resolve(sid);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("传错的文件.xlsx"), "junk");

        assertThat(expiring.isLiveSession(sid)).isFalse();   // 触发回收
        assertThat(java.nio.file.Files.exists(dir)).as("回收后上传目录应被删除").isFalse();
    }

    @Test
    @DisplayName("巡检：重启后失联的会话目录（过回收窗口）被清理；在册会话目录不动")
    void housekeepingCleansOrphanUploadDirs() throws Exception {
        String sid = (String) service.createSession().get("sessionId");
        java.nio.file.Path liveDir = tmp.resolve("uploads").resolve(sid);
        java.nio.file.Files.createDirectories(liveDir);
        java.nio.file.Files.writeString(liveDir.resolve("f.xlsx"), "x");

        java.nio.file.Path orphan = tmp.resolve("uploads").resolve("gone-session");
        java.nio.file.Files.createDirectories(orphan);
        java.nio.file.Path of = orphan.resolve("old.xlsx");
        java.nio.file.Files.writeString(of, "y");
        var past = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3L * 3600 * 1000);
        java.nio.file.Files.setLastModifiedTime(of, past);
        java.nio.file.Files.setLastModifiedTime(orphan, past);

        service.housekeeping();

        assertThat(java.nio.file.Files.exists(orphan)).as("失联且过窗的目录应被清理").isFalse();
        assertThat(java.nio.file.Files.exists(liveDir.resolve("f.xlsx"))).as("在册会话目录不受影响").isTrue();
    }

    @Test
    @DisplayName("deleteUploads：非法/穿越 sessionId 一律拒绝，不碰 uploads 根")
    void deleteUploadsPathGuard() throws Exception {
        java.nio.file.Path keep = tmp.resolve("uploads");
        java.nio.file.Files.createDirectories(keep);
        java.nio.file.Files.writeString(keep.resolve("other-session.xlsx"), "data");

        service.deleteUploads("../uploads");          // 穿越
        service.deleteUploads("a b");                 // 非法字符
        service.deleteUploads("");                    // 空
        service.deleteUploads(null);
        assertThat(java.nio.file.Files.exists(keep.resolve("other-session.xlsx"))).isTrue();
    }
}
