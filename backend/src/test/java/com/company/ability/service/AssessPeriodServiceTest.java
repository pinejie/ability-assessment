package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 年度设置服务测试：维度内「进行中」年度唯一不变式（激活/新增自动关闭其他，快照冻结联动）
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("年度设置服务测试")
class AssessPeriodServiceTest {

    private static final long DIM = 1L;

    @Autowired
    private AssessPeriodService periodService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 快照冻结/解冻不是本测试对象；mock 后既隔离报告表依赖，又能验证联动调用 */
    @MockBean
    private AssessReportService reportService;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM uf_assess_period");
        DimensionContext.set(DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    @Test
    @DisplayName("激活已关闭年度时，自动关闭同维度原进行中年度并冻结其快照")
    void activateClosesOtherActivePeriod() {
        long activeId = insertPeriod(2026, 0, DIM);
        long targetId = insertPeriod(2027, 1, DIM);

        periodService.changeStatus(targetId, 0);

        assertEquals(0, statusOf(targetId), "目标年度应变为进行中");
        assertEquals(1, statusOf(activeId), "原进行中年度应被自动关闭");
        verify(reportService).unfreezePeriod(targetId);
        verify(reportService).freezePeriod(activeId);
    }

    @Test
    @DisplayName("新增年度默认进行中，并自动关闭该维度原进行中年度")
    void createAutoActivatesAndClosesCurrent() {
        long oldActive = insertPeriod(2026, 0, DIM);

        periodService.create(2027);

        long newId = periodIdOfYear(2027, DIM);
        assertEquals(0, statusOf(newId), "新年度应为进行中");
        assertEquals(1, statusOf(oldActive), "原进行中年度应被自动关闭");
        verify(reportService).freezePeriod(oldActive);
        verify(reportService, never()).unfreezePeriod(anyLong());
    }

    @Test
    @DisplayName("不变式按维度隔离：其他维度的进行中年度不受影响")
    void otherDimensionUnaffected() {
        long sameDimActive = insertPeriod(2026, 0, DIM);
        long otherDimActive = insertPeriod(2026, 0, 2L);
        long targetId = insertPeriod(2027, 1, DIM);

        periodService.changeStatus(targetId, 0);

        assertEquals(1, statusOf(sameDimActive));
        assertEquals(0, statusOf(otherDimActive), "维度 2 的进行中年度不应被动");
    }

    @Test
    @DisplayName("关闭年度仍走冻结链路，且不联动打开/关闭其他年度")
    void closeOnlyFreezes() {
        long activeId = insertPeriod(2026, 0, DIM);
        long nextClosed = insertPeriod(2027, 1, DIM);

        periodService.changeStatus(activeId, 1);

        assertEquals(1, statusOf(activeId));
        verify(reportService).freezePeriod(activeId);
        verify(reportService, never()).unfreezePeriod(anyLong());
        // 关闭不会把其他已关闭年度错误地翻起来
        assertEquals(1, statusOf(nextClosed));
    }

    // ========== 辅助 ==========

    private long insertPeriod(int year, int status, long dimensionId) {
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, ?, ?)",
                year, status, dimensionId);
        return periodIdOfYear(year, dimensionId);
    }

    private long periodIdOfYear(int year, long dimensionId) {
        return jdbc.queryForObject("SELECT id FROM uf_assess_period WHERE company_id = 1 AND year = ? AND dimension_id = ?",
                Long.class, year, dimensionId);
    }

    private int statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM uf_assess_period WHERE id = ?", Integer.class, id);
    }
}
