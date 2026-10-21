package com.company.ability.config;

import com.company.ability.context.DimensionContext;
import com.company.ability.security.UserContext;
import com.company.ability.service.AssessDimensionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析请求头 X-Dimension-Id 写入维度上下文，请求结束后清理。
 * 头缺失或非法时按未指定维度处理（宽松模式，见 DimensionContext 注释）。
 *
 * 越权防护：X-Dimension-Id 由客户端自带，不可作为授权依据。已登录（JWT）且指定维度时，
 * 按维度授权表校验，无权直接 403，不再进入业务层。
 * 无登录身份（泛微钩子等内部集成调用）维持原宽松行为，与 DimensionContext 注释口径一致。
 * 注意依赖过滤器顺序：本过滤器在 Security 链之后执行，SecurityContext 此时已填充。
 */
@Component
@RequiredArgsConstructor
public class DimensionContextFilter extends OncePerRequestFilter {

    public static final String HEADER_DIMENSION_ID = "X-Dimension-Id";

    private final AssessDimensionService assessDimensionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Long dimensionId = parseDimensionId(request.getHeader(HEADER_DIMENSION_ID));
            if (dimensionId != null) {
                DimensionContext.set(dimensionId);
                Long resourceId = UserContext.currentResourceIdOrNull();
                if (resourceId != null && !assessDimensionService.isAuthorizedForUser(dimensionId, resourceId)) {
                    writeForbidden(response);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            DimensionContext.clear();
        }
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
        response.getWriter().write("{\"code\":403,\"message\":\"无权访问该维度，请在顶栏切换后重试\"}");
    }
}
