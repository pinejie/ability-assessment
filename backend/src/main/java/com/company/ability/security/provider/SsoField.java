package com.company.ability.security.provider;

/**
 * 单点登录配置里的<b>一个接口地址字段</b>，由协议自己声明。
 *
 * <p><b>为什么要有这个</b>：配置页原来写死了三行「发起认证地址 / 票据兑换地址 / 用户信息地址」，
 * 因为当时只接泛微一家，而泛微就是三个接口。接票据模式（三方系统只给两个接口：authorize +
 * userinfo）时，第三个框既没有对应接口、又标着必填 —— 不填存不进去，填了是个假地址。
 * 把「本协议用到哪几个地址」交给协议自己说，页面照着渲染，就不会再出现「页面上有个框，
 * 但协议里没这东西」。</p>
 *
 * <p><b>key 为什么不直接用数据库列名</b>：这一层是给页面看的，key 用
 * {@code authorizeUrl / tokenUrl / profileUrl} 这种语义名，前端按 key 决定绑哪个 v-model。
 * 数据库列名（{@code sso_authorize_url} 等）不因为换了协议就改 —— 一列不改是这次改动的底线，
 * 存量数据、备份、运维脚本都不受影响。</p>
 *
 * @param key      字段标识，前端据此绑定表单字段：authorizeUrl / tokenUrl / profileUrl
 * @param label    页面上的标签，如「发起认证地址」。不同协议对同一个接口的叫法可能不同
 * @param required 本协议下是否必填。所有协议声明的地址目前全部必填（2026-11-25 起
 *                 「能由根地址推导」不再算填了 —— 推出来的值没有人确认过，配错了要等到
 *                 有人点登录才发现）。这个标记仍保留：将来真有可选地址的协议，页面照它渲染
 * @param hint     标签下方的一句说明，解释这个地址是干什么用的；可空
 */
public record SsoField(String key, String label, boolean required, String hint) {

    /** 必填字段的简写 */
    public static SsoField required(String key, String label, String hint) {
        return new SsoField(key, label, true, hint);
    }

    /** 选填字段的简写 */
    public static SsoField optional(String key, String label, String hint) {
        return new SsoField(key, label, false, hint);
    }
}
