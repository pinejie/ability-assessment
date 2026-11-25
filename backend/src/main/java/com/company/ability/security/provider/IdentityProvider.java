package com.company.ability.security.provider;

import java.util.List;

/**
 * 单点登录协议适配器。
 *
 * <p><b>它只回答一个问题</b>：手里这张票，是谁的？
 * 至于这人能不能进系统（在不在岗、有没有匹配记录），不归它管 ——
 * 那是 {@code SsoLoginServiceImpl} 的事。这条边界让每个 Provider 都能独立单测，
 * 不需要准备人员表。</p>
 *
 * <p><b>为什么按类型注册而不是按配置实例化</b>：一个 Provider 实现对应一种协议，
 * 参数（client_id / 密钥 / 网址）每次调用时传进来。这样同一个协议接三个不同系统，
 * 只需要三行配置、一个实现类。反过来（一个系统一个实现）会退化成复制粘贴。</p>
 *
 * <p><b>新增一种协议要做的事</b>：
 * <ol>
 *   <li>实现本接口，{@link #providerType()} 返回一个新的类型名</li>
 *   <li>加进 {@code IdentityProviderRegistry}（Spring 自动收集 {@code @Component}）</li>
 *   <li>在数据库里把某个维度的 {@code sso_provider_type} 配成它</li>
 * </ol>
 * 业务代码一行不动。</p>
 */
public interface IdentityProvider {

    /**
     * 协议类型标识，与 {@code uf_assess_dimension_integration.sso_provider_type} 对应。
     * 必须是稳定的字符串（会存进数据库）。
     */
    String providerType();

    /**
     * 拼装跳转到对方认证中心的地址（浏览器 302 用）。
     *
     * @param config 该维度的登录配置
     * @param state  一次性随机串，用于防 CSRF 并携带回跳路径；对方会原样带回
     */
    String buildAuthorizeUrl(ProviderConfig config, String state);

    /**
     * 用回调拿到的票据换出身份值。
     *
     * <p>返回的是「对方系统里的用户标识」，具体是登录名还是工号取决于对方。
     * 拿它去人员表哪一列匹配，由配置的 {@code sso_match_field} 决定 ——
     * Provider 不关心，也不该关心。</p>
     *
     * @param config 该维度的登录配置
     * @param ticket 回调携带的临时票据（各协议叫法不同：ticket / code）
     * @return 身份值，非空
     * @throws com.company.ability.exception.BusinessException 兑换失败（票据过期、对方报错等）
     */
    String exchange(ProviderConfig config, String ticket);

    /**
     * 拼装统一注销地址。不需要统一注销的协议返回 null。
     */
    default String buildLogoutUrl(ProviderConfig config) {
        return null;
    }

    // ==================== 关于「协议默认值」 ====================
    //
    // 这里原来有四个方法：defaultCallbackParam / defaultTokenMethod /
    // defaultProfileMethod / defaultAccessTokenField，配置留空时就用它们兜底。
    // 2026-11-25 删掉了。理由不是「多几个方法麻烦」，而是那套兜底让必填形同虚设：
    // 配置页上四个框可以一直空着，登录照样能跑，于是没人知道库里那一栏其实是空的；
    // 等到对方换了参数名（ticket → code，GET → POST），表现是「回调地址缺少票据」，
    // 查配置页却发现那一栏「有东西」（那是 placeholder，不是值）。
    // 现在这四个值必须逐维度显式填写，读不到就报「维度 X 的某栏未配置」。
    //
    // 三个固定路径方法保留：它们不参与登录取值，只供配置页的「用根地址重新填」按钮
    // 省一次手工输入 —— 按下去是把值<b>写进输入框</b>，用户看得见、也能改，
    // 与「服务端偷偷兜底」是两回事。

    // ==================== 三个接口的固定路径 ====================
    //
    // 泛微的认证接口路径是固定的（/sso/oauth2.0/authorize 等），配了站点根就能一次把
    // 三个地址算出来填进输入框。标准 OAuth2 / OIDC 没有「固定路径」这回事 ——
    // 路径由对方给的 discovery 文档或应用注册页决定，每家都不一样，猜不出来。
    // 所以接口默认返回 null（＝本协议无法推导），需要协议自己覆盖。

    /** 发起认证接口在根地址下的固定路径；null 表示本协议无法由根地址推导 */
    default String defaultAuthorizePath() {
        return null;
    }

    /** 票据兑换接口的固定路径；null 同上 */
    default String defaultTokenPath() {
        return null;
    }

    /** 用户信息接口的固定路径；null 同上 */
    default String defaultProfilePath() {
        return null;
    }

    // ==================== 配置页形态 ====================

    /**
     * 本协议在配置页上需要哪些字段、怎么称呼、哪些必填。
     *
     * <p><b>默认实现覆盖「标准 OAuth2 / OIDC 那种没有固定路径」的形态</b>：
     * 三个接口全部手填、全部必填、没有可按下让系统代填的路径。泛微这种路径固定的，
     * 自己覆盖这个方法提供三个 path（只用于「用根地址重新填」这个省手工的按钮）。</p>
     *
     * <p><b>{@code derivableFromBase} 为什么不让人手填</b>：它和三个 {@code default*Path()}
     * 说的是同一件事 —— 有固定路径才推得出来。分成两个地方写，迟早出现
     * 「声明说推不出来、但路径方法返回了非空」这种自相矛盾。这里直接从路径方法推。</p>
     */
    default SsoShape shape() {
        boolean derivable = defaultAuthorizePath() != null
                && defaultTokenPath() != null
                && defaultProfilePath() != null;
        return new SsoShape(
                List.of(
                        SsoField.required("authorizeUrl", "发起认证地址",
                                "浏览器 302 到这里，由对方判断登录态并出票。"),
                        SsoField.required("tokenUrl", "票据兑换地址",
                                "服务端直连，用票据换访问凭证。"),
                        SsoField.required("profileUrl", "用户信息地址",
                                "服务端直连，用访问凭证换用户标识。")),
                true,
                derivable);
    }

    /**
     * 把「可能留空的地址」展开成实际会请求的完整 URL。
     *
     * <p><b>为什么这个展开放在协议上、并且对外公开</b>：配置页要显示的就是「点下去会打到哪」。
     * 如果页面自己在前端拼一遍、真正发请求时后端再拼一遍，两份逻辑迟早不一致 ——
     * 那时页面上显示的地址是对的，请求打到的地址是错的，排查会非常费劲。
     * 只留这一个实现，两边都调它。</p>
     *
     * @param explicitUrl 配置里填的完整地址（可空）
     * @param baseUrl     认证中心根地址（可空）
     * @param path        该协议在这个接口上的固定路径（可空＝本协议无法推导）
     * @return 完整地址；两条路都走不通时返回 null（页面就显示为空）
     */
    default String resolveUrl(String explicitUrl, String baseUrl, String path) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return explicitUrl.trim();
        }
        if (path == null || baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }
}
