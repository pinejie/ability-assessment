package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessSheetVO;
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
 * 年度只影响人员能力要求和测评汇总页面；测评配置全局共用，不按年设置。
 * 年度按维度隔离：同一公司同一年度在不同维度下各自独立。
 * 不变式（2026-10-22）：同一公司+维度下「进行中」年度至多一个——新增年度即激活并自动
 * 关闭原进行中年度；激活某年度时自动关闭其他进行中年度。自动关闭复用既有关闭链路
 * （含报告快照冻结），先关闭其他、再打开目标，兼容 sql/24 筛选唯一索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessPeriodService {

    private final JdbcTemplate jdbc;
    private final AssessReportService reportService;

    @Value("${assess.company-id:1}")
    private long companyId;

    @Transactional
    public void create(int year) {
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
     * 关闭：冻结该年所有页面的报告快照；重新开启：删除快照，恢复实时计算。
     * @param status 0 进行中 1 已关闭
     */
    @Transactional
    public void changeStatus(long id, int status) {
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
        jdbc.update("UPDATE uf_assess_period SET status = ?, update_time = SYSDATETIME() WHERE id = ?", status, id);
        if (status == 1) {
            reportService.freezePeriod(id);
        } else {
            reportService.unfreezePeriod(id);
        }
    }

    /**
     * 关闭同维度其他「进行中」年度，逐个复用关闭链路（status=1 + 报告快照冻结）。
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
            reportService.freezePeriod(otherId);
            log.info("激活/新增年度联动：维度 {} 的 {} 年度已自动关闭（快照冻结），年度id={}",
                    dimensionId, o.get("year"), otherId);
        }
    }

    public List<AssessSheetVO.PeriodItem> list() {
        long dimensionId = DimensionContext.getOrDefault();
        return jdbc.query(
                "SELECT id, year, status FROM uf_assess_period WHERE company_id = ? AND dimension_id = ? ORDER BY year DESC",
                (rs, i) -> {
                    AssessSheetVO.PeriodItem p = new AssessSheetVO.PeriodItem();
                    p.setId(rs.getLong("id"));
                    p.setYear(rs.getInt("year"));
                    p.setStatus(rs.getInt("status"));
                    return p;
                }, companyId, dimensionId);
    }
}
