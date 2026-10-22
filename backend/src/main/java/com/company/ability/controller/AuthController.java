package com.company.ability.controller;

import com.company.ability.client.OaSsoClient;
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
 * 认证控制器：泛微 OAuth2 单点登录（发起 / 回调 / 当前用户 / 登出）
 */
@Slf4j
@Tag(name = "认证", description = "泛微 OAuth2 单点登录")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SsoLoginService ssoLoginService;
    private final OaSsoClient oaSsoClient;
    private final AdminService adminService;

    @Operation(summary = "发起泛微单点登录（302 跳转认证中心 authorize）")
    @GetMapping("/sso/login")
    public void login(@RequestParam(required = false) String from,
                      HttpServletResponse response) throws IOException {
        response.sendRedirect(ssoLoginService.buildLoginRedirectUrl(from));
    }

    @Operation(summary = "SSO 回调换登录态（前端回调页提交 ticket/state，返回本系统 JWT）")
    @PostMapping("/sso/callback")
    public Result<SsoLoginResultVO> callback(@Valid @RequestBody SsoCallbackDTO dto) {
        SsoLoginService.SsoLoginResult result = ssoLoginService.authenticate(dto.getTicket(), dto.getState());
        CurrentUserVO user = CurrentUserVO.from(result.user());
        user.setAdmin(adminService.isAdmin(result.user().loginid()));
        SsoLoginResultVO vo = new SsoLoginResultVO(result.token(), user, result.redirect());
        return Result.success("登录成功", vo);
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
        return Result.success(vo);
    }

    @Operation(summary = "登出（返回泛微统一注销地址，前端清理本地 token 后跳转）")
    @PostMapping("/logout")
    public Result<Map<String, String>> logout() {
        return Result.success(Map.of("logoutUrl", oaSsoClient.buildLogoutUrl()));
    }
}
