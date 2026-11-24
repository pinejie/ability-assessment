package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.exception.BusinessException;
import com.company.ability.support.PaTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 年度设置服务测试：维度内「进行中」年度唯一不变式（激活/新增自动关闭其他）
 * + 维护权限（方案 B：维度管理员/系统管理员可写，分部授权的普通用户只读）。
 *
 * <p>P3 起不再验证「报告快照冻结」：快照表 {@code uf_assess_report_snapshot}
 * 随方案 17 清理删除，年度关闭的语义只剩「不再接受新评价、已提交的仍可查看」。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("年度设置服务测试")
class AssessPeriodServiceTest {

    private static final long DIM = 1L;
    /** 维度 1 的维度管理员（人员授权） */
    private static final long DIM_ADMIN = 9001L;
    /** 只有分部授权的普通用户：能看年度，不能改 */
    private static final long PLAIN_USER = 9002L;
    private static final long SUB = 77L;

    @Autowired
    private AssessPeriodService periodService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM uf_assess_period");
        jdbc.update("DELETE FROM uf_assess_dimension_auth");
        jdbc.update("DELETE FROM uf_assess_admin");
        jdbc.update("DELETE FROM HrmSubCompany");
        jdbc.update("DELETE FROM HrmResource");
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '甲分部', NULL)", SUB);
        person(DIM_ADMIN, "dim_admin");
        person(PLAIN_USER, "plain_user");
        // 维度 1：人员授权给 DIM_ADMIN（= 维度管理员），分部授权给 SUB（= 普通可见用户）
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                DIM, DIM_ADMIN);
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 1, ?)",
                DIM, SUB);
        DimensionContext.set(DIM);
        PaTestData.login(DIM_ADMIN);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
        PaTestData.logout();
    }

    @Test
    @DisplayName("激活已关闭年度时，自动关闭同维度原进行中年度")
    void activateClosesOtherActivePeriod() {
        long activeId = insertPeriod(2026, 0, DIM);
        long targetId = insertPeriod(2027, 1, DIM);

        periodService.changeStatus(targetId, 0);

        assertEquals(0, statusOf(targetId), "目标年度应变为进行中");
        assertEquals(1, statusOf(activeId), "原进行中年度应被自动关闭");
    }

    @Test
    @DisplayName("新增年度默认进行中，并自动关闭该维度原进行中年度")
    void createAutoActivatesAndClosesCurrent() {
        long oldActive = insertPeriod(2026, 0, DIM);

        periodService.create(2027);

        long newId = periodIdOfYear(2027, DIM);
        assertEquals(0, statusOf(newId), "新年度应为进行中");
        assertEquals(1, statusOf(oldActive), "原进行中年度应被自动关闭");
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
    @DisplayName("关闭年度只改本年度状态，不联动打开/关闭其他年度")
    void closeOnlyFreezes() {
        long activeId = insertPeriod(2026, 0, DIM);
        long nextClosed = insertPeriod(2027, 1, DIM);

        periodService.changeStatus(activeId, 1);

        assertEquals(1, statusOf(activeId));
        // 关闭不会把其他已关闭年度错误地翻起来
        assertEquals(1, statusOf(nextClosed));
    }

    // ========== 维护权限（方案 B） ==========

    @Test
    @DisplayName("权限：维度管理员可写，只有分部授权的普通用户只读")
    void dimensionAdminCanWritePlainUserCannot() {
        assertTrue(periodService.canWrite(), "维度管理员应可维护年度");

        PaTestData.login(PLAIN_USER);
        assertFalse(periodService.canWrite(), "分部授权用户只读");
        BusinessException createErr = assertThrows(BusinessException.class,
                () -> periodService.create(2027), "只读用户新增年度应被拒");
        assertEquals(403, createErr.getCode());
        long id = insertPeriod(2026, 0, DIM);
        BusinessException statusErr = assertThrows(BusinessException.class,
                () -> periodService.changeStatus(id, 1), "只读用户关闭年度应被拒");
        assertEquals(403, statusErr.getCode());
        assertEquals(0, statusOf(id), "被拒后状态不应变化");
    }

    @Test
    @DisplayName("权限：系统管理员不占维度授权也能写")
    void systemAdminCanWriteWithoutDimensionAuth() {
        PaTestData.loginAs(PLAIN_USER, "boot-admin1");
        assertTrue(periodService.canWrite());
        periodService.create(2027);
        assertEquals(0, statusOf(periodIdOfYear(2027, DIM)));
    }

    @Test
    @DisplayName("权限：未登录不给写，但列表仍可读")
    void anonymousCannotWriteButCanList() {
        PaTestData.logout();
        assertFalse(periodService.canWrite());
        assertEquals(401, assertThrows(BusinessException.class,
                () -> periodService.create(2027)).getCode());
        assertTrue(periodService.list().isEmpty(), "无年度时列表为空，不因未登录报错");
    }

    @Test
    @DisplayName("跨维度隔离：A 维度管理员看不到也改不了 B 维度的年度")
    void dimensionAdminIsScopedToOneDimension() {
        long dimB = 2L;
        long otherAdmin = 9003L;
        person(otherAdmin, "dim_b_admin");
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (?, 2, ?)",
                dimB, otherAdmin);
        long periodB = insertPeriod(2026, 0, dimB);
        insertPeriod(2025, 1, DIM);

        // 维度 1 管理员在维度 1 上下文里：看不到 B 的年度，也动不了 B 的年度
        assertEquals(1, periodService.list().size(), "只应看到本维度年度");
        assertEquals(2025, periodService.list().get(0).getYear());
        assertThrows(BusinessException.class, () -> periodService.changeStatus(periodB, 1),
                "改其他维度的年度应被拒");
        assertEquals(0, statusOf(periodB), "B 维度年度状态不应变化");

        // 切到维度 2 上下文同样是 B 的管理员才行；A 的管理员在 B 里连写都不行
        DimensionContext.set(dimB);
        assertFalse(periodService.canWrite(), "A 维度管理员在 B 维度里不是管理员");
        PaTestData.login(otherAdmin);
        assertTrue(periodService.canWrite());
        assertEquals(1, periodService.list().size());
    }

    @Test
    @DisplayName("新增：同维度重复年份被拒，换一个维度同一年可以并存")
    void duplicateYearRejectedWithinDimension() {
        periodService.create(2027);
        BusinessException err = assertThrows(BusinessException.class,
                () -> periodService.create(2027), "同维度同年不能建第二条");
        assertTrue(err.getMessage().contains("已存在"), "提示要说明已存在，实际：" + err.getMessage());
        assertEquals(1, countOfYear(2027, DIM), "被拒后不应多出一条");

        // 同一年的另一维度互不影响（年度按维度隔离，不是全局唯一）。
        // 切维度后要重判权限，所以把同一个管理员也授到维度 2 上。
        jdbc.update("INSERT INTO uf_assess_dimension_auth (dimension_id, auth_type, auth_id) VALUES (2, 2, ?)",
                DIM_ADMIN);
        DimensionContext.set(2L);
        periodService.create(2027);
        assertEquals(1, countOfYear(2027, 2L));
        assertEquals(1, countOfYear(2027, DIM));
    }

    @Test
    @DisplayName("新增：年份越界被拒，且越界校验不写库")
    void outOfRangeYearRejected() {
        assertThrows(BusinessException.class, () -> periodService.create(1999));
        assertThrows(BusinessException.class, () -> periodService.create(2101));
        assertEquals(0, periodService.list().size());
    }

    @Test
    @DisplayName("新增：已关闭的年份也算占用，不能再建一条同年的")
    void closedYearStillBlocksDuplicate() {
        insertPeriod(2027, 1, DIM);   // 已关闭
        assertThrows(BusinessException.class, () -> periodService.create(2027),
                "关闭只是状态，年份仍然占着");
    }

    // ========== 辅助 ==========

    private void person(long id, String loginid) {
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1) "
                + "VALUES (?, ?, 1, ?, ?)", id, loginid, loginid, SUB);
    }

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

    private int countOfYear(int year, long dimensionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_period WHERE company_id = 1 AND year = ? AND dimension_id = ?",
                Integer.class, year, dimensionId);
    }
}
