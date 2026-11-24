package com.company.ability.service;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.entity.AssessDimensionAuth;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessDimensionAuthVO;
import com.company.ability.vo.AssessDimensionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度授权的排除分部（sql/59，2026-11-23 第三版）。
 *
 * <p>场景：国际贸易整棵树归维度 A，但它的二级子公司「中达科技」要单独一个维度 B。
 * 授权是「勾父含子」的正向规则，没有减法，所以必须能显式挖掉一支。</p>
 *
 * <p>本类锁死三条语义，改判定逻辑前先看这里：</p>
 * <ol>
 *   <li>排除生效：授权父分部、排除子分部时，子分部的人进不来</li>
 *   <li>排除含下级：排除一个分部，它下面<b>新设</b>的分支同样进不来</li>
 *   <li>人员授权压过排除：手工点进来的人照常可用</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("维度授权：排除分部")
class DimensionExcludedSubcompanyTest {

    /** 国际贸易（根） */
    private static final long SUB_INTL = 9401L;
    /** 中达科技（国际贸易的二级子公司，要单独一个维度） */
    private static final long SUB_TECH = 9402L;
    /** 中达科技下面的新设分支 */
    private static final long SUB_TECH_BRANCH = 9403L;
    /** 国际-行政（国际贸易的另一个二级子公司，仍归本维度） */
    private static final long SUB_ADMIN = 9404L;
    /** 与国际贸易无上下级关系 */
    private static final long SUB_UNRELATED = 9405L;

    private static final long PERSON_IN_TECH = 9501L;
    private static final long PERSON_IN_TECH_BRANCH = 9502L;
    private static final long PERSON_IN_ADMIN = 9503L;
    private static final long PERSON_UNRELATED = 9504L;

    @Autowired
    private AssessDimensionService dimensionService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long dimensionId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM uf_assess_dimension_auth");
        insertSubcompany(SUB_INTL, "国际贸易", null);
        insertSubcompany(SUB_TECH, "中达科技", SUB_INTL);
        insertSubcompany(SUB_TECH_BRANCH, "中达科技-上海", SUB_TECH);
        insertSubcompany(SUB_ADMIN, "国际-行政", SUB_INTL);
        insertSubcompany(SUB_UNRELATED, "中达材料", null);

        insertPerson(PERSON_IN_TECH, "科技员工", SUB_TECH);
        insertPerson(PERSON_IN_TECH_BRANCH, "科技分支员工", SUB_TECH_BRANCH);
        insertPerson(PERSON_IN_ADMIN, "行政员工", SUB_ADMIN);
        insertPerson(PERSON_UNRELATED, "材料员工", SUB_UNRELATED);

