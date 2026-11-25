package com.company.ability.security.provider;

/**
 * 一个协议在配置页上的<b>全部可描述信息</b>，供「协议下拉框旁边的一切」使用。
 *
 * <p><b>为什么把这些打包成一条</b>：用户在下拉框里换协议时，页面要同时变好几样东西 ——
 * 显示哪几个地址框（{@link #shape}）、以及「用根地址重新填」按钮能不能点（三个 path）。
 * 如果分几个接口去问，切换过程中页面会出现「框换了、按钮还在」的中间态；
 * 更麻烦的是每加一个协议要改好几处。打包成一条，`GET /integration-options` 一次全给。</p>
 *
 * <p>不能用「重新读一次该维度的配置」来替代：那读回来的是<b>库里存的</b>协议，
 * 而用户刚在下拉框里选的那个还没保存 —— 两者恰好是要区分的东西。</p>
 *
 * <p><b>2026-11-25 起去掉了四个「默认值」字段</b>（默认回调参数名 / 两种请求方式 / 取码字段名）。
 * 它们原来只用来在输入框里做 placeholder，而 placeholder 一旦存在，用户就会把它当成
 * 「已经配好了」直接保存。现在这些都必须在页面上填出确定的字，没有可参考的默认。</p>
 *
 * @param shape                 界面形态
 * @param defaultAuthorizePath  发起认证的固定路径；null＝推不出来
 * @param defaultTokenPath      兑换的固定路径；null＝推不出来
 * @param defaultProfilePath    用户信息的固定路径；null＝推不出来
 */
public record ProviderOption(
        SsoShape shape,
        String defaultAuthorizePath,
        String defaultTokenPath,
        String defaultProfilePath
) {

    /** 从一个协议实现里读出它的全部描述 */
    public static ProviderOption of(IdentityProvider provider) {
        return new ProviderOption(
                provider.shape(),
                provider.defaultAuthorizePath(),
                provider.defaultTokenPath(),
                provider.defaultProfilePath());
    }
}
