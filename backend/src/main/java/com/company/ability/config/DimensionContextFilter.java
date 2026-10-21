package com.company.ability.config;

import com.company.ability.context.DimensionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析请求头 X-Dimension-Id 写入维度上下文，请求结束后清理。
 * 头缺失或非法时按未指定维度处理（宽松模式，见 DimensionContext 注释）。
 */
@Component
public class DimensionContextFilter extends OncePerRequestFilter {

    public static final String HEADER_DIMENSION_ID = "X-Dimension-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader(HEADER_DIMENSION_ID);
            if (header != null && !header.isBlank()) {
                try {
                    long dimensionId = Long.parseLong(header.trim());
                    if (dimensionId > 0) {
                        DimensionContext.set(dimensionId);
                    }
                } catch (NumberFormatException ignored) {
                    // 非法头按未指定处理
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            DimensionContext.clear();
        }
    }
}
