package com.company.ability.security.provider;

import java.util.List;

/**
 * 一套单点登录协议<b>在配置页上长什么样</b>：用哪几个地址、要不要再调一次取身份、
 * 能不能由根地址推导。
 *
 * <p><b>为什么由协议自己描述，而不是页面写死一套</b>：配置页原来照泛微的 OAuth2 三步写死，
 * 三个地址框全部必填。接票据模式（三方只给 authorize + userinfo 两个接口）时，
 * 「用户信息地址」在协议里根本不存在，但页面坚持要么填它、要么填一个对三方毫无作用的
 * 认证中心根地址 —— 结果是<b>正确的配置存不进去，错误的配置反而能存</b>。
 * 判断依据（要几个接口、能不能推导）本来就是协议的属性，放在协议上，
 * 页面和保存校验读同一份，才不会各写一遍再各自漂移。</p>
 *
 * <p><b>2026-11-25 起不再有任何字段默认值</b>：原来这里还带
 * {@code identityFieldDefault} / {@code matchFieldDefault}，页面拿它们当 placeholder、
 * 留空时后端拿它们兜底。结果是「打开页面就是一个能直接保存的、看起来配好了的配置」——
 * 用户从没确认过对方返回的是登录名还是工号，而这两者配错的后果是<b>164 人登不进来</b>。
 * 现在形态只描述「有哪些框」，值必须由人去问对方、再显式填。</p>
 *
 * <p><b>与 {@link IdentityProvider#resolveUrl} 的关系</b>：{@code derivableFromBase} 为 true
 * 的协议，三个 {@code default*Path()} 必须给出非空路径；为 false 的必须都给 null。
 * 这两件事是同一个事实的两面，拆开写迟早会不一致 —— {@code IdentityProvider#shape()}
 * 的默认实现直接从三个 path 方法推出这个布尔值，不让人手填。</p>
 *
 * @param fields               本协议用到的接口地址，按页面显示顺序排列。每一项都必须显式填写
 * @param accessTokenStep      兑换票据后是否还要再调一次接口才能拿到身份值。
 *                             泛微是「ticket → access_token → profile」三步，为 true；
 *                             票据模式一次 userinfo 就返回身份，为 false。
 *                             页面据此决定要不要显示「access_token 字段名」这类只在两步协议里才有意义的配置
 * @param derivableFromBase    本协议是否有「认证中心根地址 + 固定路径」这套规律。
 *                             只决定页面显示不显示根地址框与「用根地址重新填」按钮
 *                             （那两个按钮只是省手工，不替代填写）；为 false 时这两样不显示 ——
 *                             留着只会让人以为填了有用
 */
public record SsoShape(
        List<SsoField> fields,
        boolean accessTokenStep,
        boolean derivableFromBase
) {

    /** 按 key 找字段；本协议没有这个字段时返回 null（页面据此决定显不显示） */
    public SsoField field(String key) {
        if (key == null) {
            return null;
        }
        for (SsoField f : fields) {
            if (key.equals(f.key())) {
                return f;
            }
        }
        return null;
    }

    /** 本协议是否用得到这个地址字段 */
    public boolean uses(String key) {
        return field(key) != null;
    }
}
