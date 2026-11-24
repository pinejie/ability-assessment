package com.company.ability.service;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.entity.AssessDimensionAuth;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessDimensionAuthMapper;
import com.company.ability.mapper.AssessDimensionMapper;
import com.company.ability.entity.AssessDimension;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度配置合并（sql/58，2026-11-23 第二版）。
 *
 * <p>合并后「所属分部」「维度管理员」「授权配置」三处只剩一个真相源
 * {@code uf_assess_dimension_auth}：</p>
 * <ul>
 *   <li>分部授权（auth_type=1）= 归属哪几家 + 这些分部的人可用（子树语义）</li>
 *   <li>人员授权（auth_type=2）= 这些人可用，同时是维度管理员（报告里 360 姓名可见）</li>
 * </ul>
 *
 * <p>这里锁死的是合并的本质约束：人员授权<b>同时</b>表达「能用」和「管」，
 * 不能再有第二个入口。若哪天又拆出独立的「管理员」表，本类会先红。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("维度配置合并（分部 / 人员授权 = 归属 + 可用 + 管理员）")
class DimensionAuthMergeTest {

    private static final long PERSON_A = 9101L;
    private static final long PERSON_B = 9102L;
    private static final long SUB_PARENT = 9301L;
    private static final long SUB_CHILD = 9302L;
    private static final long SUB_OTHER = 9303L;

