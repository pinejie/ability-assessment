package com.company.ability.service;

import com.company.ability.dto.AssessAdminCreateDTO;
import com.company.ability.entity.AssessAdmin;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessAdminMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.vo.AssessAdminVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统管理员服务测试。
 * 配置超管取自 application-test.yml：assess.bootstrap-admins=boot-admin1,boot-admin2
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("系统管理员服务测试")
class AdminServiceTest {

    @Autowired
    private AdminService adminService;

    @Autowired
    private AssessAdminMapper adminMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 身份判定 ====================

    @Test
    @DisplayName("配置超管永远是管理员；未知人员与空登录名不是")
    void bootstrapAdminIsAdmin() {
        assertTrue(adminService.isAdmin("boot-admin1"));
        assertTrue(adminService.isAdmin("boot-admin2"));
        assertFalse(adminService.isAdmin("nobody"));
        assertFalse(adminService.isAdmin(null));
        assertFalse(adminService.isAdmin("  "));
    }

    @Test
    @DisplayName("表记录授予管理员身份")
    void tableRowGrantsAdmin() {
        assertFalse(adminService.isAdmin("table-admin"));
        insertRow("table-admin");
        assertTrue(adminService.isAdmin("table-admin"));
    }

    @Test
    @DisplayName("requireAdmin：未登录 401，非管理员 403，管理员放行")
    void requireAdminGuards() {
        BusinessException anon = assertThrows(BusinessException.class, adminService::requireAdmin);
        assertEquals(401, anon.getCode());

        loginAs("guest", 500L);
        BusinessException denied = assertThrows(BusinessException.class, adminService::requireAdmin);
        assertEquals(403, denied.getCode());

        loginAs("boot-admin1", 1L);
        adminService.requireAdmin();
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("按在职泛微人员新增管理员：loginid/姓名取自服务端查询")
    void addFromActiveResource() {
        seedResource(901L, "zs901", 1);
        AssessAdminCreateDTO dto = new AssessAdminCreateDTO();
        dto.setResourceId(901L);
        dto.setRemark("测试添加");

        Long id = adminService.add(dto);

        assertNotNull(id);
        AssessAdmin row = adminMapper.selectById(id);
        assertEquals("zs901", row.getLoginid());
        assertEquals("张三901", row.getLastname());
        assertEquals("测试添加", row.getRemark());
    }

    @Test
    @DisplayName("新增拒绝：重复人员、配置超管、非在职、人员不存在、无登录名")
    void addRejectsInvalidCandidates() {
        seedResource(902L, "dup", 1);
        insertRow("dup");
        assertThrows(BusinessException.class, () -> adminService.add(createFor(902L)));

        seedResource(903L, "boot-admin2", 1);
        assertThrows(BusinessException.class, () -> adminService.add(createFor(903L)));

        seedResource(904L, "left", 0);
        assertThrows(BusinessException.class, () -> adminService.add(createFor(904L)));

        assertThrows(BusinessException.class, () -> adminService.add(createFor(999L)));

        seedResource(905L, null, 1);
        assertThrows(BusinessException.class, () -> adminService.add(createFor(905L)));
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除拒绝：配置超管（即便落了表）与本人")
    void deleteRejectsBootstrapAndSelf() {
        AssessAdmin bootRow = insertRow("boot-admin1");
        BusinessException notDeletable = assertThrows(BusinessException.class,
                () -> adminService.delete(bootRow.getId()));
        assertTrue(notDeletable.getMessage().contains("配置超管"));

        loginAs("self-admin", 11L);
        AssessAdmin selfRow = insertRow("self-admin");
        BusinessException selfDenied = assertThrows(BusinessException.class,
                () -> adminService.delete(selfRow.getId()));
        assertTrue(selfDenied.getMessage().contains("不能删除自己"));
    }

    @Test
    @DisplayName("管理员删除他人：物理删除生效")
    void deleteOtherSucceeds() {
        loginAs("boot-admin1", 1L);
        AssessAdmin row = insertRow("other-admin");

        adminService.delete(row.getId());

        assertNull(adminMapper.selectById(row.getId()));
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表合并：表记录 + 未落表的配置超管合成条目（无 id、标记 bootstrap）")
    void listMergesTableAndConfig() {
        insertRow("table-admin");

        List<AssessAdminVO> list = adminService.list();

        assertEquals(3, list.size());
        AssessAdminVO tableRow = list.stream()
                .filter(v -> "table-admin".equals(v.getLoginid())).findFirst().orElseThrow();
        assertFalse(tableRow.getBootstrap());
        assertNotNull(tableRow.getId());

        AssessAdminVO configOnly = list.stream()
                .filter(v -> "boot-admin1".equals(v.getLoginid())).findFirst().orElseThrow();
        assertTrue(configOnly.getBootstrap());
        assertNull(configOnly.getId());
    }

    // ==================== 辅助 ====================

    private void loginAs(String loginid, Long resourceId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(loginid, resourceId, loginid, null, null), null, List.of()));
    }

    private void seedResource(Long id, String loginid, int status) {
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid) VALUES (?, ?, ?, ?)",
                id, "张三" + id, status, loginid);
    }

    private AssessAdminCreateDTO createFor(Long resourceId) {
        AssessAdminCreateDTO dto = new AssessAdminCreateDTO();
        dto.setResourceId(resourceId);
        return dto;
    }

    private AssessAdmin insertRow(String loginid) {
        AssessAdmin admin = new AssessAdmin();
        admin.setLoginid(loginid);
        admin.setLastname("测试" + loginid);
        adminMapper.insert(admin);
        return admin;
    }
}
