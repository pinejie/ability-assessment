package com.company.ability.service;

import com.company.ability.dto.AssessAdminCreateDTO;
import com.company.ability.vo.AssessAdminVO;

import java.util.List;

/**
 * 系统管理员服务：身份判定（配置超管 ∪ uf_assess_admin 表）与名单维护
 */
public interface AdminService {

    /**
     * 是否管理员。角色实时查库判定（不走 JWT 携带），撤销即时生效。
     */
    boolean isAdmin(String loginid);

    /**
     * 是否超级管理员：仅看配置 assess.bootstrap-admins（不含表添加的普通管理员）。
     * 只有超管可维护管理员名单（防止普通管理员互相加人扩权）。
     */
    boolean isSuperAdmin(String loginid);

    /**
     * 要求当前登录者为管理员：未登录 401，无角色 403（BusinessException）
     */
    void requireAdmin();

    /**
     * 要求当前登录者为超级管理员：未登录 401，非超管 403（BusinessException）
     */
    void requireSuperAdmin();

    /**
     * 管理员名单（表记录 + 未落表的配置超管合成条目）
     */
    List<AssessAdminVO> list();

    /**
     * 新增管理员（按泛微在职人员），返回记录 ID
     */
    Long add(AssessAdminCreateDTO dto);

    /**
     * 移除管理员：配置超管与本人不可删
     */
    void delete(Long id);
}
