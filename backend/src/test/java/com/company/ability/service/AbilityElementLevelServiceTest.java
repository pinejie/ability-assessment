package com.company.ability.service;

import com.company.ability.dto.AbilityElementLevelCreateDTO;
import com.company.ability.dto.AbilityElementLevelUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AbilityElementLevelVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力要素等级配置服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力要素等级配置服务测试")
class AbilityElementLevelServiceTest {

    @Autowired
    private AbilityElementLevelService abilityElementLevelService;

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    @Autowired
    private AbilityElementService abilityElementService;

    private Long elementId;

    @BeforeEach
    void setUp() {
        // 清理数据
        abilityElementLevelService.list().forEach(l -> abilityElementLevelService.deleteLevel(l.getId()));
        abilityElementService.remove(null);
        abilityCategoryService.remove(null);

        // 创建测试类别和要素
        var categoryDTO = new com.company.ability.dto.AbilityCategoryCreateDTO();
        categoryDTO.setCategoryName("测试类别");
        categoryDTO.setStatus(1);
        Long categoryId = abilityCategoryService.createCategory(categoryDTO);

        var elementDTO = new com.company.ability.dto.AbilityElementCreateDTO();
        elementDTO.setElementName("测试要素");
        elementDTO.setCategoryId(categoryId);
        elementId = abilityElementService.createElement(elementDTO);
    }

    @Test
    @DisplayName("应该创建等级配置")
    void shouldCreateLevel() {
        // Arrange
        AbilityElementLevelCreateDTO dto = new AbilityElementLevelCreateDTO();
        dto.setElementId(elementId);
        dto.setLevel(3);
        dto.setLevelName("高级");
        dto.setLevelRequirement("高级要求描述");
        dto.setScore(new BigDecimal("90"));

        // Act
        Long id = abilityElementLevelService.createLevel(dto);

        // Assert
        assertNotNull(id);
        AbilityElementLevelVO vo = abilityElementLevelService.getLevelById(id);
        assertEquals(elementId, vo.getElementId());
        assertEquals(3, vo.getLevel());
        assertEquals("高级", vo.getLevelName());
        assertEquals("高级要求描述", vo.getLevelRequirement());
    }

    @Test
    @DisplayName("应该更新等级配置")
    void shouldUpdateLevel() {
        // Arrange
        AbilityElementLevelCreateDTO createDTO = new AbilityElementLevelCreateDTO();
        createDTO.setElementId(elementId);
        createDTO.setLevel(3);
        createDTO.setLevelName("高级");
        createDTO.setLevelRequirement("原始要求");
        createDTO.setScore(new BigDecimal("90"));
        Long id = abilityElementLevelService.createLevel(createDTO);

        AbilityElementLevelUpdateDTO updateDTO = new AbilityElementLevelUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setLevelName("更新后的高级");
        updateDTO.setLevelRequirement("更新后的要求");
        updateDTO.setScore(new BigDecimal("95"));

        // Act
        abilityElementLevelService.updateLevel(updateDTO);

        // Assert
        AbilityElementLevelVO vo = abilityElementLevelService.getLevelById(id);
        assertEquals("更新后的高级", vo.getLevelName());
        assertEquals("更新后的要求", vo.getLevelRequirement());
    }

    @Test
    @DisplayName("应该删除等级配置")
    void shouldDeleteLevel() {
        // Arrange
        AbilityElementLevelCreateDTO dto = new AbilityElementLevelCreateDTO();
        dto.setElementId(elementId);
        dto.setLevel(3);
        dto.setLevelName("高级");
        dto.setLevelRequirement("高级要求");
        dto.setScore(new BigDecimal("90"));
        Long id = abilityElementLevelService.createLevel(dto);

        // Act
        abilityElementLevelService.deleteLevel(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            abilityElementLevelService.getLevelById(id);
        });
    }

    @Test
    @DisplayName("应该分页查询等级配置")
    void shouldPageLevelsByElementId() {
        // Arrange
        for (int i = 1; i <= 5; i++) {
            AbilityElementLevelCreateDTO dto = new AbilityElementLevelCreateDTO();
            dto.setElementId(elementId);
            dto.setLevel(i);
            dto.setLevelName("等级" + i);
            dto.setLevelRequirement("要求" + i);
            dto.setScore(new BigDecimal(60 + i * 10));
            abilityElementLevelService.createLevel(dto);
        }

        // Act
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(1);
        pageRequest.setPageSize(10);
        PageResult<AbilityElementLevelVO> result = abilityElementLevelService.pageLevelsByElementId(elementId, pageRequest);

        // Assert
        assertEquals(5, result.getList().size());
        assertEquals(5L, result.getTotal());
    }

    @Test
    @DisplayName("应该在等级已存在时抛出异常")
    void shouldThrowExceptionWhenLevelExists() {
        // Arrange
        AbilityElementLevelCreateDTO dto = new AbilityElementLevelCreateDTO();
        dto.setElementId(elementId);
        dto.setLevel(3);
        dto.setLevelName("高级");
        dto.setLevelRequirement("高级要求");
        dto.setScore(new BigDecimal("90"));
        abilityElementLevelService.createLevel(dto);

        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            abilityElementLevelService.createLevel(dto);
        });
    }

    @Test
    @DisplayName("应该在等级不存在时抛出异常")
    void shouldThrowExceptionWhenLevelNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            abilityElementLevelService.getLevelById(999L);
        });
    }
}
