package com.company.ability.config;

import com.company.ability.context.DimensionContext;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AdminService;
import com.company.ability.service.AssessDimensionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析请求头 X-Dimension-Id 写入维度上下文，请求结束后清理。
 * 头缺失或非法时按未指定维度处理（宽松模式，见 DimensionContext 注释）。
 *
 * 越权防护：X-Dimension-Id 由客户端自带，不可作为授权依据。已登录（JWT）且指定维度时，
 * 按维度授权表校验，无权直接 403，不再进入业务层；系统管理员豁免（可查看任意租户数据）。
 * 无登录身份（泛微钩子等内部集成调用）维持原宽松行为，与 DimensionContext 注释口径一致。
 * 平台路径豁免见 EXEMPT_URI_PREFIXES 注释。
 * 注意依赖过滤器顺序：本过滤器在 Security 链之后执行，SecurityContext 此时已填充。
 */
@Component
@RequiredArgsConstructor
public class DimensionContextFilter extends OncePerRequestFilter {

    public static final String HEADER_DIMENSION_ID = "X-Dimension-Id";

    /**
     * 平台级路径：登录态、租户目录（authorized 返回的就是"该用户能看哪些租户"）、管理员名单——
     * 均不读取租户上下文，请求头里的 X-Dimension-Id 只是前端全局拦截器的副产品。
     * 若不豁免，零租户普通用户对 /authorized 自身也会 403（兜底头维度 1 未授权），
     * 前端重试与提示将进入死循环（2026-10-22 事故复盘）。业务数据接口不在此列，越权照拦。
     */
    private static final List<String> EXEMPT_URI_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/assess-dimensions",
            "/api/v1/assess-admins");

    private final AssessDimensionService assessDimensionService;
    private final AdminService adminService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Long dimensionId = parseDimensionId(request.getHeader(HEADER_DIMENSION_ID));
            if (dimensionId != null && !isExempt(request)) {
                DimensionContext.set(dimensionId);
                LoginUser user = UserContext.currentOrNull();
                if (user != null && user.resourceId() != null
                        && !adminService.isAdmin(user.loginid())
                        && !assessDimensionService.isAuthorizedForUser(dimensionId, user.resourceId())) {
                    writeForbidden(response);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            DimensionContext.clear();
        }
    }

    private boolean isExempt(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        return EXEMPT_URI_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    private Long parseDimensionId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(header.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null; // 非法头按未指定处理
        }
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"没有该租户的访问权限，请在顶栏切换或联系管理员配置授权\"}");
    }
}
