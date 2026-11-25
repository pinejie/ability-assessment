package com.company.ability.controller;

import com.company.ability.dto.Result;
import com.company.ability.dto.SsoCallbackDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AdminService;
import com.company.ability.service.SsoLoginService;
import com.company.ability.vo.CurrentUserVO;
import com.company.ability.vo.SsoLoginResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 认证控制器：单点登录（发起 / 回调 / 当前用户 / 登出）。
 *
 * <p>登录走哪个维度由调用方指明，缺省用默认维度 —— 泛微门户里已经挂出去的旧链接
 * 不带维度号，必须还能用。维度号只用来找配置，登录完成后不再参与判断。</p>
 */
@Slf4j
@Tag(name = "认证", description = "单点登录（按维度配置选协议）")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SsoLoginService ssoLoginService;
    private final AdminService adminService;

    @Operation(summary = "发起单点登录（302 跳转该维度配置的认证中心）")
    @GetMapping("/sso/login")
    public void login(@RequestParam(required = false) Long p,
                      @RequestParam(required = false) String from,
                      HttpServletResponse response) throws IOException {
        response.sendRedirect(ssoLoginService.buildLoginRedirectUrl(p, from));
    }

    @Operation(summary = "SSO 回调换登录态（前端回调页提交 ticket/state，返回本系统 JWT）")
    @PostMapping("/sso/callback")
    public Result<SsoLoginResultVO> callback(@Valid @RequestBody SsoCallbackDTO dto) {
        SsoLoginService.SsoLoginResult result =
                ssoLoginService.authenticate(dto.getDimensionId(), dto.getTicket(), dto.getState());
        CurrentUserVO user = CurrentUserVO.from(result.user());
        user.setAdmin(adminService.isAdmin(result.user().loginid()));
        user.setSuperAdmin(adminService.isSuperAdmin(result.user().loginid()));
        SsoLoginResultVO vo = new SsoLoginResultVO(result.token(), user, result.redirect());
        return Result.success("登录成功", vo);
    }

    @Operation(summary = "该维度回调 URL 里票据的参数名（前端回调页据此取票据）")
    @GetMapping("/sso/callback-param")
    public Result<Map<String, String>> callbackParam(@RequestParam(required = false) Long p) {
        // 回调页此时还没有登录态，所以这个接口在 SecurityConfig 的 /sso/** 白名单里，
        // 且只回一个参数名 —— 不回 client_id、地址或任何配置内容。
        // 取不到（维度没配/不存在）时返回空，前端按 ticket / code 依次兜底。
        String param = ssoLoginService.callbackParamOf(p);
        return Result.success(param == null ? Map.of() : Map.of("param", param));
    }

    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null) {
            throw new BusinessException(401, "未登录");
        }
        CurrentUserVO vo = CurrentUserVO.from(user);
        vo.setAdmin(adminService.isAdmin(user.loginid()));
        vo.setSuperAdmin(adminService.isSuperAdmin(user.loginid()));
        return Result.success(vo);
    }

    @Operation(summary = "登出（返回该维度认证中心的统一注销地址，前端清理本地 token 后跳转）")
    @PostMapping("/logout")
    public Result<Map<String, String>> logout(@RequestParam(required = false) Long p) {
        // 未指定维度时用默认维度；统一注销地址可能为空（协议不支持），前端按空处理
        String logoutUrl = ssoLoginService.buildLogoutUrl(p);
        return Result.success(logoutUrl == null ? Map.of() : Map.of("logoutUrl", logoutUrl));
    }
}