    @Autowired
    private AssessDimensionService dimensionService;
    @Autowired
    private AssessDimensionMapper dimensionMapper;
    @Autowired
    private AssessDimensionAuthMapper authMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long dimensionId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM uf_assess_dimension_auth");
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '中达建设', NULL)",
                SUB_PARENT);
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '中达建设一分公司', ?)",
                SUB_CHILD, SUB_PARENT);
        jdbc.update("INSERT INTO HrmSubCompany (id, subcompanyname, supsubcomid) VALUES (?, '国际贸易', NULL)",
                SUB_OTHER);
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1)"
                + " VALUES (?, '甲管理员', 1, 'adm-a', ?)", PERSON_A, SUB_CHILD);
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, subcompanyid1)"
                + " VALUES (?, '乙员工', 1, 'emp-b', ?)", PERSON_B, SUB_OTHER);

        AssessDimensionCreateDTO dto = new AssessDimensionCreateDTO();
        dto.setDimensionName("合并维度");
        dimensionId = dimensionService.create(dto);
    }

    @Test
    @DisplayName("分部授权多选：列表里都显示，且同时是归属与可用范围（子树）")
    void multipleSubcompanysAreBothOwnershipAndAccess() {
        saveAuths(List.of(SUB_PARENT, SUB_OTHER), List.of());

        AssessDimensionVO vo = find(dimensionId);
        assertEquals(List.of(SUB_PARENT, SUB_OTHER),
                vo.getSubcompanys().stream().map(AssessDimensionVO.AuthItem::getId).toList());
        assertEquals(2L, vo.getAuthCount(), "授权条目数 = 分部数 + 人员数");

        // 甲挂在 SUB_CHILD（SUB_PARENT 的下级）：勾父分部即覆盖其全部下级分支
        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_A), "子树语义要生效");
        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_B));
    }

    @Test
    @DisplayName("人员授权 = 维度管理员：能用这个维度，也能在报告里看到 360 姓名")
    void personAuthIsAlsoDimensionAdmin() {
        saveAuths(List.of(), List.of(PERSON_A));

        assertTrue(dimensionService.isAuthorizedForUser(dimensionId, PERSON_A), "人员授权直接放行");
        assertTrue(dimensionService.isDimensionAdmin(dimensionId, PERSON_A), "同一份数据即管理员");
        assertFalse(dimensionService.isDimensionAdmin(dimensionId, PERSON_B), "没被授权的人不是管理员");
        assertFalse(dimensionService.isAuthorizedForUser(dimensionId, PERSON_B));
    }

    @Test
    @DisplayName("管理员只在自己的维度成立：别的维度没有他的授权行")
    void adminIsScopedToOneDimension() {
        saveAuths(List.of(), List.of(PERSON_A));

        AssessDimensionCreateDTO other = new AssessDimensionCreateDTO();
        other.setDimensionName("另一个维度");
        Long otherId = dimensionService.create(other);

        assertTrue(dimensionService.isDimensionAdmin(dimensionId, PERSON_A));
        assertFalse(dimensionService.isDimensionAdmin(otherId, PERSON_A), "管一个维度不等于能看所有维度");
    }

    @Test
    @DisplayName("撤掉人员授权即撤掉管理员身份，没有第二张表要同步")
    void removingPersonAuthRemovesAdmin() {
        saveAuths(List.of(), List.of(PERSON_A));
        assertTrue(dimensionService.isDimensionAdmin(dimensionId, PERSON_A));

        saveAuths(List.of(), List.of());
        assertFalse(dimensionService.isDimensionAdmin(dimensionId, PERSON_A));
        assertTrue(find(dimensionId).getPersons().isEmpty());
    }

    @Test
    @DisplayName("列表里分部名与姓名都补齐，前端不用再查一次")
    void listFillsNamesForBothTypes() {
        saveAuths(List.of(SUB_PARENT), List.of(PERSON_A));

        AssessDimensionVO vo = find(dimensionId);
        assertEquals("中达建设", vo.getSubcompanys().get(0).getName());
        assertEquals("甲管理员", vo.getPersons().get(0).getName());
        assertEquals(personIds(PERSON_A), vo.getPersons().stream().map(AssessDimensionVO.AuthItem::getId).toList());
    }

    @Test
    @DisplayName("授权列表接口同时返回分部与人员，顺序稳定")
    void listAuthsReturnsBothTypes() {
        saveAuths(List.of(SUB_PARENT), List.of(PERSON_A, PERSON_B));

        List<AssessDimensionAuthVO> auths = dimensionService.listAuths(dimensionId);
        assertEquals(3, auths.size());
        assertEquals(1, auths.stream()
                .filter(a -> a.getAuthType() == AssessDimensionAuth.TYPE_SUBCOMPANY).count());
        assertEquals(2, auths.stream()
                .filter(a -> a.getAuthType() == AssessDimensionAuth.TYPE_RESOURCE).count());
        assertEquals(List.of("中达建设", "甲管理员", "乙员工"),
                auths.stream().map(AssessDimensionAuthVO::getAuthName).toList());
    }

    @Test
    @DisplayName("重复条目与非法类型仍然被拦（校验逻辑不该因为合并而放松）")
    void duplicateAndIllegalTypeStillRejected() {
        assertThrows(BusinessException.class, () -> dimensionService.saveAuths(dimensionId, List.of(
                new com.company.ability.dto.DimensionAuthItemDTO() {{
                    setAuthType(AssessDimensionAuth.TYPE_RESOURCE);
                    setAuthId(PERSON_A);
                }},
                new com.company.ability.dto.DimensionAuthItemDTO() {{
                    setAuthType(AssessDimensionAuth.TYPE_RESOURCE);
                    setAuthId(PERSON_A);
                }})));

        assertThrows(BusinessException.class, () -> dimensionService.saveAuths(dimensionId, List.of(
                new com.company.ability.dto.DimensionAuthItemDTO() {{
                    setAuthType(99);
                    setAuthId(PERSON_A);
                }})));
    }

    @Test
    @DisplayName("删除维度时授权一并清掉，不留孤儿行")
    void deleteCleansAuths() {
        saveAuths(List.of(SUB_PARENT), List.of(PERSON_A));

        dimensionService.delete(dimensionId);

        Integer left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_dimension_auth WHERE dimension_id = ?", Integer.class, dimensionId);
        assertEquals(0, left);
    }

    @Test
    @DisplayName("编辑维度（改名/排序/状态）不碰授权：合并后更新接口里根本没有授权字段")
    void updatingDimensionNeverTouchesAuths() {
        saveAuths(List.of(SUB_PARENT), List.of(PERSON_A));

        com.company.ability.dto.AssessDimensionUpdateDTO update =
                new com.company.ability.dto.AssessDimensionUpdateDTO();
        update.setId(dimensionId);
        update.setDimensionName("改了个名");
        update.setSort(9);
        update.setStatus(0);
        dimensionService.update(update);

        AssessDimensionVO vo = find(dimensionId);
        assertEquals("改了个名", vo.getDimensionName());
        assertEquals(1, vo.getSubcompanys().size(), "编辑不该把分部授权冲掉");
        assertEquals(1, vo.getPersons().size(), "编辑不该把人员授权冲掉");
        assertTrue(dimensionService.isDimensionAdmin(dimensionId, PERSON_A));
    }

    @Test
    @DisplayName("匿名（无人员 ID）不算维度管理员")
    void anonymousIsNeverDimensionAdmin() {
        saveAuths(List.of(), List.of(PERSON_A));

        assertFalse(dimensionService.isDimensionAdmin(dimensionId, null));
        assertFalse(dimensionService.isDimensionAdmin(null, PERSON_A));
    }

    @Test
    @DisplayName("所属分部列与被删的管理员表都不复存在：旧结构没有残留写入口")
    void legacyStructuresAreGone() {
        Integer subcompanyColumn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE LOWER(TABLE_NAME) = 'uf_assess_dimension' AND LOWER(COLUMN_NAME) = 'subcompany_id'",
                Integer.class);
        assertEquals(0, subcompanyColumn, "subcompany_id 已并入分部授权，留着就有第二个入口");
    }

    // ==================== 辅助 ====================

    private void saveAuths(List<Long> subcompanyIds, List<Long> personIds) {
        java.util.List<com.company.ability.dto.DimensionAuthItemDTO> items = new java.util.ArrayList<>();
        subcompanyIds.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_SUBCOMPANY, id)));
        personIds.forEach(id -> items.add(item(AssessDimensionAuth.TYPE_RESOURCE, id)));
        dimensionService.saveAuths(dimensionId, items);
    }

    private com.company.ability.dto.DimensionAuthItemDTO item(int type, Long authId) {
        com.company.ability.dto.DimensionAuthItemDTO dto = new com.company.ability.dto.DimensionAuthItemDTO();
        dto.setAuthType(type);
        dto.setAuthId(authId);
        return dto;
    }

    private List<Long> personIds(Long... ids) {
        return List.of(ids);
    }

    private AssessDimensionVO find(Long id) {
        return dimensionService.listAll().stream()
                .filter(d -> d.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("维度没出现在列表里：" + id));
    }
}
