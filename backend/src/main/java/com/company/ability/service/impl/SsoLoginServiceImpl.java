package com.company.ability.service.impl;

import com.company.ability.exception.BusinessException;
import com.company.ability.security.JwtTokenProvider;
import com.company.ability.security.LoginUser;
import com.company.ability.security.SsoStateService;
import com.company.ability.security.provider.IdentityProvider;
import com.company.ability.security.provider.IdentityProviderRegistry;
import com.company.ability.security.provider.ProviderConfig;
import com.company.ability.service.DimensionIntegrationService;
import com.company.ability.service.SsoLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 单点登录实现：<b>按维度配置决定用哪套协议，认人仍然锚定泛微人员表。</b>
 *
 * <p><b>改造前后差异</b>：原来直接调 {@code OaSsoClient} 并用 loginid 查人；
 * 现在多两步「按维度取配置」「按配置选 Provider」，其余完全一致。</p>
 *
 * <p><b>身份锚定</b>：兑换出的身份值（登录名或工号，由配置的 {@code sso_match_field} 决定）
 * 去 {@code HrmResource} 匹配，要求在岗（status 0/1/2/3）。不建本地账号表 ——
 * 组织与账号权威在泛微，本系统只读。</p>
 *
 * <p><b>为什么匹配字段要配置而不是写死工号</b>：泛微返回的是 loginid，
 * 而实测 164 人的 loginid 与工号不同。统一按工号匹配会让这些人登不进来。
 * 其它系统返回工号，则按工号匹配。两者必须能分开配。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoLoginServiceImpl implements SsoLoginService {

    /**
     * 放行登录的人员状态（业务口径：只要在职就可以）。
     * 0=试用期、1=正式、2=临时、3=试用延期 —— 四类都属「在岗在职」；
     * 4=已解聘、5=已离职、6=已退休、7=已失效 一律拒绝。
     *
     * 2026-11-13 由 status=1 放宽而来：原口径要求「正式在职」，把试用期 174 人、
     * 试用延期 61 人、临时 3 人挡在登录页外（其中 32 人有 OA 登录名，本可登录）。
     * 放宽后 {@link com.company.ability.service.RaterEligibilityService} 与
     * oa-job 的 PushMsgJob 必须保持同一判据，三处改动要一起上。
     */
    static final String ACTIVE_STATUSES = "0,1,2,3";

    private final DimensionIntegrationService integrationService;
    private final IdentityProviderRegistry providerRegistry;
    private final SsoStateService stateService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String buildLoginRedirectUrl(Long dimensionId, String from) {
        Long dimId = resolveDimension(dimensionId);
        ProviderConfig config = integrationService.loginConfig(dimId);
        IdentityProvider provider = providerRegistry.get(config.providerType());
        // 维度号随 state 走：回调时浏览器只带回 ticket+state，服务端靠它找回同一套配置
        return provider.buildAuthorizeUrl(config, stateService.make(from, dimId));
    }

    @Override
    public SsoLoginResult authenticate(Long dimensionId, String ticket, String state) {
        // 1. 校验 state（防 CSRF / 越权回跳），并取回原始回跳地址与发起时的维度号。
        //    state 可缺省：泛微文档 §1.1 声明 state 非必须，门户菜单直链 authorize 时无本端
        //    发起动作，不存在 CSRF 会话绑定问题；携带时仍严格校验签名与时效。
        SsoStateService.Payload payload = (state == null || state.isBlank())
                ? new SsoStateService.Payload(null, null)
                : stateService.consume(state);
        String from = payload.from();

        // 2. 维度号优先取 state 里记的（服务端签发，最可信），
        //    其次取调用方显式传的，都没有才落到默认维度 —— 门户旧链接两侧都为空。
        Long dimId = resolveDimension(
                payload.dimensionId() != null ? payload.dimensionId() : dimensionId);
        ProviderConfig config = integrationService.loginConfig(dimId);
        IdentityProvider provider = providerRegistry.get(config.providerType());
        String identity = provider.exchange(config, ticket);

        // 3. 按该维度配置的匹配字段查人（泛微 loginid / 其它系统 workcode）
        SsoLoginServiceImpl.IntegrationRowRef row = rowOf(dimId);
        LoginUser loginUser = loadActiveResource(identity, row.matchField(), row.providerType());
        String token = jwtTokenProvider.generate(loginUser);

        log.info("SSO 登录成功: dimension={} provider={} identity={} resourceId={} name={}",
                dimId, row.providerType(), identity, loginUser.resourceId(), loginUser.name());
        return new SsoLoginResult(token, loginUser, sanitizeRedirect(from));
    }

    @Override
    public String buildLogoutUrl(Long dimensionId) {
        Long dimId = resolveDimension(dimensionId);
        ProviderConfig config = integrationService.loginConfig(dimId);
        return providerRegistry.get(config.providerType()).buildLogoutUrl(config);
    }

    @Override
    public String callbackParamOf(Long dimensionId) {
        // 这里刻意吞掉异常：回调页在登录态建立之前调用，任何 IOException/配置缺失
        // 都不该让登录页白屏。取不到就返回 null，前端按 ticket / code 依次试。
        //
        // 不再有「留空取协议默认」这一层（2026-11-25）：参数名是配置页的必填项，
        // 空着说明这个维度没配好 —— 那时悄悄退回 code/ticket 猜一个，等于把
        // 「配置没填全」这件事掩盖成了一次偶发的登录失败。
        try {
            Long dimId = resolveDimension(dimensionId);
            ProviderConfig config = integrationService.loginConfig(dimId);
            String param = config.callbackParam();
            return param == null || param.isBlank() ? null : param.trim();
        } catch (RuntimeException e) {
            log.debug("取回调参数名失败（按前端兜底处理）dimension={}：{}", dimensionId, e.getMessage());
            return null;
        }
    }

    // ==================== 内部 ====================

    /** 未指定维度时取默认；配置缺失时给出明确的修复指引 */
    private Long resolveDimension(Long dimensionId) {
        return dimensionId != null ? dimensionId : integrationService.defaultDimensionId();
    }

    /** 该维度的协议类型与匹配字段（登录配置的可用性已由 loginConfig 校验过） */
    private IntegrationRowRef rowOf(Long dimensionId) {
        return new IntegrationRowRef(
                integrationService.loginConfig(dimensionId).providerType(),
                integrationService.matchFieldOf(dimensionId));
    }

    /**
     * 按身份值查在岗人员。
     *
     * <p>匹配列名来自配置，受 CHECK 约束限制只能是 loginid / workcode，
     * 所以这里拼接列名是安全的（不存在注入面）。</p>
     *
     * <p><b>JWT 主体永远非空</b>：按工号匹配进来的人可能没有 loginid
     * （在岗 2305 人里有 1531 人没有）。若拿空值当 JWT 主体，整个会话就失去了
     * 稳定标识。所以 {@code sessionId}（JWT subject）在 loginid 为空时用登录身份值兜底，
     * 而 {@code loginid} 保留真实值（可为 null）—— 管理员名单按登录名比对，
     * 拿工号去比对会误判放权。</p>
     *
     * <p><b>由此产生的已知限制</b>（第一步不改，第二步处理）：
     * 这类用户无法出现在 {@code bootstrap-admins} / {@code uf_assess_admin} 里 ——
     * 那两处按泛微 loginid 存，而他根本没有 loginid。要让他当管理员，
     * 需要把管理员判定也改成按 {@code resource_id}，那是独立的一项改动。</p>
     *
     * @throws BusinessException 查不到或已离职
     */
    private LoginUser loadActiveResource(String identity, String matchField, String providerType) {
        String column = "workcode".equals(matchField) ? "workcode" : "loginid";
        String sql = "SELECT id, loginid, workcode, lastname, departmentid, subcompanyid1 " +
                     "FROM HrmResource WHERE " + column + " = ? AND status IN (" + ACTIVE_STATUSES + ")";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, identity);
        if (rows.isEmpty()) {
            // 提示语要说明用的是哪个字段：泛微维度下说「工号不存在」会误导排查方向
            String what = "workcode".equals(column) ? "工号" : "登录名";
            log.warn("SSO 登录被拒：provider={} 返回的{} [{}] 在人员表中不存在或已离职",
                    providerType, what, identity);
            throw new BusinessException(401,
                    "账号 " + identity + "（" + what + "）在人员表中不存在或已离职，请联系管理员");
        }
        Map<String, Object> row = rows.get(0);
        String lastname = (String) row.get("lastname");
        String loginid = trim((String) row.get("loginid"));
        // 会话主体（JWT subject）：优先真实登录名，没有就用这次登录拿到的身份值（工号）
        String sessionId = loginid.isEmpty() ? identity : loginid;
        if (loginid.isEmpty()) {
            log.debug("用户没有 loginid，会话主体改用登录身份值：{}", identity);
        }
        return new LoginUser(
                loginid.isEmpty() ? null : loginid,
                sessionId,
                ((Number) row.get("id")).longValue(),
                lastname == null ? identity : lastname.trim(),
                row.get("departmentid") == null ? null : ((Number) row.get("departmentid")).longValue(),
                row.get("subcompanyid1") == null ? null : ((Number) row.get("subcompanyid1")).longValue()
        );
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
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

    /** 登录用到的两个配置值，收在一起免得传一长串参数 */
    private record IntegrationRowRef(String providerType, String matchField) {
    }
}