        AssessDimensionCreateDTO dto = new AssessDimensionCreateDTO();
        dto.setDimensionName("国际贸易维度");
        dimensionId = dimensionService.create(dto);
    }

    @Test
    @DisplayName("排除生效：授权国际贸易、排除中达科技 → 科技的人进不来，行政的人照常进")
    void excludedSubcompanyIsDenied() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_TECH), List.of());

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_ADMIN),
                "同属国际贸易、没被排除的分支照常可用");
        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH),
                "被排除的子公司不能再借父分部的授权进来");
    }

    @Test
    @DisplayName("排除含下级：排除中达科技，它下面新设的分支也进不来")
    void exclusionCoversSubtree() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_TECH), List.of());

        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH_BRANCH),
                "排除要含下级，否则科技每新设一个分支就漏一个人进来");
    }

    @Test
    @DisplayName("人员授权压过排除：被排除分部里的人手工加进来就可用")
    void personAuthOverridesExclusion() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_TECH), List.of(PERSON_IN_TECH));

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH),
                "手工点进来的人比按组织划的范围意图更强");
        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH_BRANCH),
                "但只放行这一个人，不算把整个被排除的分部放回来");
    }

    @Test
    @DisplayName("人员授权压在排除之前判断，不受授权行顺序影响")
    void personAuthWinsRegardlessOfRowOrder() {
        // 先写排除再写人员，去掉「排除项排在前面就先 return false」这种随行序复现的坑
        saveAuthsItems(List.of(
                item(AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY, SUB_TECH),
                item(AssessDimensionAuth.TYPE_SUBCOMPANY, SUB_INTL),
                item(AssessDimensionAuth.TYPE_RESOURCE, PERSON_IN_TECH)));

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH));
    }

    @Test
    @DisplayName("排除项在不相关的分部上不误伤：排除没命中的分部，不影响别的人")
    void exclusionOfUnrelatedSubcompanyDoesNotHurt() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_UNRELATED), List.of());

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_ADMIN),
                "排除的是范围外的分部，对范围内的人不该有影响");
    }

    @Test
    @DisplayName("只配排除、不配任何授权分部：等于谁都不能用（排除不创造权限）")
    void exclusionAloneGrantsNothing() {
        saveAuths(List.of(), List.of(SUB_TECH), List.of());

        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_ADMIN));
        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH));
    }

    @Test
    @DisplayName("列表与授权接口同时返回授权分部与排除分部，名字都补齐")
    void listShowsBothGrantAndExclusion() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_TECH), List.of());

        AssessDimensionVO vo = dimensionService.listAll().stream()
                .filter(d -> d.getId().equals(dimensionId))
                .findFirst().orElseThrow();
        assertEquals(List.of("国际贸易"),
                vo.getSubcompanys().stream().map(AssessDimensionVO.AuthItem::getName).toList());
        assertEquals(List.of("中达科技"),
                vo.getExcludedSubcompanys().stream().map(AssessDimensionVO.AuthItem::getName).toList(),
                "列表要展示成「国际贸易（不含 中达科技）」");
        assertEquals(2L, vo.getAuthCount(), "授权条目数含排除项");

        List<AssessDimensionAuthVO> auths = dimensionService.listAuths(dimensionId);
        assertEquals(1, auths.stream()
                .filter(a -> a.getAuthType() == AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY).count());
        assertEquals("中达科技", auths.stream()
                .filter(a -> a.getAuthType() == AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY)
                .findFirst().orElseThrow().getAuthName());
    }

    @Test
    @DisplayName("撤掉排除后，被排除分支的人重新可用")
    void removingExclusionRestoresAccess() {
        saveAuths(List.of(SUB_INTL), List.of(SUB_TECH), List.of());
        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH));

        saveAuths(List.of(SUB_INTL), List.of(), List.of());
        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_TECH));
    }

    @Test
    @DisplayName("同一个分部不能既授权又排除，保存时直接拒绝")
    void grantAndExcludeSameSubcompanyIsRejected() {
        // 授权 A 又排除 A，结果是一个人都进不来 —— 这份授权等于没配，
        // 只可能是误操作，不能靠「排除优先」这种隐式规则去猜，必须当场报错。
        assertThrows(BusinessException.class,
                () -> saveAuths(List.of(SUB_TECH), List.of(SUB_TECH), List.of()),
                "同一分部既授权又排除应当被拒绝");
    }

    @Test
    @DisplayName("排除项在授权范围之外时不算冲突（授权树下级、排除表亲分部）")
    void exclusionOfUnrelatedSubcompanyIsNotConflict() {
        // 只校验「同一个 id」，不做子树交集判断：授权国际贸易、排除中达材料
        // 虽然中达材料根本不在范围内（watch 会清掉），但这是两个不同的 id，
        // 保存层不拦，避免把正常配置误判成冲突。
        saveAuths(List.of(SUB_INTL), List.of(SUB_UNRELATED), List.of());

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_IN_ADMIN));
    }

    // ==================== 辅助 ====================

    private void saveAuths(List<Long> granted, List<Long> excluded, List<Long> persons) {
        List<DimensionAuthItemDTO> items = new ArrayList<>();
        granted.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_SUBCOMPANY, id)));
        excluded.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY, id)));
        persons.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_RESOURCE, id)));
        saveAuthsItems(items);
    }

    private void saveAuthsItems(List<DimensionAuthItemDTO> items) {
        dimensionService.saveAuths(dimensionId, items);
    }

    private DimensionAuthItemDTO item(int authType, Long authId) {
        DimensionAuthItemDTO dto = new DimensionAuthItemDTO();
        dto.setAuthType(authType);
        dto.setAuthId(authId);
        return dto;
    }

    private void insertSubcompany(long id, String name, Long parentId) {
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, ?, ?)",
                id, name, parentId);
    }

    private void insertPerson(long id, String name, long subcompanyId) {
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1)"
                + " VALUES (?, ?, 1, ?, ?)", id, name, "u" + id, subcompanyId);
    }
}
