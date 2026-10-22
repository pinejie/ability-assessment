package com.company.ability.config;

import com.company.ability.context.DimensionContext;
import com.company.ability.security.LoginUser;
import com.company.ability.service.AdminService;
import com.company.ability.service.AssessDimensionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 维度请求头越权校验：已登录用户对无权维度必须 403，其余场景维持原宽松行为。
 */
class DimensionContextFilterTest {

    private static final LoginUser USER = new LoginUser("tester", 100L, "测试员", 1L, 2L);

    private AssessDimensionService dimensionService;
    private AdminService adminService;
    private DimensionContextFilter filter;
    private MockHttpServletResponse response;
    private final AtomicBoolean chainCalled = new AtomicBoolean();
    private final AtomicReference<Long> contextDuringChain = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dimensionService = mock(AssessDimensionService.class);
        adminService = mock(AdminService.class);
        filter = new DimensionContextFilter(dimensionService, adminService);
        response = new MockHttpServletResponse();
        chainCalled.set(false);
        contextDuringChain.set(null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private FilterChain recordingChain() {
        return (req, res) -> {
            chainCalled.set(true);
            contextDuringChain.set(DimensionContext.get());
        };
    }

    private MockHttpServletRequest requestWithDimension(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(DimensionContextFilter.HEADER_DIMENSION_ID, headerValue);
        }
        return request;
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER, null, List.of()));
    }

    @Test
    @DisplayName("未登录：维度头写入上下文并放行（钩子等内部调用的宽松模式）")
    void anonymousPassesThrough() throws Exception {
        filter.doFilterInternal(requestWithDimension("2"), response, recordingChain());

        assertTrue(chainCalled.get());
        assertEquals(2L, contextDuringChain.get());
        verifyNoInteractions(dimensionService);
    }

    @Test
    @DisplayName("已登录且有权限：放行")
    void authorizedUserPassesThrough() throws Exception {
        login();
        when(dimensionService.isAuthorizedForUser(2L, 100L)).thenReturn(true);

        filter.doFilterInternal(requestWithDimension("2"), response, recordingChain());

        assertTrue(chainCalled.get());
        assertEquals(2L, contextDuringChain.get());
    }

    @Test
    @DisplayName("已登录但无权限：403，不进入业务链")
    void forbiddenWhenNotAuthorized() throws Exception {
        login();
        when(dimensionService.isAuthorizedForUser(3L, 100L)).thenReturn(false);

        filter.doFilterInternal(requestWithDimension("3"), response, recordingChain());

        assertFalse(chainCalled.get());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("403"));
    }

    @Test
    @DisplayName("平台路径豁免：/authorized、/auth/me、/assess-admins 带维度头也不校验、不写上下文（防零租户死循环）")
    void platformPathsSkipCheck() throws Exception {
        login();
        for (String uri : List.of("/api/v1/assess-dimensions/authorized",
                "/api/v1/auth/me", "/api/v1/assess-admins")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            request.addHeader(DimensionContextFilter.HEADER_DIMENSION_ID, "1");

            filter.doFilterInternal(request, response, recordingChain());

            assertTrue(chainCalled.get(), uri + " 应放行");
            assertNull(contextDuringChain.get(), uri + " 不应写维度上下文");
            chainCalled.set(false);
        }
        verifyNoInteractions(dimensionService, adminService);
    }

    @Test
    @DisplayName("业务路径不豁免：零授权用户对兜底维度 1 的业务请求仍 403")
    void businessPathStillChecked() throws Exception {
        login();
        when(adminService.isAdmin("tester")).thenReturn(false);
        when(dimensionService.isAuthorizedForUser(1L, 100L)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/assess-sheets");
        request.addHeader(DimensionContextFilter.HEADER_DIMENSION_ID, "1");
        filter.doFilterInternal(request, response, recordingChain());

        assertFalse(chainCalled.get());
        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("已登录管理员：豁免授权表校验，任意维度放行")
    void adminBypassesAuthorizationCheck() throws Exception {
        login();
        when(adminService.isAdmin("tester")).thenReturn(true);

        filter.doFilterInternal(requestWithDimension("9"), response, recordingChain());

        assertTrue(chainCalled.get());
        assertEquals(9L, contextDuringChain.get());
        verifyNoInteractions(dimensionService);
    }

    @Test
    @DisplayName("已登录但未带维度头：宽松放行，不触发授权查询")
    void withoutHeaderStaysLenient() throws Exception {
        login();

        filter.doFilterInternal(requestWithDimension(null), response, recordingChain());

        assertTrue(chainCalled.get());
        assertNull(contextDuringChain.get());
        verifyNoInteractions(dimensionService);
    }

    @Test
    @DisplayName("非法/非正数维度头：按未指定处理")
    void invalidHeaderTreatedAsUnset() throws Exception {
        login();

        filter.doFilterInternal(requestWithDimension("abc"), response, recordingChain());
        assertTrue(chainCalled.get());
        assertNull(contextDuringChain.get());

        MockHttpServletRequest zero = requestWithDimension("-1");
        filter.doFilterInternal(zero, response, recordingChain());
        assertNull(contextDuringChain.get());
        verifyNoInteractions(dimensionService);
    }

    @Test
    @DisplayName("请求结束后上下文必须清理（线程复用防串维度）")
    void contextClearedAfterRequest() throws Exception {
        filter.doFilterInternal(requestWithDimension("5"), response, recordingChain());

        assertNull(DimensionContext.get());
    }

    @Test
    @DisplayName("授权查询以 JWT 内 resourceId 为准，不信任请求中的其他身份参数")
    void checkUsesJwtIdentity() throws Exception {
        login();
        when(dimensionService.isAuthorizedForUser(anyLong(), anyLong())).thenReturn(true);

        filter.doFilterInternal(requestWithDimension("7"), response, recordingChain());

        org.mockito.Mockito.verify(dimensionService).isAuthorizedForUser(7L, 100L);
    }
}
