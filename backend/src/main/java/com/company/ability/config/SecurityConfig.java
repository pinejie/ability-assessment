package com.company.ability.config;

import com.company.ability.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 * assess.auth.enforced=true（默认）：除 SSO 引导接口与接口文档外，全部要求携带有效 JWT；
 * assess.auth.enforced=false：回退为全放行（仅限开发调试，禁止公网）。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${assess.auth.enforced:true}")
    private boolean authEnforced;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（无状态 JWT + OAuth state 校验已覆盖跨站风险）
            .csrf(AbstractHttpConfigurer::disable)
            // 跨域走 CorsFilter（CorsConfig）
            .cors(Customizer.withDefaults())
            // 禁用 Session
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 配置请求授权
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                    // CORS 预检
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // SSO 引导接口（发起跳转 / 回调换 token）
                    .requestMatchers("/api/v1/auth/sso/**").permitAll()
                    // Agent 交付稿/三出口（内网 cp_server 调用，无 JWT；服务内强校验 X-Agent-Token）
                    .requestMatchers(HttpMethod.POST, "/api/v1/agent/draft", "/api/v1/agent/outcome").permitAll()
                    // 接口文档
                    .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**",
                            "/swagger-ui/**", "/swagger-ui.html", "/favicon.ico").permitAll();
                if (authEnforced) {
                    auth.anyRequest().authenticated();
                } else {
                    auth.anyRequest().permitAll();
                }
            })
            // 未认证/无权限返回统一 JSON（与前端 Result 契约一致）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                        writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已失效"))
                .accessDeniedHandler((request, response, e) ->
                        writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "无权访问"))
            )
            // JWT 解析过滤器
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
