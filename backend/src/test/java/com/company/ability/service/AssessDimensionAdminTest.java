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
 * 维度（类租户）可见性测试（2026-10-22 语义）：
 * 未配置授权行的维度仅管理员可见；配置后按「人员 ID 精确命中，或人员分部沿上级链命中授权分部（子树）」访问；
 * 无登录身份维持宽松（内部集成调用）；禁用维度对所有人不可见。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("维度可见性语义测试")
class AssessDimensionAdminTest {

    private static final long SUB_AUTHORIZED = 9L;
    private static final long SUB_OTHER = 8L;
    private static final long PERSON_ID = 100L;
    private static final long PERSON_OTHER = 200L;

    @Autowired
    private AssessDimensionService dimensionService;

    @Autowired
    private AssessDimensionMapper dimensionMapper;

    @Autowired
    private AssessDimensionAuthMapper authMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long SUB_PARENT = 5L;

    private Long openDim;
    private Long subDim;
    private Long personDim;
    private Long subtreeDim;
    private Long disabledDim;

    @BeforeEach
    void seed() {
        openDim = insertDimension("未配置维度", 1, 1);
        subDim = insertDimension("分部授权维度", 1, 2);
        personDim = insertDimension("人员授权维度", 1, 3);
        disabledDim = insertDimension("禁用维度", 0, 4);
        subtreeDim = insertDimension("上级分部授权维度", 1, 5);

        insertAuth(subDim, AssessDimensionAuth.TYPE_SUBCOMPANY, SUB_AUTHORIZED);
        insertAuth(personDim, AssessDimensionAuth.TYPE_RESOURCE, PERSON_ID);
        // 子树语义：授权给上级分部 5，人员挂在下级分部 9 → 也应命中
        insertAuth(subtreeDim, AssessDimensionAuth.TYPE_SUBCOMPANY, SUB_PARENT);

        // 分部层级：5(父) → 9(子)；8 独立
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '父分部', NULL)", SUB_PARENT);
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '子分部', ?)", SUB_AUTHORIZED, SUB_PARENT);
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '另一分部', NULL)", SUB_OTHER);

        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1) "
                + "VALUES (?, '甲', 1, 'u100', ?)", PERSON_ID, SUB_AUTHORIZED);
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1) "
                + "VALUES (?, '乙', 1, 'u200', ?)", PERSON_OTHER, SUB_OTHER);
    }

    @Test
    @DisplayName("子树匹配：授权父分部覆盖下级分部人员；他链人员不命中")
    void subcompanyAuthCoversSubtree() {
        // 甲挂在 9（5 的下级）→ 授权 5 命中
        List<Long> hit = ids(dimensionService.authorized(PERSON_ID, false));
        assertTrue(hit.contains(subtreeDim));

        // 乙在 8（与 5 无祖先关系）→ 不命中
        List<Long> miss = ids(dimensionService.authorized(PERSON_OTHER, false));
        assertFalse(miss.contains(subtreeDim));
    }

    @Test
    @DisplayName("未配置授权的维度：普通用户不可见，管理员可见")
    void emptyAuthIsAdminOnly() {
        List<Long> visible = ids(dimensionService.authorized(PERSON_OTHER, false));
        assertFalse(visible.contains(openDim));

        List<Long> adminVisible = ids(dimensionService.authorized(PERSON_OTHER, true));
        assertTrue(adminVisible.contains(openDim));
    }

    @Test
    @DisplayName("命中方式：分部链（本级/上级）或 人员 ID，任一匹配即可访问")
    void subcompanyOrPersonIdMatch() {
        // 100：人员维度按 ID 命中 + 分部维度按本级 9 命中 + 上级授权维度按链命中；未配置的 openDim 不可见
        assertEquals(List.of(subDim, personDim, subtreeDim), ids(dimensionService.authorized(PERSON_ID, false)));
        // 200：什么都没命中 → 空列表（前端据此展示引导页）
        assertEquals(List.of(), ids(dimensionService.authorized(PERSON_OTHER, false)));
    }

    @Test
    @DisplayName("管理员：全部启用维度可见（含未配置授权与别人授权的），禁用维度仍不可见")
    void adminSeesAllEnabled() {
        List<Long> visible = ids(dimensionService.authorized(PERSON_OTHER, true));

        assertEquals(List.of(openDim, subDim, personDim, subtreeDim), visible);
        assertFalse(visible.contains(disabledDim));
    }

    @Test
    @DisplayName("无登录身份（钩子/调试宽松口径）：返回全部启用维度")
    void anonymousStaysLenient() {
        List<Long> visible = ids(dimensionService.authorized(null, false));

        assertEquals(List.of(openDim, subDim, personDim, subtreeDim), visible);
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

    private void insertAuth(Long dimensionId, int authType, Long authId) {
        AssessDimensionAuth auth = new AssessDimensionAuth();
        auth.setDimensionId(dimensionId);
        auth.setAuthType(authType);
        auth.setAuthId(authId);
        authMapper.insert(auth);
    }

    private List<Long> ids(List<AssessDimensionVO> dimensions) {
        return dimensions.stream().map(AssessDimensionVO::getId).toList();
    }
}
