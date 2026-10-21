package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessSheetVO;
import lombok.RequiredArgsConstructor;
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
 */
@Service
@RequiredArgsConstructor
public class AssessPeriodService {

    private final JdbcTemplate jdbc;
    private final AssessReportService reportService;

    @Value("${assess.company-id:1}")
    private long companyId;

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
        jdbc.update("UPDATE uf_assess_period SET status = ?, update_time = SYSDATETIME() WHERE id = ?", status, id);
        if (status == 1) {
            reportService.freezePeriod(id);
        } else {
            reportService.unfreezePeriod(id);
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
