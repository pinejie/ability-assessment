package com.company.ability.service.impl;

import com.company.ability.client.OaSsoClient;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.JwtTokenProvider;
import com.company.ability.security.LoginUser;
import com.company.ability.security.SsoStateService;
import com.company.ability.service.SsoLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 泛微 OAuth2 单点登录实现。
 * 身份锚定：OAuth profile 返回的 id（OA 登录名）→ HrmResource.loginid，
 * 要求在职（status=1，与《Ecology 账号密码校验服务》口径一致）。
 * 不建本地账号表——组织与账号权威在泛微，本系统只读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoLoginServiceImpl implements SsoLoginService {

    private final OaSsoClient oaSsoClient;
    private final SsoStateService stateService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String buildLoginRedirectUrl(String from) {
        return oaSsoClient.buildAuthorizeUrl(stateService.make(from));
    }

    @Override
    public SsoLoginResult authenticate(String ticket, String state) {
        // 1. 校验 state（防 CSRF / 越权回跳），并取回原始回跳地址。
        //    state 可缺省：泛微文档 §1.1 声明 state 非必须，门户菜单直链 authorize 时无本端
        //    发起动作，不存在 CSRF 会话绑定问题；携带时仍严格校验签名与时效。
        String from = (state == null || state.isBlank())
                ? null
                : stateService.verify(state);

        // 2. ticket（临时授权码，60 秒一次有效）→ access_token
        String accessToken = oaSsoClient.getAccessToken(ticket);

        // 3. access_token → OA 登录名
        String loginid = oaSsoClient.getProfileLoginid(accessToken);

        // 4. OA 登录名 → 在职人员（HrmResource），签发本系统 JWT
        LoginUser loginUser = loadActiveResource(loginid);
        String token = jwtTokenProvider.generate(loginUser);

        log.info("SSO 登录成功: loginid={} resourceId={} name={}",
                loginUser.loginid(), loginUser.resourceId(), loginUser.name());
        return new SsoLoginResult(token, loginUser, sanitizeRedirect(from));
    }

    /**
     * 按 OA 登录名查询在职人员（status=1）；不存在或已离职则拒绝登录
     */
    private LoginUser loadActiveResource(String loginid) {
        String sql = "SELECT id, loginid, lastname, departmentid, subcompanyid1 " +
                     "FROM HrmResource WHERE loginid = ? AND status = 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, loginid);
        if (rows.isEmpty()) {
            log.warn("SSO 登录被拒：OA 登录名 [{}] 在本系统人员表中不存在或非在职", loginid);
            throw new BusinessException(401, "账号 " + loginid + " 在泛微中不存在或已离职，请联系管理员");
        }
        Map<String, Object> row = rows.get(0);
        String lastname = (String) row.get("lastname");
        return new LoginUser(
                (String) row.get("loginid"),
                ((Number) row.get("id")).longValue(),
                lastname == null ? loginid : lastname.trim(),
                row.get("departmentid") == null ? null : ((Number) row.get("departmentid")).longValue(),
                row.get("subcompanyid1") == null ? null : ((Number) row.get("subcompanyid1")).longValue()
        );
    }

    /**
     * 回跳地址白名单校验：仅允许站内相对路径，防开放重定向
     */
    private String sanitizeRedirect(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        if (from.startsWith("/") && !from.startsWith("//")) {
            return from;
        }
        return null;
    }
}
