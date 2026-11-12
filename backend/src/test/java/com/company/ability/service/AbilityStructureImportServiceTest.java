package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AbilityStructureDTO;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.entity.PositionAbilityReqItem;
import com.company.ability.entity.UserAbilityReqItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AbilityStructureVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 能力结构导入（方案 14）：preview 只读、已存在跳过、等级描述与分值分离、权重与重名校验、
 * 岗位覆盖、幂等重试、跨维度拒绝。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力结构导入服务测试")
class AbilityStructureImportServiceTest {

    private static final long DIM = 3L;

    @Autowired private AbilityStructureImportService service;
    @Autowired private AbilityElementService elementService;
    @Autowired private AbilityElementLevelService levelService;
    @Autowired private PositionAbilityReqItemService positionItemService;
    @Autowired private UserAbilityReqItemService userItemService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DimensionContext.set(DIM);
        // 干净基线：本维度清空结构数据与人员/岗位配置
        jdbc.update("DELETE FROM uf_user_ability_req_item");
        jdbc.update("DELETE FROM uf_user_ability_req_new");
        jdbc.update("DELETE FROM uf_position_ability_req_item");
        jdbc.update("DELETE FROM uf_position_ability_req_new");
        jdbc.update("DELETE FROM uf_ability_element_level");
        jdbc.update("DELETE FROM uf_ability_element");
        jdbc.update("DELETE FROM uf_ability_category");
        jdbc.update("DELETE FROM uf_assess_level_score WHERE dimension_id = ?", DIM);
        jdbc.update("DELETE FROM uf_assess_period WHERE dimension_id = ?", DIM);
        jdbc.update("INSERT INTO uf_assess_level_score (company_id, dimension_id, level_no, level_desc, rate_value, score)"
                + " VALUES (1, ?, 1, '一级', 1, 2), (1, ?, 2, '二级', 2, 3), (1, ?, 3, '三级', 3, 5),"
                + " (1, ?, 4, '四级', 4, 7), (1, ?, 5, '五级', 5, 10)", DIM, DIM, DIM, DIM, DIM);
        jdbc.update("INSERT INTO uf_assess_period (company_id, period_name, year, status, dimension_id)"
                + " VALUES (1, '2026年度', 2026, 0, ?)", DIM);
    }

    @AfterEach
    void tearDown() {
        DimensionContext.clear();
    }

    // ==================== preview ====================

    @Test
    @DisplayName("preview：只读——不写任何表")
    void previewWritesNothing() {
        AbilityStructureDTO dto = minimal();
        int before = countAll();
        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.isCommitable()).isTrue();
        assertThat(countAll()).isEqualTo(before);
        assertThat(result.getCounts().getElementCreate()).isEqualTo(1);
        assertThat(result.getCounts().getLevelCreate()).isEqualTo(5);
    }

    @Test
    @DisplayName("preview：类别与要素已在库 → 标跳过且不新增")
    void existingSkipped() {
        AbilityStructureVO.CommitResult first = service.commit(minimal(), "s-1", null);
        assertThat(first.getCounts().getCategoryCreate()).isEqualTo(1);

        AbilityStructureVO.PreviewResult again = service.preview(minimal(), "s-1");
        assertThat(again.isCommitable()).isTrue();
        assertThat(again.getCounts().getCategoryCreate()).isZero();
        assertThat(again.getCounts().getCategorySkip()).isEqualTo(1);
        assertThat(again.getCounts().getElementSkip()).isEqualTo(1);
        assertThat(again.getCounts().getLevelCreate()).isZero();
    }

    @Test
    @DisplayName("commit：等级描述写入 level_requirement，分值字段保持为空（列错位回归）")
    void levelDescNotScore() {
        service.commit(minimal(), "s-1", null);
        List<AbilityElementLevel> levels = levelService.list(new LambdaQueryWrapper<AbilityElementLevel>()
                .orderByAsc(AbilityElementLevel::getLevel));
        assertThat(levels).hasSize(5);
        assertThat(levels.get(0).getLevelRequirement()).contains("了解");
        assertThat(levels.get(0).getLevelRequirement()).doesNotContain("2");
        // 分值取等级分值配置（2/3/5/7/10），与全系统同口径
        assertThat(levels.get(0).getScore()).isEqualByComparingTo("2");
        assertThat(levels.get(0).getLevelName()).isEqualTo("一级");
    }

    @Test
    @DisplayName("preview：等级描述取到了分值列 → 阻断（防止把 2/3/5 当描述写入）")
    void scoreAsDescBlocked() {
        AbilityStructureDTO dto = minimal();
        dto.getElements().get(0).getLevels().get(0).setDesc("2");
        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.isCommitable()).isFalse();
        assertThat(result.getCounts().getBlocked()).isEqualTo(1);
        assertThatThrownBy(() -> service.commit(dto, "s-1", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("阻断项");
    }

    @Test
    @DisplayName("preview：等级号不在当前维度等级分值配置 → 阻断")
    void unknownLevelBlocked() {
        AbilityStructureDTO dto = minimal();
        dto.getElements().get(0).getLevels().get(0).setLevelNo(9);
        assertThat(service.preview(dto, "s-1").isCommitable()).isFalse();
    }

    @Test
    @DisplayName("preview：等级描述为空 → 阻断")
    void emptyDescBlocked() {
        AbilityStructureDTO dto = minimal();
        dto.getElements().get(0).getLevels().get(0).setDesc("  ");
        assertThat(service.preview(dto, "s-1").isCommitable()).isFalse();
    }

    @Test
    @DisplayName("preview：要素声明的类别未在稿件中声明 → 阻断")
    void unknownCategoryBlocked() {
        AbilityStructureDTO dto = minimal();
        dto.getElements().get(0).setCategoryName("不存在的类别");
        assertThat(service.preview(dto, "s-1").isCommitable()).isFalse();
    }

    @Test
    @DisplayName("preview：同一稿内要素重复 → 阻断")
    void duplicateElementBlocked() {
        AbilityStructureDTO dto = minimal();
        java.util.List<AbilityStructureDTO.ElementRow> elements = new java.util.ArrayList<>(dto.getElements());
        elements.add(dto.getElements().get(0));
        dto.setElements(elements);
        assertThat(service.preview(dto, "s-1").isCommitable()).isFalse();
    }

    @Test
    @DisplayName("preview：维度与当前上下文不一致 → 409")
    void dimensionMismatch() {
        AbilityStructureDTO dto = minimal();
        dto.setDimensionId(DIM + 1);
        assertThatThrownBy(() -> service.preview(dto, "s-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("维度");
    }

    // ==================== 岗位 ====================

    @Test
    @DisplayName("岗位置位：类别权重越界 / 同类别不一致 → 阻断")
    void positionWeightBlocked() {
        seedHrm();
        AbilityStructureDTO dto = withPosition();
        java.util.List<AbilityStructureDTO.PositionItem> items =
                new java.util.ArrayList<>(dto.getPositionReqs().get(0).getItems());
        AbilityStructureDTO.PositionItem bad = new AbilityStructureDTO.PositionItem();
        bad.setCategoryName("安全环保");
        bad.setElementName("风险辨识");
        bad.setWeight(new BigDecimal("120"));
        items.set(0, bad);
        dto.getPositionReqs().get(0).setItems(items);
        assertThat(service.preview(dto, "s-1").isCommitable()).isFalse();
    }

    @Test
    @DisplayName("岗位：业务键查不到部门/岗位 → PENDING，不写入但不阻断整批")
    void positionPendingWhenUnmatched() {
        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.PositionReqRow row = new AbilityStructureDTO.PositionReqRow();
        row.setDepartmentName("查无此部门");
        row.setJobTitleName("查无此岗位");
        row.setItems(List.of(item("安全环保", "风险辨识", "100")));
        dto.setPositionReqs(List.of(row));

        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.isCommitable()).isTrue();
        assertThat(result.getCounts().getUnresolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("岗位：要素未在稿件或系统中声明 → PENDING")
    void positionUnknownElementPending() {
        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.PositionReqRow row = new AbilityStructureDTO.PositionReqRow();
        row.setDepartmentName("测试部门");
        row.setJobTitleName("测试岗位");
        row.setItems(List.of(item("安全环保", "不存在的要素", "100")));
        dto.setPositionReqs(List.of(row));
        seedHrm();
        assertThat(service.preview(dto, "s-1").getCounts().getUnresolved()).isEqualTo(1);
    }

    // ==================== 人员 ====================

    @Test
    @DisplayName("人员：同名多人 → PENDING，绝不取第一个")
    void ambiguousResourcePending() {
        AbilityStructureDTO dto = withUser("张三", "");
        assertThat(service.preview(dto, "s-1").getCounts().getUserPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("人员：工号命中优先于姓名")
    void resourceNoWins() {
        AbilityStructureDTO dto = withUser("张三", "A002");
        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.getCounts().getUserPending()).isZero();
        assertThat(result.getCounts().getUserCreate()).isEqualTo(1);
    }

    @Test
    @DisplayName("人员：查无此人 → PENDING")
    void unknownResourcePending() {
        AbilityStructureDTO dto = withUser("查无此人", "");
        assertThat(service.preview(dto, "s-1").getCounts().getUserPending()).isEqualTo(1);
    }

    @Test
    @DisplayName("人员：年度不存在或已关闭 → 阻断")
    void closedPeriodBlocked() {
        jdbc.update("UPDATE uf_assess_period SET status = 1 WHERE dimension_id = ?", DIM);
        AbilityStructureDTO dto = withUser("李四", "A001");
        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.isCommitable()).isFalse();
        assertThat(result.getCounts().getBlocked()).isGreaterThan(0);
    }

    @Test
    @DisplayName("人员：commit 写入要求明细（等级 ID、类别 ID 均有效）")
    void userItemsWritten() {
        AbilityStructureDTO dto = withUser("李四", "A001");
        service.commit(dto, "s-1", null);
        List<UserAbilityReqItem> items = userItemService.list();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getLevelId()).isNotNull();
        assertThat(items.get(0).getCategoryId()).isNotNull();
    }

    @Test
    @DisplayName("幂等：同稿重复 commit 不产生重复要求")
    void commitIdempotent() {
        AbilityStructureDTO dto = withUser("李四", "A001");
        service.commit(dto, "s-1", null);
        service.commit(dto, "s-1", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_user_ability_req_new", Integer.class)).isEqualTo(1);
        assertThat(userItemService.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uf_ability_element", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("人员：引用系统已有要素也能落库（要求可只覆盖部分要素）")
    void userReferencesExistingElement() {
        // 先导入一个要素
        service.commit(minimal(), "s-1", null);
        // 第二份稿件不再声明该要素，人员要求直接引用它
        AbilityStructureDTO dto = withUser("李四", "A001");
        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.isCommitable()).isTrue();
        service.commit(dto, "s-1", null);
        assertThat(userItemService.count()).isEqualTo(1);
    }

    // ==================== 岗位覆盖 ====================

    @Test
    @DisplayName("岗位：已存在默认保留，显式 overwrite 才替换明细")
    void positionOverwriteOnlyWhenAsked() {
        seedHrm();
        AbilityStructureDTO dto = withPosition();
        service.commit(dto, "s-1", null);
        long reqId = jdbc.queryForObject("SELECT id FROM uf_position_ability_req_new", Long.class);

        // 第二次：不勾覆盖 → 保留
        AbilityStructureVO.PreviewResult keep = service.preview(dto, "s-1");
        assertThat(keep.getCounts().getPositionKeep()).isEqualTo(1);

        // 第三次：显式覆盖 → 删旧明细重建
        dto.getPositionReqs().get(0).setOverwrite(true);
        AbilityStructureVO.PreviewResult over = service.preview(dto, "s-1");
        assertThat(over.getCounts().getPositionOverwrite()).isEqualTo(1);
        service.commit(dto, "s-1", null);
        List<PositionAbilityReqItem> items = positionItemService.list(new LambdaQueryWrapper<PositionAbilityReqItem>()
                .eq(PositionAbilityReqItem::getReqId, reqId));
        assertThat(items).hasSize(1);
    }

    // ==================== 别名表（名称不硬配的唯一合法例外） ====================

    @Test
    @DisplayName("别名：文件写「测试部」、系统叫「测试部门」，人工确认过一次即自动匹配并累加 hit_count")
    void aliasMatchesDepartmentAndJobTitle() {
        seedHrm();
        jdbc.update("DELETE FROM uf_assess_import_alias");
        jdbc.update("INSERT INTO uf_assess_import_alias (company_id, dimension_id, kind, source_name, target_id,"
                + " target_name, hit_count, confirmed_by) VALUES (1, ?, 'DEPARTMENT', ?, 901, '测试部门', 0, 1)",
                DIM, "测试部");
        jdbc.update("INSERT INTO uf_assess_import_alias (company_id, dimension_id, kind, source_name, target_id,"
                + " target_name, hit_count, confirmed_by) VALUES (1, ?, 'JOB_TITLE', ?, 902, '测试岗位', 0, 1)",
                DIM, "测试岗");

        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.PositionReqRow row = new AbilityStructureDTO.PositionReqRow();
        row.setDepartmentName("测试部");
        row.setJobTitleName("测试岗");
        row.setItems(List.of(item("安全环保", "风险辨识", "100")));
        dto.setPositionReqs(List.of(row));

        AbilityStructureVO.PreviewResult result = service.preview(dto, "s-1");
        assertThat(result.getCounts().getUnresolved()).isZero();
        assertThat(result.getCounts().getPositionCreate()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT hit_count FROM uf_assess_import_alias WHERE kind='DEPARTMENT'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("别名键按归一化写法存储（忽略空格/全角括号）；未命中别名时回落精确匹配")
    void aliasKeyNormalizedAndFallback() {
        seedHrm();
        jdbc.update("DELETE FROM uf_assess_import_alias");
        jdbc.update("INSERT INTO uf_assess_import_alias (company_id, dimension_id, kind, source_name, target_id,"
                + " target_name, hit_count, confirmed_by) VALUES (1, ?, 'DEPARTMENT', ?, 901, '测试部门', 0, 1)",
                DIM, "测试部业务");

        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.PositionReqRow row = new AbilityStructureDTO.PositionReqRow();
        row.setDepartmentName("测试部（业务）");   // 归一化后即「测试部业务」
        row.setJobTitleName("测试岗位");           // 无别名 → 精确匹配命中
        row.setItems(List.of(item("安全环保", "风险辨识", "100")));
        dto.setPositionReqs(List.of(row));

        assertThat(service.preview(dto, "s-1").getCounts().getUnresolved()).isZero();
    }

    // ==================== 工具 ====================

    private int countAll() {
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM uf_ability_category)"
                + " + (SELECT COUNT(*) FROM uf_ability_element)"
                + " + (SELECT COUNT(*) FROM uf_ability_element_level)"
                + " + (SELECT COUNT(*) FROM uf_position_ability_req_new)"
                + " + (SELECT COUNT(*) FROM uf_user_ability_req_new)", Integer.class);
    }

    private void seedHrm() {
        jdbc.update("MERGE INTO HrmDepartment (id, departmentname, departmentmark, canceled) KEY(id)"
                + " VALUES (901, '测试部门', '测试部门', '0')");
        jdbc.update("MERGE INTO HrmJobTitles (id, jobtitlemark, jobtitlename, canceled) KEY(id)"
                + " VALUES (902, '测试岗位', '测试岗位', '0')");
        jdbc.update("MERGE INTO HrmResource (id, lastname, loginid, departmentid, jobtitle) KEY(id)"
                + " VALUES (903, '李四', 'A001', 901, 902)");
        jdbc.update("MERGE INTO HrmResource (id, lastname, loginid, departmentid, jobtitle) KEY(id)"
                + " VALUES (904, '张三', 'A002', 901, 902)");
        jdbc.update("MERGE INTO HrmResource (id, lastname, loginid, departmentid, jobtitle) KEY(id)"
                + " VALUES (905, '张三', 'A003', 901, 902)");
    }

    private AbilityStructureDTO.PositionItem item(String category, String element, String weight) {
        AbilityStructureDTO.PositionItem item = new AbilityStructureDTO.PositionItem();
        item.setCategoryName(category);
        item.setElementName(element);
        item.setWeight(new BigDecimal(weight));
        return item;
    }

    private AbilityStructureDTO minimal() {
        AbilityStructureDTO dto = new AbilityStructureDTO();
        dto.setDimensionId(DIM);
        dto.setYear(2026);
        AbilityStructureDTO.CategoryRow c = new AbilityStructureDTO.CategoryRow();
        c.setName("安全环保");
        dto.setCategories(List.of(c));

        AbilityStructureDTO.ElementRow e = new AbilityStructureDTO.ElementRow();
        e.setCategoryName("安全环保");
        e.setName("风险辨识");
        e.setLevels(List.of(level(1), level(2), level(3), level(4), level(5)));
        dto.setElements(List.of(e));
        return dto;
    }

    private AbilityStructureDTO.LevelRow level(int no) {
        AbilityStructureDTO.LevelRow lv = new AbilityStructureDTO.LevelRow();
        lv.setLevelNo(no);
        lv.setDesc("了解第 " + no + " 级要求，能按清单识别常见风险");
        return lv;
    }

    private AbilityStructureDTO withPosition() {
        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.PositionReqRow row = new AbilityStructureDTO.PositionReqRow();
        row.setDepartmentName("测试部门");
        row.setJobTitleName("测试岗位");
        row.setItems(List.of(item("安全环保", "风险辨识", "100")));
        dto.setPositionReqs(List.of(row));
        return dto;
    }

    private AbilityStructureDTO withUser(String name, String no) {
        seedHrm();
        AbilityStructureDTO dto = minimal();
        AbilityStructureDTO.UserReqRow row = new AbilityStructureDTO.UserReqRow();
        row.setResourceName(name);
        row.setResourceNo(no);
        row.setDepartmentName("测试部门");
        AbilityStructureDTO.UserItem item = new AbilityStructureDTO.UserItem();
        item.setElementName("风险辨识");
        item.setLevelNo(3);
        row.setItems(List.of(item));
        dto.setUserReqs(List.of(row));
        return dto;
    }
}
