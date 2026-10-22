package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.dto.AssessAdminCreateDTO;
import com.company.ability.entity.AssessAdmin;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessAdminMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AdminService;
import com.company.ability.vo.AssessAdminVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统管理员实现。
 * 角色不写入 JWT：撤销/新增即时生效（JWT 有效期 2 小时且无黑名单，携带角色会留下撤销空窗）。
 * 表极小（个位数行），判定直接查库，不引缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AssessAdminMapper adminMapper;
    private final JdbcTemplate jdbc;

    /**
     * 配置超管（OA loginid，逗号分隔）：引导第一批管理员用，永远视为管理员且不可界面删除。
     * 例：assess.bootstrap-admins=2024188
     */
    @Value("${assess.bootstrap-admins:}")
    private String bootstrapAdmins;

    @Override
    public boolean isAdmin(String loginid) {
        if (loginid == null || loginid.isBlank()) {
            return false;
        }
        if (bootstrapSet().contains(loginid)) {
            return true;
        }
        return adminMapper.selectCount(new LambdaQueryWrapper<AssessAdmin>()
                .eq(AssessAdmin::getLoginid, loginid)) > 0;
    }

    @Override
    public void requireAdmin() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!isAdmin(user.loginid())) {
            throw new BusinessException(403, "需要管理员权限，请联系管理员开通");
        }
    }

    @Override
    public List<AssessAdminVO> list() {
        List<AssessAdmin> rows = adminMapper.selectList(
                new LambdaQueryWrapper<AssessAdmin>().orderByAsc(AssessAdmin::getId));
        Set<String> tableLoginids = new HashSet<>();
        List<AssessAdminVO> result = new ArrayList<>();
        for (AssessAdmin row : rows) {
            tableLoginids.add(row.getLoginid());
            result.add(toVO(row, bootstrapSet().contains(row.getLoginid())));
        }
        // 未落表的配置超管：合成展示条目（无 id，前端不可删）
        for (String loginid : bootstrapSet()) {
            if (tableLoginids.contains(loginid)) {
                continue;
            }
            AssessAdminVO vo = new AssessAdminVO();
            vo.setLoginid(loginid);
            vo.setLastname(lookupLastnameByLoginid(loginid));
            vo.setBootstrap(true);
            result.add(vo);
        }
        return result;
    }

    @Override
    public Long add(AssessAdminCreateDTO dto) {
        Map<String, Object> resource = findActiveResource(dto.getResourceId());
        String loginid = (String) resource.get("loginid");
        if (loginid == null || loginid.isBlank()) {
            throw new BusinessException("该人员在泛微中没有 OA 登录名（loginid），无法设为管理员");
        }
        if (bootstrapSet().contains(loginid)) {
            throw new BusinessException("该人员已是配置超管，无需重复添加");
        }
        if (adminMapper.selectCount(new LambdaQueryWrapper<AssessAdmin>()
                .eq(AssessAdmin::getLoginid, loginid)) > 0) {
            throw new BusinessException("该人员已是管理员");
        }
        AssessAdmin admin = new AssessAdmin();
        admin.setLoginid(loginid);
        admin.setLastname((String) resource.get("lastname"));
        admin.setRemark(dto.getRemark());
        LoginUser operator = UserContext.currentOrNull();
        if (operator != null) {
            admin.setCreateBy(operator.resourceId());
        }
        adminMapper.insert(admin);
        log.info("新增管理员: loginid={} 操作人={}", loginid,
                operator == null ? "匿名" : operator.loginid());
        return admin.getId();
    }

    @Override
    public void delete(Long id) {
        AssessAdmin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BusinessException("管理员记录不存在，ID: " + id);
        }
        if (bootstrapSet().contains(admin.getLoginid())) {
            throw new BusinessException("配置超管不可在界面删除（需改 assess.bootstrap-admins 配置）");
        }
        LoginUser operator = UserContext.currentOrNull();
        if (operator != null && Objects.equals(operator.loginid(), admin.getLoginid())) {
            throw new BusinessException("不能删除自己");
        }
        adminMapper.deleteById(id);
        log.info("移除管理员: loginid={} 操作人={}", admin.getLoginid(),
                operator == null ? "匿名" : operator.loginid());
    }

    // ==================== 内部方法 ====================

    /** 解析配置超管集合（每次现解析，配置运行期不变、代价可忽略） */
    private Set<String> bootstrapSet() {
        if (bootstrapAdmins == null || bootstrapAdmins.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(bootstrapAdmins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** 查在职泛微人员（口径与 SSO 登录一致：status=1） */
    private Map<String, Object> findActiveResource(Long resourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT loginid, lastname, status FROM HrmResource WHERE id = ?", resourceId);
        if (rows.isEmpty()) {
            throw new BusinessException("人员不存在（HrmResource.id=" + resourceId + "）");
        }
        Map<String, Object> row = rows.get(0);
        Object status = row.get("status");
        if (status == null || ((Number) status).intValue() != 1) {
            throw new BusinessException("该人员非在职状态，不能设为管理员");
        }
        return row;
    }

    /** 按登录名查姓名（配置超管展示用，查不到返回 null） */
    private String lookupLastnameByLoginid(String loginid) {
        List<String> names = jdbc.queryForList(
                "SELECT lastname FROM HrmResource WHERE loginid = ?", String.class, loginid);
        return names.isEmpty() ? null : names.get(0);
    }

    private AssessAdminVO toVO(AssessAdmin admin, boolean bootstrap) {
        AssessAdminVO vo = new AssessAdminVO();
        BeanUtils.copyProperties(admin, vo);
        vo.setBootstrap(bootstrap);
        return vo;
    }
}
