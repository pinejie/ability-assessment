package com.company.ability.security;

import java.io.Serializable;

/**
 * 登录用户主体（SSO 认证通过后由 JWT 还原）。
 * 身份锚定泛微 HrmResource：resourceId 为人员主键，loginid 为 OA 登录名。
 *
 * <p><b>为什么 loginid 和 sessionId 要分开</b>：接入多系统后，有人是<b>按工号</b>登录进来的
 * （维度配置 {@code sso_match_field=workcode}），他可能根本没有泛微登录名 ——
 * 在岗 2305 人里有 1531 人如此。这时：
 * <ul>
 *   <li>{@code sessionId} 是<b>这次登录取到的身份值</b>（登录名或工号），JWT subject 用它，
 *       保证永远非空 —— 它是「这次会话是谁」的稳定标识；</li>
 *   <li>{@code loginid} 保留<b>真实的泛微登录名</b>，没有就是 null。
 *       管理员名单（{@code assess.bootstrap-admins}、{@code uf_assess_admin}）按登录名存，
 *       比对时只能用这一列。</li>
 * </ul>
 * 两者混用会出两种事故：用会话主体去做管理员判定 → 某个工号恰好等于别人的登录名时误判放权；
 * 用登录名做 JWT 主体 → 没有登录名的人签不出 token。</p>
 *
 * @param loginid      OA 登录名（HrmResource.loginid），<b>可能为 null</b>
 * @param sessionId    本次会话的身份值：loginid 非空时为 loginid，否则为工号（保证非空）
 * @param resourceId   人员 ID（HrmResource.id），维度授权等按此过滤
 * @param name         姓名（HrmResource.lastname）
 * @param departmentId 部门 ID
 * @param subcompanyId 分部 ID
 */
public record LoginUser(
        String loginid,
        String sessionId,
        Long resourceId,
        String name,
        Long departmentId,
        Long subcompanyId
) implements Serializable {

    /**
     * 兼容构造：泛微维度登录（loginid 就是会话主体）。
     *
     * <p>保留它是因为现有测试与部分内部构造点只关心泛微这一种形态；
     * 新增的登录路径请显式给两个值，别走这里 —— 否则又变成「以为有登录名」。</p>
     */
    public LoginUser(String loginid, Long resourceId, String name, Long departmentId, Long subcompanyId) {
        this(loginid, loginid, resourceId, name, departmentId, subcompanyId);
    }
}
