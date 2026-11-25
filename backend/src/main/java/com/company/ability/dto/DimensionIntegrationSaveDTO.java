package com.company.ability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 保存维度集成配置的入参。
 *
 * <p><b>密钥的三种语义</b>（不能混）：
 * <ul>
 *   <li>传 {@link com.company.ability.vo.DimensionIntegrationVO#SECRET_MASK}（{@code __UNCHANGED__}）
 *       → 保持库里原值不变。页面回显的就是这个占位符，用户没动过密钥时自然回传它。</li>
 *   <li>传空串 {@code ""} → 清空。表示「这个维度不要密钥了」。</li>
 *   <li>传非空明文 → 加密后覆盖。</li>
 * </ul>
 * 若把「空串」也当成「不修改」，用户就永远没法清掉一个配错的密钥；
 * 若把「占位符」当成真密钥存进去，下次登录会因为拿 {@code __UNCHANGED__} 去换 token 而失败 ——
 * 两种错法都很隐蔽，所以在这里写死语义并在服务层强制。</p>
 *
 * <p><b>2026-11-25 起单点登录下的每一项都是必填且无默认值</b>。原来「留空走协议默认」
 * 那一套已经删掉：配置为空时后端不再替用户猜 ticket / GET / id / 根地址推导，
 * 页面上也不再摆默认值的 placeholder。代价是每个维度要真的问过对方再填一遍，
 * 换来的是「配置页显示什么，登录就打什么」—— 不会再有库里空着、登录却能跑的情形。
 * 每个字段下面写了它是干什么的，填错时表现为哪种故障。</p>
 */
@Data
public class DimensionIntegrationSaveDTO {

    @NotNull(message = "维度 ID 不能为空")
    private Long dimensionId;

    // ===== 登录 =====
    /**
     * @deprecated 2026-11-25 删除「启用单点登录」开关后，服务端<b>不再读</b>这个字段。
     * 「这个维度提不提供单点登录」现在只看 {@link #ssoClientSecret} 是否为空 ——
     * 开关与实际能力不挂钩（开着没填密钥、填好了却关着都能存进去），页面已取下。
     * 字段保留只为不破坏可能存在的旧调用方，传什么都不生效。
     */
    @Deprecated
    private Boolean ssoEnabled;

    /** 协议类型，必须是已注册的 IdentityProvider 类型（服务层校验） */
    @NotBlank(message = "单点登录类型不能为空")
    private String ssoProviderType;

    @NotBlank(message = "客户端 ID 不能为空")
    private String ssoClientId;

    /** 见类注释的三种语义。启用登录时不能为空（服务层校验） */
    private String ssoClientSecret;

    /**
     * 认证中心根地址，如 {@code http://xcx.zhongda.cn:8080}。
     *
     * <p>它不再参与「推导三个接口地址」（那三个现在必须自己填），但仍<b>必填</b>：
     * 泛微协议拼统一注销地址时要取它的 origin，缺了就是登出时 500。
     * 票据模式没有认证中心这个概念，所以这里不能加 {@code @NotBlank} ——
     * 加了会让票据模式永远存不进去。「本协议要不要它」由协议形态声明，
     * 由服务层按协议查（见 {@code DimensionIntegrationController#validateByProviderShape}）。</p>
     */
    private String ssoBaseUrl;

    /** 发起认证地址（完整 URL） */
    @NotBlank(message = "发起认证地址不能为空")
    private String ssoAuthorizeUrl;

    /** 票据兑换地址（完整 URL） */
    @NotBlank(message = "票据兑换地址不能为空")
    private String ssoTokenUrl;

    /**
     * 用户信息地址（完整 URL）。
     *
     * <p><b>2026-11-25 起服务端忽略这个入参</b>：泛微的用户信息地址是固定的
     * （根地址 + {@code /sso/oauth2.0/profile}），票据模式又没有这个接口，
     * 两种协议下它都不该由人填，所以页面已经撤掉这个框。保留字段只为兼容旧页面/旧脚本，
     * 库里那一列由保存接口按现有值原样写回（见 {@code DimensionIntegrationController.save}）。</p>
     */
    @Deprecated
    private String ssoProfileUrl;

    /** 对方响应里取哪个字段作身份值，如 id；必填，不再回退协议默认 */
    @NotBlank(message = "用户标识字段不能为空")
    private String ssoUserinfoField;

    /** 回调 URL 里携带票据的参数名：泛微 {@code ticket}，标准 OAuth2 {@code code}。必填 */
    @NotBlank(message = "回调票据参数名不能为空")
    private String ssoCallbackParam;

    /** 票据兑换接口的请求方式 GET / POST。必填，不再回退协议默认 */
    @NotBlank(message = "兑换请求方式不能为空")
    @Pattern(regexp = "(?i)GET|POST", message = "兑换请求方式只能是 GET 或 POST")
    private String ssoTokenMethod;

    /** 用户信息接口的请求方式 GET / POST。本协议用得上这个接口时必填（服务层按协议查） */
    @Pattern(regexp = "(?i)GET|POST", message = "用户信息请求方式只能是 GET 或 POST")
    private String ssoProfileMethod;

    /** 兑换响应里取票据的字段名，如 access_token。本协议有这一步时必填（服务层按协议查） */
    private String ssoAccessTokenField;

    /** 去人员表哪一列匹配，只能是 loginid / workcode。必填，页面不预选 */
    @NotBlank(message = "匹配字段不能为空")
    @Pattern(regexp = "loginid|workcode", message = "匹配字段只能是 loginid 或 workcode")
    private String ssoMatchField;

    /**
     * 回调相对路径。
     *
     * @deprecated 2026-11-25 起页面不再提供此项，服务端也<b>忽略</b>这个入参 ——
     * 回调地址必须和对方登记的白名单逐字符一致，做成可配置项等于给了一个「一改就全员
     * 登不进来」的开关。字段保留是为了不破坏可能存在的旧客户端调用，读到也不生效。
     */
    @Deprecated
    private String ssoRedirectPath;

    // ===== 推送 =====
    private Boolean pushEnabled;

    @NotBlank(message = "推送渠道不能为空")
    private String pushChannel;

    private String pushAgentId;
    /** 见类注释的三种语义 */
    private String pushSecret;
    private String pushExtraJson;
}
