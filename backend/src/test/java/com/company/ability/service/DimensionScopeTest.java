package com.company.ability.service;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.entity.AssessDimensionAuth;
import com.company.ability.vo.DimensionScopeVO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度的组织范围（currentScope，2026-11-23）。
 *
 * <p>「所属分部」除了决定谁能进维度，还决定维度里的人能选到哪些部门 ——
 * 岗位只能挂在授权分部下的部门上，被测人也只能从这些部门里挑。
 * 本类锁死四条：子树展开、排除扣减、无授权=不限、部门跟着分部走。</p>
 *
 * <p>口径必须和 {@code isAuthorizedFor} 的分部部分一致：那边勾父含子、
 * 排除也含子，这边算「哪些部门在范围内」，两者走的是同一棵树，不能各算各的。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("维度组织范围：授权分部 → 部门")
class DimensionScopeTest {

    /** 一级分部（根） */
    private static final long SUB_ROOT = 9601L;
    /** 二级子公司 */
    private static final long SUB_CHILD = 9602L;
    /** 二级子公司下再挂的分支 */
    private static final long SUB_GRANDCHILD = 9603L;
    /** 另一个二级子公司，用来说明「排除只影响自己那一支」 */
    private static final long SUB_SIBLING = 9604L;
    /** 完全无关的分部 */
    private static final long SUB_UNRELATED = 9605L;

    private static final long DEPT_ROOT = 9701L;
    private static final long DEPT_CHILD = 9702L;
    private static final long DEPT_GRANDCHILD = 9703L;
    private static final long DEPT_SIBLING = 9704L;
    private static final long DEPT_UNRELATED = 9705L;
    /** 已注销部门：范围里不该出现（选它建岗位会被后端拒，出现在选择器里只会误导） */
    private static final long DEPT_CANCELED = 9706L;

    @Autowired
    private AssessDimensionService dimensionService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long dimensionId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM uf_assess_dimension_auth");
        insertSubcompany(SUB_ROOT, "本部", null);
        insertSubcompany(SUB_CHILD, "子公司", SUB_ROOT);
        insertSubcompany(SUB_GRANDCHILD, "孙公司", SUB_CHILD);
        insertSubcompany(SUB_SIBLING, "兄弟公司", SUB_ROOT);
        insertSubcompany(SUB_UNRELATED, "无关公司", null);

        insertDepartment(DEPT_ROOT, "本部-综合办", null, SUB_ROOT, false);
        insertDepartment(DEPT_CHILD, "子公司-生产部", null, SUB_CHILD, false);
        insertDepartment(DEPT_GRANDCHILD, "孙公司-仓储部", null, SUB_GRANDCHILD, false);
        insertDepartment(DEPT_SIBLING, "兄弟公司-销售部", null, SUB_SIBLING, false);
        insertDepartment(DEPT_UNRELATED, "无关公司-财务部", null, SUB_UNRELATED, false);
        insertDepartment(DEPT_CANCELED, "本部-已撤部门", null, SUB_ROOT, true);

