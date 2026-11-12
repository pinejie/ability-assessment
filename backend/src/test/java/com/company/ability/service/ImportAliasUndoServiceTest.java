package com.company.ability.service;

import com.company.ability.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 别名沉淀与批次撤销（方案 14 S3）。
 * 撤销是破坏性操作，重点覆盖三道闸：维度门、覆盖写闸、未写入闸。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("别名沉淀与批次撤销")
class ImportAliasUndoServiceTest {

    private static final long DIM = 3L;

    @Autowired private ImportAliasUndoService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM uf_assess_import_alias");
        jdbc.update("DELETE FROM uf_assess_import_batch_item");
        jdbc.update("DELETE FROM uf_assess_import_run");
        jdbc.update("DELETE FROM uf_ability_element_level");
        jdbc.update("DELETE FROM uf_ability_element");
        jdbc.update("DELETE FROM uf_ability_category");
        jdbc.update("DELETE FROM uf_user_ability_req_item");
        jdbc.update("DELETE FROM uf_user_ability_req_new");
        jdbc.update("MERGE INTO HrmDepartment (id, departmentname, departmentmark, canceled) KEY(id)"
                + " VALUES (901, '测试部门', '测试部门', '0')");
        jdbc.update("MERGE INTO HrmDepartment (id, departmentname, departmentmark, canceled) KEY(id)"
                + " VALUES (904, '安环部', '安环部', '0')");
        jdbc.update("MERGE INTO HrmJobTitles (id, jobtitlemark, jobtitlename, canceled) KEY(id)"
                + " VALUES (902, '测试岗位', '测试岗位', '0')");
    }

    // ==================== 别名 ====================

    @Test
    @DisplayName("确认别名：按归一化写法落库，同写法再确认走更新不重复")
    void confirmAliasesUpsert() {
        int saved = service.confirmAliases(List.of(
                new ImportAliasUndoService.AliasItem("DEPARTMENT", "安环部（安全）", 901, "测试部门")), DIM);
        assertThat(saved).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT source_name FROM uf_assess_import_alias WHERE kind='DEPARTMENT'", String.class))
                .isEqualTo("安环部安全");   // 全角括号与空白被归一化掉

        // 同写法再确认只剩一条，target 被更新
        service.confirmAliases(List.of(
                new ImportAliasUndoService.AliasItem("department", "安环部 安全", 904, "安环部")), DIM);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_assess_import_alias", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT target_id FROM uf_assess_import_alias WHERE kind='DEPARTMENT'", Long.class)).isEqualTo(904L);
    }

    @Test
    @DisplayName("确认别名：目标不存在 / 类型不支持 / 写法为空一律拒绝")
    void confirmAliasesRejects() {
        assertThatThrownBy(() -> service.confirmAliases(List.of(
                new ImportAliasUndoService.AliasItem("DEPARTMENT", "安环部", 999999, "鬼影部门")), DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("目标实体不存在");
        assertThatThrownBy(() -> service.confirmAliases(List.of(
                new ImportAliasUndoService.AliasItem("ELEMENT", "风险辨识", 901, "部门A")), DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持的别名类型");
        assertThatThrownBy(() -> service.confirmAliases(List.of(
                new ImportAliasUndoService.AliasItem("DEPARTMENT", "   ", 901, "测试部门")), DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThat(service.confirmAliases(List.of(), DIM)).isZero();
    }

    // ==================== 撤销 ====================

    @Test
    @DisplayName("撤销：只删台账里 CREATE 的实体，台账保留并标记已撤销")
    void undoCreateOnly() {
        long categoryId = insertCategory("安全环保");
        long elementId = insertElement(categoryId, "风险辨识");
        long levelId = insertLevel(elementId, 1);
        long runId = insertRun(DIM, "DONE");
        batchItem(runId, "CATEGORY", categoryId, "安全环保", "CREATE");
        batchItem(runId, "ELEMENT", elementId, "风险辨识", "CREATE");
        batchItem(runId, "LEVEL", levelId, "风险辨识 L1", "CREATE");
        // 台账按写入顺序记（类别在前），撤销必须按依赖倒序删，否则撞 uf_ability_element 的外键
        jdbc.update("INSERT INTO uf_ability_element_level (dimension_id, element_id, level, level_name,"
                + " level_requirement, score) VALUES (?, ?, 2, 'L2', '描述', 3)", DIM, elementId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_element_level WHERE element_id = ?",
                Integer.class, elementId)).isEqualTo(2);

        ImportAliasUndoService.UndoResult r = service.undo(runId, false, DIM);

        assertThat(r.removed()).isEqualTo(3);
        assertThat(r.deleted()).containsEntry("CATEGORY", 1).containsEntry("ELEMENT", 1).containsEntry("LEVEL", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_element", Integer.class)).isZero();
        // 台账不删（审计留痕），run 打标记
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_assess_import_batch_item", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT fail_reason FROM uf_assess_import_run WHERE id = ?",
                String.class, runId)).contains("已撤销");
    }

    @Test
    @DisplayName("撤销：含覆盖写（UPDATE）默认整体拒绝，force 才放行")
    void undoRefusesOverwriteWithoutForce() {
        long categoryId = insertCategory("安全环保");
        long runId = insertRun(DIM, "DONE");
        batchItem(runId, "CATEGORY", categoryId, "安全环保", "CREATE");
        batchItem(runId, "POSITION_REQ", 4242, "测试部门 · 测试岗位", "UPDATE");

        assertThatThrownBy(() -> service.undo(runId, false, DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("覆盖写");
        // 拒绝时不做事：一个都不删
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_category", Integer.class)).isEqualTo(1);

        ImportAliasUndoService.UndoResult r = service.undo(runId, true, DIM);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_category", Integer.class)).isZero();
    }

    @Test
    @DisplayName("撤销：跨维度 / 未写入 / 无台账 三种情况都不许动数据")
    void undoGuards() {
        long categoryId = insertCategory("安全环保");
        long otherDimRun = insertRun(4L, "DONE");
        batchItem(otherDimRun, "CATEGORY", categoryId, "安全环保", "CREATE");
        assertThatThrownBy(() -> service.undo(otherDimRun, true, DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于当前维度");

        long parsingRun = insertRun(DIM, "PARSING");
        batchItem(parsingRun, "CATEGORY", categoryId, "安全环保", "CREATE");
        assertThatThrownBy(() -> service.undo(parsingRun, true, DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未写入");

        assertThatThrownBy(() -> service.undo(999999L, true, DIM))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有写入记录");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_category", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销：岗位/人员要求先删明细再删主表，重复撤销幂等")
    void undoPositionAndUserReq() {
        long runId = insertRun(DIM, "DONE");
        jdbc.update("INSERT INTO uf_position_ability_req_new (id, department_id, job_title_id, dimension_id)"
                + " VALUES (7001, 901, 902, ?)", DIM);
        jdbc.update("INSERT INTO uf_position_ability_req_item (req_id, element_id, weight) VALUES (7001, 1, 100)");
        jdbc.update("INSERT INTO uf_user_ability_req_new (id, company_id, resource_id, assess_year, dimension_id)"
                + " VALUES (7002, 1, 903, 2026, ?)", DIM);
        jdbc.update("INSERT INTO uf_user_ability_req_item (req_id, element_id, level_id, score)"
                + " VALUES (7002, 1, 1, 2)");
        batchItem(runId, "POSITION_REQ", 7001, "测试部门 · 测试岗位", "CREATE");
        batchItem(runId, "USER_REQ", 7002, "李四", "CREATE");

        ImportAliasUndoService.UndoResult r = service.undo(runId, false, DIM);

        assertThat(r.removed()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_position_ability_req_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_position_ability_req_new", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_user_ability_req_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_user_ability_req_new", Integer.class)).isZero();

        // 已删过的再撤一次：不报错，删 0 条
        assertThat(service.undo(runId, false, DIM).removed()).isZero();
    }

    // ==================== 工具 ====================

    private long insertCategory(String name) {
        jdbc.update("INSERT INTO uf_ability_category (dimension_id, category_name, sort) VALUES (?, ?, 1)",
                DIM, name);
        return jdbc.queryForObject("SELECT MAX(id) FROM uf_ability_category", Long.class);
    }

    private long insertElement(long categoryId, String name) {
        jdbc.update("INSERT INTO uf_ability_element (dimension_id, category_id, element_name) VALUES (?, ?, ?)",
                DIM, categoryId, name);
        return jdbc.queryForObject("SELECT MAX(id) FROM uf_ability_element", Long.class);
    }

    private long insertLevel(long elementId, int levelNo) {
        jdbc.update("INSERT INTO uf_ability_element_level (dimension_id, element_id, level, level_name,"
                + " level_requirement, score) VALUES (?, ?, ?, ?, '描述', 2)", DIM, elementId, levelNo, "L" + levelNo);
        return jdbc.queryForObject("SELECT MAX(id) FROM uf_ability_element_level", Long.class);
    }

    private long insertRun(long dimensionId, String status) {
        jdbc.update("INSERT INTO uf_assess_import_run (company_id, dimension_id, session_id, file_path, file_name,"
                + " status, prompt_version, created_by) VALUES (1, ?, 's-1', '/tmp/a.xlsx', 'a.xlsx', ?, 'imp-v1', 900)",
                dimensionId, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM uf_assess_import_run", Long.class);
    }

    private void batchItem(long batchId, String type, long entityId, String key, String action) {
        jdbc.update("INSERT INTO uf_assess_import_batch_item (batch_id, entity_type, entity_id, natural_key, action)"
                + " VALUES (?, ?, ?, ?, ?)", batchId, type, entityId, key, action);
    }
}
