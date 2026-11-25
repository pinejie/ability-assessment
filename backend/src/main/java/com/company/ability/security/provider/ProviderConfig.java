package com.company.ability.security.provider;

/**
 * 一次登录用到的配置（从 {@code uf_assess_dimension_integration} 解出，密钥已解密）。
 *
 * <p><b>为什么单独一个 record 而不是把 Entity 传给 Provider</b>：
 * Entity 里有 push_* 那一半字段、以及 update_by/update_time 这些与登录无关的东西。
 * Provider 拿到整个 Entity，就会有人忍不住去读 push_channel 之类的字段，
 * 适配层与业务配置从此缠在一起。这个 record 只放登录真正需要的，
 * 边界一眼可见。</p>
 *
 * <p><b>2026-11-25 起这里不再有「留空取协议默认」</b>：原来的
 * {@code callbackParamOr / tokenMethodOr / profileMethodOr / accessTokenFieldOr}
 * 四个方法会在配置为空时静默换成协议默认值。那套兜底让「必填」成为一句空话 ——
 * 配置页上留空照样能存、照样能登录，等到对方换了参数名才发现库里那一栏一直是空的。
 * 现在这四个值都必须显式配置，读不到就由调用方报「维度 X 的某栏未配置」，
 * 而不是替它猜一个。</p>
 *
 * <p><b>不覆盖 toString</b>：record 默认展开所有字段，会打印 clientSecret。
 * 所以这里显式覆盖，把密钥抹掉 —— 现有 {@code OaSsoClient.maskUrl} 就是这么做的，
 * 但它只防住了日志里的 URL，防不住 {@code log.info("config={}", config)} 这种写法。</p>
 *
 * @param providerType   协议类型，weaver / oidc / ...
 * @param clientId       应用标识
 * @param clientSecret   应用密钥（已解密）
 * @param baseUrl        认证中心基地址（泛微用；路径由 Provider 拼）
 * @param authorizeUrl   发起认证地址（完整 URL，必填）
 * @param tokenUrl       票据兑换地址（完整 URL，必填）
 * @param profileUrl     用户信息地址（本协议用到时必填）
 * @param userinfoField  响应 JSON 里取哪个字段作身份值（必填）
 * @param callbackParam  回调 URL 里携带票据的参数名（必填）
 * @param tokenMethod    票据兑换接口的请求方式 GET/POST（必填）
 * @param profileMethod  用户信息接口的请求方式 GET/POST（本协议用到时必填）
 * @param accessTokenField 兑换响应里取票据的字段名（本协议用到时必填）
 * @param redirectUri    回调地址（完整 URL，带域名）
 * @param dimensionId    所属维度，仅用于日志与排查
 */
public record ProviderConfig(
        String providerType,
        String clientId,
        String clientSecret,
        String baseUrl,
        String authorizeUrl,
        String tokenUrl,
        String profileUrl,
        String userinfoField,
        String callbackParam,
        String tokenMethod,
        String profileMethod,
        String accessTokenField,
        String redirectUri,
        Long dimensionId
) {

    @Override
    public String toString() {
        return "ProviderConfig[type=" + providerType
                + ", clientId=" + clientId
                + ", clientSecret=***"
                + ", baseUrl=" + baseUrl
                + ", authorizeUrl=" + authorizeUrl
                + ", tokenUrl=" + tokenUrl
                + ", profileUrl=" + profileUrl
                + ", userinfoField=" + userinfoField
                + ", redirectUri=" + redirectUri
                + ", dimensionId=" + dimensionId + "]";
    }
}
