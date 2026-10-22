package com.company.ability.service;

import com.company.ability.entity.AssessDimension;
import com.company.ability.entity.AssessDimensionAuth;
import com.company.ability.mapper.AssessDimensionAuthMapper;
import com.company.ability.mapper.AssessDimensionMapper;
import com.company.ability.vo.AssessDimensionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度（类租户）切换器可见性测试：普通人员按授权过滤，管理员全量（禁用维度除外）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("维度切换器管理员全量可见测试")
class AssessDimensionAdminTest {

    private static final long SUB_AUTHORIZED = 9L;
    private static final long SUB_OTHER = 8L;

    @Autowired
    private AssessDimensionService dimensionService;

    @Autowired
    private AssessDimensionMapper dimensionMapper;

    @Autowired
    private AssessDimensionAuthMapper authMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private Long openDim;
    private Long restrictedDim;
    private Long disabledDim;

    @BeforeEach
    void seed() {
        openDim = insertDimension("开放维度", 1, 1);
        restrictedDim = insertDimension("限定维度", 1, 2);
        disabledDim = insertDimension("禁用维度", 0, 3);

        AssessDimensionAuth auth = new AssessDimensionAuth();
        auth.setDimensionId(restrictedDim);
        auth.setAuthType(AssessDimensionAuth.TYPE_SUBCOMPANY);
        auth.setAuthId(SUB_AUTHORIZED);
        authMapper.insert(auth);

        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1) "
                + "VALUES (100, '甲', 1, 'u100', ?)", SUB_AUTHORIZED);
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1) "
                + "VALUES (200, '乙', 1, 'u200', ?)", SUB_OTHER);
    }

    @Test
    @DisplayName("普通人员：只见开放维度与命中的授权维度，禁用维度不可见")
    void normalUserSeesOnlyGranted() {
        List<Long> visible = ids(dimensionService.authorized(200L, false));

        assertEquals(List.of(openDim), visible);

        List<Long> authorizedHit = ids(dimensionService.authorized(100L, false));
        assertTrue(authorizedHit.contains(restrictedDim));
    }

    @Test
    @DisplayName("管理员：全部启用维度可见（不受授权限制），禁用维度仍不可见")
    void adminSeesAllEnabled() {
        List<Long> visible = ids(dimensionService.authorized(200L, true));

        assertEquals(List.of(openDim, restrictedDim), visible);
        assertFalse(visible.contains(disabledDim));
    }

    @Test
    @DisplayName("无身份（旧宽松口径）：不过滤，返回全部启用维度")
    void anonymousStaysLenient() {
        List<Long> visible = ids(dimensionService.authorized(null, false));

        assertEquals(List.of(openDim, restrictedDim), visible);
    }

    // ==================== 辅助 ====================

    private Long insertDimension(String name, int status, int sort) {
        AssessDimension dimension = new AssessDimension();
        dimension.setDimensionName(name);
        dimension.setStatus(status);
        dimension.setSort(sort);
        dimensionMapper.insert(dimension);
        return dimension.getId();
    }

    private List<Long> ids(List<AssessDimensionVO> dimensions) {
        return dimensions.stream().map(AssessDimensionVO::getId).toList();
    }
}
