package com.company.ability.service;

import com.company.ability.security.LoginUser;

/**
 * 单点登录服务：发起跳转 / 处理回调。
 *
 * <p>与改造前的差别只有一处：多了 {@code dimensionId} 参数。
 * 原来只有泛微一套配置，从 {@code application.yml} 读；现在配置在维度上，
 * 所以发起和回调都要指明是哪个维度。
 * 它的用途仅限于<b>找到正确的配置行</b> —— 认证完成后，这个参数不再参与任何判断，
 * 用户属于哪个维度由人员表反查（见 {@code DimensionIntegrationService.resolveDimension}）。</p>
 */
public interface SsoLoginService {

    /**
     * 生成跳转到认证中心的 authorize 地址（内部签发 state）。
     *
     * @param dimensionId 维度 ID；null 表示用默认维度（兜底泛微门户里不带维度号的旧链接）
     * @param from        登录完成后前端回跳路径（站内相对路径，可为空）
     */
    String buildLoginRedirectUrl(Long dimensionId, String from);

    /**
     * 处理回调：校验 state → 按该维度配置的协议兑换出身份值 →
     * 按该维度配置的匹配字段查在职人员 → 签发本系统 JWT
     *
     * @param dimensionId 维度 ID；null 表示用默认维度
     * @param ticket      回调携带的临时票据
     * @param state       发起时签发的 state
     * @return 登录结果（token + 用户 + 回跳地址）
     */
    SsoLoginResult authenticate(Long dimensionId, String ticket, String state);

    /**
     * 登出地址：按该维度配置拼出对方认证中心的统一注销地址。
     *
     * @param dimensionId 维度 ID；null 表示用默认维度
     * @return 注销地址；该协议不支持统一注销时返回 null
     */
    String buildLogoutUrl(Long dimensionId);

    /**
     * 该维度回调 URL 里携带票据的参数名（泛微 ticket / 标准 OAuth2 code）。
     *
     * <p>前端回调页要用它决定去 URL 里取哪个参数。维度没配置或维度不存在时返回 null，
     * 前端按「依次试 ticket、code」兜底 —— 泛微门户里挂出去的旧链接不带维度号，
     * 这条路径必须还能走通。</p>
     *
     * @param dimensionId 维度 ID；null 表示用默认维度
     */
    String callbackParamOf(Long dimensionId);

    /**
     * 登录结果
     *
     * @param token    本系统 JWT
     * @param user     登录用户
     * @param redirect 前端回跳路径（已校验，仅站内相对路径）
     */
    record SsoLoginResult(String token, LoginUser user, String redirect) {
    }
}
