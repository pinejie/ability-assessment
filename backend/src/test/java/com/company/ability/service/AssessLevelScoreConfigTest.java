package com.company.ability.service;

import com.company.ability.dto.AssessConfigDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AssessConfigVO;
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

import static com.company.ability.h2test.SheetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 等级分值（等级唯一定义源）动态化测试：
 * 数量可配、四字段校验、被引用等级的删除/改号保护、孤儿要素等级行清理。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("等级分值动态配置测试")
class AssessLevelScoreConfigTest {

    @Autowired
    private AssessConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanAll(jdbc);
    }

    private AssessConfigDTO.LevelScoreSaveDTO saveDto(AssessConfigDTO.LevelScoreItem... items) {
        AssessConfigDTO.LevelScoreSaveDTO dto = new AssessConfigDTO.LevelScoreSaveDTO();
        dto.setCompanyId(1L);
        dto.setItems(List.of(items));
        return dto;
    }

    private AssessConfigDTO.LevelScoreItem item(int levelNo, String desc, int rateValue, int score) {
        AssessConfigDTO.LevelScoreItem i = new AssessConfigDTO.LevelScoreItem();
        i.setLevelNo(levelNo);
        i.setLevelDesc(desc);
        i.setRateValue(rateValue);
        i.setScore(BigDecimal.valueOf(score));
        return i;
    }

    @Test
    @DisplayName("等级数量动态：保存 3 个等级后读取即 3 行，四字段完整")
    void dynamicLevelCount() {
        configService.saveLevelScores(saveDto(
                item(1, "入门", 1, 2),
                item(2, "熟练", 3, 5),
                item(3, "精通", 5, 10)));

        List<AssessConfigVO.LevelScoreVO> list = configService.getLevelScores(1L);
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).getLevelNo());
        assertEquals("熟练", list.get(1).getLevelDesc());
        assertEquals(3, list.get(1).getRateValue());
        assertEquals(0, new BigDecimal("10").compareTo(list.get(2).getScore()));
    }

    @Test
    @DisplayName("校验：空列表 / 评价值重复 / 描述缺失被拒")
    void validationRejects() {
        assertThrows(BusinessException.class, () -> configService.saveLevelScores(saveDto()));

        BusinessException dup = assertThrows(BusinessException.class, () ->
                configService.saveLevelScores(saveDto(item(1, "甲", 1, 2), item(2, "乙", 1, 5))));
        assertTrue(dup.getMessage().contains("重复"));

        assertThrows(BusinessException.class, () ->
                configService.saveLevelScores(saveDto(item(1, " ", 1, 2))));
    }

    @Test
    @DisplayName("删除保护：等级号被人员能力要求引用时禁止删除")
    void removeReferencedLevelBlocked() {
        insertLevelScores(jdbc); // 1..5
        long categoryId = insertCategory(jdbc, "类别A");
        long elementId = insertElement(jdbc, "要素A", categoryId, 1);
        // addReqItem 创建 2 级等级行并被要求明细引用
        long reqId = insertRequirement(jdbc, RESOURCE_ID, YEAR);
        addReqItem(jdbc, reqId, elementId, categoryId);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                configService.saveLevelScores(saveDto(
                        item(1, "一级", 1, 2), item(3, "三级", 3, 5),
                        item(4, "四级", 4, 7), item(5, "五级", 5, 10))));
        assertTrue(ex.getMessage().contains("等级 2"));
        assertTrue(ex.getMessage().contains("禁止删除或改号"));
    }

    @Test
    @DisplayName("删除放行：未引用等级可删，且其孤儿要素等级行被清理")
    void removeUnreferencedLevelCleansOrphans() {
        insertLevelScores(jdbc);
        long categoryId = insertCategory(jdbc, "类别A");
        long elementId = insertElement(jdbc, "要素A", categoryId, 1);
        // 3 级等级行存在但无任何要求明细引用
        jdbc.update("INSERT INTO uf_ability_element_level (element_id, level, level_name, level_requirement, score, dimension_id) " +
                "VALUES (?, 3, '三级', '要求文本', 60, 1)", elementId);

        configService.saveLevelScores(saveDto(
                item(1, "一级", 1, 2), item(2, "二级", 2, 3),
                item(4, "四级", 4, 7), item(5, "五级", 5, 10)));

        Integer orphan = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_ability_element_level WHERE level = 3", Integer.class);
        assertEquals(0, orphan, "被删等级的孤儿要素等级行应被清理");
        assertEquals(4, configService.getLevelScores(1L).size());
    }

    @Test
    @DisplayName("改评价号保护：评价值已有页面评级数据时禁止删除或修改")
    void changeRateWithExistingRatingsBlocked() {
        insertLevelScores(jdbc);
        long categoryId = insertCategory(jdbc, "类别A");
        long elementId = insertElement(jdbc, "要素A", categoryId, 1);
        insertPeriod(jdbc, YEAR);
        jdbc.update("INSERT INTO uf_assess_sheet (company_id, period_id, resource_id, dimension_id, status, need_recalc, " +
                "req_changed, client_count) VALUES (1, (SELECT id FROM uf_assess_period WHERE year = ?), ?, 1, 1, 0, 0, 0)",
                YEAR, RESOURCE_ID);
        long sheetId = jdbc.queryForObject("SELECT id FROM uf_assess_sheet WHERE resource_id = ?", Long.class, RESOURCE_ID);
        jdbc.update("INSERT INTO uf_assess_sheet_row (sheet_id, category_id, element_id, element_name, required_level_no, " +
                "active, dimension_id) VALUES (?, ?, ?, '要素A', 3, 1, 1)", sheetId, categoryId, elementId);
        // 领导对要素按 4 级评过价（level_no 存评价值 4）
        jdbc.update("INSERT INTO uf_assess_sheet_rating (sheet_id, element_id, rater_type, rater_resource_id, level_no, " +
                "source, dimension_id) VALUES (?, ?, 'LEADER', 88, 4, 'FLOW', 1)", sheetId, elementId);

        // 新配置里评价值 4 被改成 6（旧值 4 消失）→ 应被拦截
        List<AssessConfigDTO.LevelScoreItem> items = List.of(
                item(1, "一级", 1, 2), item(2, "二级", 2, 3), item(3, "三级", 3, 5),
                item(5, "五级", 5, 10), item(4, "四级", 6, 7));
        AssessConfigDTO.LevelScoreSaveDTO dto = new AssessConfigDTO.LevelScoreSaveDTO();
        dto.setCompanyId(1L);
        dto.setItems(items);

        BusinessException ex = assertThrows(BusinessException.class, () -> configService.saveLevelScores(dto));
        assertTrue(ex.getMessage().contains("对应的评价 4"));
    }

    @Test
    @DisplayName("描述/分值随便改：等级号与评价号不变时不受引用保护约束")
    void descAndScoreEditableWithoutRefs() {
        insertLevelScores(jdbc);
        configService.saveLevelScores(saveDto(
                item(1, "新知识级", 1, 99), item(2, "新规范级", 2, 3), item(3, "新技巧级", 3, 5),
                item(4, "新技能级", 4, 7), item(5, "新变通级", 5, 10)));

        List<AssessConfigVO.LevelScoreVO> list = configService.getLevelScores(1L);
        assertEquals("新知识级", list.get(0).getLevelDesc());
        assertEquals(0, new BigDecimal("99").compareTo(list.get(0).getScore()));
    }
}
