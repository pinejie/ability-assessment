package com.company.ability.vo;

import lombok.Data;

/**
 * 维度集成配置（登录 + 推送）的读模型。
 *
 * <p><b>密钥永远不回显</b>：{@code clientSecret} / {@code pushSecret} 两个字段
 * 在这个 VO 里只承载「有没有配」这一个信息 —— 有值时写 {@link #SECRET_MASK}，
 * 没配时为 null。把密文或明文吐给前端等于把密钥暴露在浏览器历史、代理日志、
 * 开发者工具里，而这些地方都不该有它。</p>
 *
 * <p>保存时前端把 {@link #SECRET_MASK} 原样传回来，表示「不修改」——
 * 与「清空」是两个不同的意思。详见 {@code DimensionIntegrationSaveDTO}。</p>
 */
@Data
public class DimensionIntegrationVO {

    /** 密钥占位符：前端原样回传表示不修改 */
    public static final String SECRET_MASK = "__UNCHANGED__";

    private Long dimensionId;

    /** 维度名称，弹窗标题用，省一次查询 */
    private String dimensionName;

    // ===== 登录 =====
    private Boolean ssoEnabled;
    private String ssoProviderType;
    private String ssoClientId;
    /** 已配置时恒为 {@link #SECRET_MASK}，未配置为 null */
    private String ssoClientSecret;
    private String ssoBaseUrl;
    private String ssoAuthorizeUrl;
    private String ssoTokenUrl;
    private String ssoProfileUrl;
    private String ssoUserinfoField;
    /** 回调参数名；库里没配就是 null（不再用协议默认值填进来） */
    private String ssoCallbackParam;
    /** GET / POST；库里没配就是 null */
    private String ssoTokenMethod;
    private String ssoProfileMethod;
    /** 兑换响应里取票据的字段名；库里没配就是 null */
    private String ssoAccessTokenField;
    /** loginid / workcode */
    private String ssoMatchField;
    private String ssoRedirectPath;

    // ===== 协议固定路径（只读，仅供「用根地址重新填」这个按钮代填） =====
    //
    // 2026-11-25 删掉了原来那一组「协议默认值」字段（defaultCallbackParam /
    // defaultTokenMethod / defaultProfileMethod / defaultAccessTokenField /
    // defaultUserinfoField）。它们只用来在输入框里做 placeholder，而 placeholder
    // 让用户以为「这栏已经有值了」，于是库里一直空着也能一路保存。现在不回了：
    // 页面上每个必填框都必须真的有字。
    //
    // 剩下这三个路径不参与登录取值，只让「用根地址重新填」按钮把三个地址一次代填进输入框。
    // null＝本协议没有固定路径（标准 OAuth2 / 票据模式），按钮不出现。
    private String defaultAuthorizePath;
    private String defaultTokenPath;
    private String defaultProfilePath;

    /**
     * 当前协议在配置页上的形态：用哪几个地址、要不要 access_token 那一步。
     *
     * <p><b>为什么整块回给前端，而不是让前端按协议名写死</b>：页面原来写死三行地址框，
     * 接第二个协议时就会出现「页面有个框、协议里没这个接口」。形态跟着协议走，
     * 前端只负责照着渲染，新增协议不用改页面。</p>
     */
    private com.company.ability.security.provider.SsoShape ssoShape;

    /**
     * 该维度<b>实际</b>会用的完整回调地址，如 {@code https://ca.zhongda.cn/sso/callback/4}。
     *
     * <p>给三方注册回调白名单时要用精确值，原来只能自己拿「站点根 + 相对路径」手拼。
     * 这里由服务端拼好（与真正发起登录时用的是同一份逻辑），页面上直接显示、可复制。</p>
     *
     * <p>站点根（{@code assess.fill-base-url}）没配时为 null —— 不编造域名。</p>
     */
    private String callbackUrl;

    // ===== 推送 =====
    private Boolean pushEnabled;
    private String pushChannel;
    private String pushAgentId;
    /** 已配置时恒为 {@link #SECRET_MASK}，未配置为 null */
    private String pushSecret;
    private String pushExtraJson;

    /** 该维度是否已有配置行；false 时页面提示「尚未配置，点击填写」 */
    private Boolean configured;
}