        AssessDimensionCreateDTO dto = new AssessDimensionCreateDTO();
        dto.setDimensionName("本部维度");
        dimensionId = dimensionService.create(dto);
    }

    @Test
    @DisplayName("勾父含全部下级：授权根分部，整棵树的部门都在范围内")
    void grantedRootCoversSubtree() {
        saveAuths(List.of(SUB_ROOT), List.of());

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertTrue(scope.isRestricted());
        assertEquals(List.of(DEPT_ROOT, DEPT_CHILD, DEPT_GRANDCHILD, DEPT_SIBLING),
                sorted(scope.getDepartmentIds()),
                "下级分部的部门要一起进来，否则建岗位时选不到孙子公司的部门");
        assertFalse(scope.getDepartmentIds().contains(DEPT_UNRELATED), "无关分部不该混进来");
        assertFalse(scope.getDepartmentIds().contains(DEPT_CANCELED), "已注销部门不进选择器");
    }

    @Test
    @DisplayName("只勾某一级下级：父分部的部门不进来，只出这一个分支")
    void grantedChildOnlyNarrowsToThatBranch() {
        saveAuths(List.of(SUB_CHILD), List.of());

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertEquals(List.of(DEPT_CHILD, DEPT_GRANDCHILD), sorted(scope.getDepartmentIds()),
                "勾子公司要含它的孙公司，但不该带上父分部的部门");
    }

    @Test
    @DisplayName("排除分部做减法：扣掉那一支的部门，兄弟分支照常保留")
    void excludedBranchIsSubtracted() {
        saveAuths(List.of(SUB_ROOT), List.of(SUB_GRANDCHILD));

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertFalse(scope.getDepartmentIds().contains(DEPT_GRANDCHILD), "被排除的分部下的部门要扣掉");
        assertTrue(scope.getDepartmentIds().contains(DEPT_CHILD), "排除只影响自己那一支");
        assertTrue(scope.getDepartmentIds().contains(DEPT_SIBLING));
    }

    @Test
    @DisplayName("排除父分部时，它下面即使还有被授权的子分部也被扣掉")
    void exclusionOfParentWinsOverGrantedChild() {
        // 授权根分部 + 单独授权孙子分部，同时排除子公司：孙子在公司子树里，应被扣掉。
        // 结论不能取决于处理顺序，所以先算排除子树、再在展开后的集合上做差。
        saveAuths(List.of(SUB_ROOT, SUB_GRANDCHILD), List.of(SUB_CHILD));

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertEquals(List.of(DEPT_ROOT, DEPT_SIBLING), sorted(scope.getDepartmentIds()),
                "排除子树含下级，孙子分部的部门也不该出现");
        assertEquals(List.of(SUB_ROOT, SUB_SIBLING), sorted(scope.getSubcompanyIds()));
    }

    @Test
    @DisplayName("没配授权分部 = 不限（restricted=false），不能表达成「一个部门都没有」")
    void noGrantedSubcompanyMeansUnrestricted() {
        saveAuths(List.of(), List.of(SUB_UNRELATED));

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertFalse(scope.isRestricted(),
                "只有排除、没有授权时谁都不能用，但范围本身是「不限」而非「空部门集」");
        assertTrue(scope.getDepartmentIds().isEmpty());
    }

    @Test
    @DisplayName("只配了人员授权、没有分部授权：同样算不限")
    void personOnlyAuthIsUnrestricted() {
        saveAuths(List.of(), List.of());

        DimensionScopeVO scope = dimensionService.currentScope(dimensionId);
        assertFalse(scope.isRestricted(), "这类维度只有被点名的人能进，组织范围不做收窄");
    }

    @Test
    @DisplayName("维度 ID 为空时不报错，按不限处理")
    void nullDimensionIsUnrestricted() {
        DimensionScopeVO scope = dimensionService.currentScope(null);
        assertFalse(scope.isRestricted());
        assertTrue(scope.getDepartmentIds().isEmpty());
    }

    @Test
    @DisplayName("改授权后范围立即跟着变，不需要重启或缓存刷新")
    void scopeFollowsAuthChanges() {
        saveAuths(List.of(SUB_UNRELATED), List.of());
        assertEquals(List.of(DEPT_UNRELATED), sorted(dimensionService.currentScope(dimensionId).getDepartmentIds()));

        saveAuths(List.of(SUB_SIBLING), List.of());
        assertEquals(List.of(DEPT_SIBLING), sorted(dimensionService.currentScope(dimensionId).getDepartmentIds()));
    }

    // ==================== 辅助 ====================

    private List<Long> sorted(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        copy.sort(Long::compareTo);
        return copy;
    }

    private void saveAuths(List<Long> granted, List<Long> excluded) {
        List<DimensionAuthItemDTO> items = new ArrayList<>();
        granted.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_SUBCOMPANY, id)));
        excluded.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY, id)));
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

    private void insertDepartment(long id, String name, Long parentId, long subcompanyId, boolean canceled) {
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, supdepid, subcompanyid1, canceled)"
                + " VALUES (?, ?, ?, ?, ?, ?)", id, name, name, parentId, subcompanyId, canceled ? 1 : 0);
    }
}
