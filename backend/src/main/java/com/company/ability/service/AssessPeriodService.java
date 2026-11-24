package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 年度设置：新增年度，设置进行中 / 已关闭。
 * 年度只影响人员能力要求和测评汇总页面；测评能力模型全局共用，不按年设置。
 * 年度按维度隔离：同一公司同一年度在不同维度下各自独立。
 * 不变式（2026-10-22）：同一公司+维度下「进行中」年度至多一个——新增年度即激活并自动
 * 关闭原进行中年度；激活某年度时自动关闭其他进行中年度。先关闭其他、再打开目标，
 * 兼容 sql/24 筛选唯一索引。
 *
 * <h3>谁能改（2026-11-23 方案 B）</h3>
 *
 * <p>年度不是全局设置，它挂在维度上（uk 里有 dimension_id），所以维护权也按维度给：
 * <b>该维度的维度管理员</b>（{@code uf_assess_dimension_auth} 里 auth_type=2 的人员）
 * 与系统管理员可新增/关闭年度；只靠分部授权进来看数据的普通用户只读。
 * 读（列表）对任何通过维度过滤的人开放 —— 发起测评、测评报告、任务列表三页都要拿年度下拉，
 * 那些页面的使用者大多不是维度管理员，把读也锁上等于把功能一起锁死。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessPeriodService {

    private final JdbcTemplate jdbc;
    private final AdminService adminService;
    private final AssessDimensionService dimensionService;

    @Value("${assess.company-id:1}")
    private long companyId;

    @Transactional
    public void create(int year) {
        requireWritePermission();
        if (year < 2000 || year > 2100) {
            throw new BusinessException("年度不合法，应在 2000–2100 之间");
        }
        long dimensionId = DimensionContext.getOrDefault();
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                Integer.class, companyId, year, dimensionId);
        if (exists != null && exists > 0) {
            throw new BusinessException(year + "年度已存在");
        }
        // 新增默认进行中（=激活），先关闭原进行中年度，保持维度内唯一（兼容 sql/24 唯一索引）
        closeOtherActivePeriods(dimensionId, -1L);
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id, create_time, update_time) " +
                "VALUES (?, ?, 0, ?, SYSDATETIME(), SYSDATETIME())", companyId, year, dimensionId);
    }

    /**
     * 关闭：该年度不再接受新评价，已提交的仍可查看。
     * @param status 0 进行中 1 已关闭
     */
    @Transactional
    public void changeStatus(long id, int status) {
        requireWritePermission();
        if (status != 0 && status != 1) {
            throw new BusinessException("状态不合法");
        }
        long dimensionId = DimensionContext.getOrDefault();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM uf_assess_period WHERE id = ? AND company_id = ? AND dimension_id = ?",
                id, companyId, dimensionId);
        if (rows.isEmpty()) {
            throw new BusinessException("年度不存在");
        }
        if (status == 0) {
            // 激活：先自动关闭该维度其他进行中年度（含快照冻结），再打开目标
            closeOtherActivePeriods(dimensionId, id);
        }
        // 关闭/重开年度只改本表：旧实现在这里还会冻结「报告快照」，
        // 但那套快照表（uf_assess_report_snapshot）已随方案 17 的 P3 清理删除。
        // 新模型的报告每次打开都直接读 uf_pa_*，年度关闭的语义由各处
        // 「年度已关闭不再接受新评价，已提交的仍可查看」保证。
        jdbc.update("UPDATE uf_assess_period SET status = ?, update_time = SYSDATETIME() WHERE id = ?", status, id);
    }

    /**
     * 关闭同维度其他「进行中」年度（只改状态；报告不再有快照表可冻结）。
     * @param exceptId 排除的年度 id（激活场景=目标年度；新增场景传 -1，无排除）
     */
    private void closeOtherActivePeriods(long dimensionId, long exceptId) {
        List<Map<String, Object>> others = jdbc.queryForList(
                "SELECT id, year FROM uf_assess_period " +
                "WHERE company_id = ? AND dimension_id = ? AND status = 0 AND id <> ?",
                companyId, dimensionId, exceptId);
        for (Map<String, Object> o : others) {
            long otherId = ((Number) o.get("id")).longValue();
            jdbc.update("UPDATE uf_assess_period SET status = 1, update_time = SYSDATETIME() WHERE id = ?", otherId);
            log.info("激活/新增年度联动：维度 {} 的 {} 年度已自动关闭，年度id={}",
                    dimensionId, o.get("year"), otherId);
        }
    }

    public List<PeriodItem> list() {
        long dimensionId = DimensionContext.getOrDefault();
        return jdbc.query(
                "SELECT id, year, status FROM uf_assess_period WHERE company_id = ? AND dimension_id = ? ORDER BY year DESC",
                (rs, i) -> {
                    PeriodItem p = new PeriodItem();
                    p.setId(rs.getLong("id"));
                    p.setYear(rs.getInt("year"));
                    p.setStatus(rs.getInt("status"));
                    return p;
                }, companyId, dimensionId);
    }

    /**
     * 当前登录人能不能维护当前维度的年度。
     *
     * <p>前端只用它来决定「新增年度 / 关闭年度 / 重新开启」这几个按钮是亮的还是灰的；
     * 权威拦截在 {@link #requireWritePermission()}，绕开前端直接打接口一样会 403。</p>
     */
    public boolean canWrite() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            return false;
        }
        if (adminService.isAdmin(user.loginid())) {
            return true;
        }
        return dimensionService.isDimensionAdmin(DimensionContext.getOrDefault(), user.resourceId());
    }

    /**
     * 写操作闸门：系统管理员，或当前维度的维度管理员。
     *
     * <p>未登录给 401、无权限给 403，与 {@code AdminService.requireAdmin()} 保持同一套状态码 ——
     * 前端拦截器按业务码 403 走「温和提示」，不会弹红色报错。</p>
     */
    private void requireWritePermission() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (adminService.isAdmin(user.loginid())) {
            return;
        }
        long dimensionId = DimensionContext.getOrDefault();
        if (!dimensionService.isDimensionAdmin(dimensionId, user.resourceId())) {
            throw new BusinessException(403, "年度由该维度的管理员维护，你当前只有查看权限");
        }
    }

    /**
     * 年度列表行。原本挂在旧 {@code AssessSheetVO} 上，随旧 VO 一起搬到这里 ——
     * 「年度设置」是旧模型里唯一保留下来的业务对象，它的 DTO 不该住在已删除的 VO 里。
     */
    @lombok.Data
    public static class PeriodItem {
        private Long id;
        private Integer year;
        private Integer status;
    }
}
