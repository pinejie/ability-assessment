package com.company.ability.service;

import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AbilityElementVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力要素服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力要素服务测试")
class AbilityElementServiceTest {

    @Autowired
    private AbilityElementService abilityElementService;

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    private Long categoryId;

    @BeforeEach
    void setUp() {
        // 清理数据
        abilityElementService.remove(null);
        abilityCategoryService.remove(null);

        // 创建测试类别
        var categoryDTO = new com.company.ability.dto.AbilityCategoryCreateDTO();
        categoryDTO.setCategoryName("测试类别");
        categoryDTO.setStatus(1);
        categoryId = abilityCategoryService.createCategory(categoryDTO);
    }

    @Test
    @DisplayName("应该创建能力要素")
    void shouldCreateAbilityElement() {
        // Arrange
        AbilityElementCreateDTO dto = new AbilityElementCreateDTO();
        dto.setElementName("测试要素");
        dto.setElementCode("TEST-001");
        dto.setCategoryId(categoryId);
        dto.setDescription("测试描述");
        dto.setStatus(1);
        dto.setSort(1);

        // Act
        Long id = abilityElementService.createElement(dto);

        // Assert
        assertNotNull(id);
        AbilityElementVO vo = abilityElementService.getElementById(id);
        assertEquals("测试要素", vo.getElementName());
        assertEquals("TEST-001", vo.getElementCode());
        assertEquals(categoryId, vo.getCategoryId());
    }

    @Test
    @DisplayName("应该更新能力要素")
    void shouldUpdateAbilityElement() {
        // Arrange
        AbilityElementCreateDTO createDTO = new AbilityElementCreateDTO();
        createDTO.setElementName("原始名称");
        createDTO.setCategoryId(categoryId);
        Long id = abilityElementService.createElement(createDTO);

        AbilityElementUpdateDTO updateDTO = new AbilityElementUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setElementName("更新后的名称");
        updateDTO.setDescription("更新后的描述");

        // Act
        abilityElementService.updateElement(updateDTO);

        // Assert
        AbilityElementVO vo = abilityElementService.getElementById(id);
        assertEquals("更新后的名称", vo.getElementName());
        assertEquals("更新后的描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该删除能力要素")
    void shouldDeleteAbilityElement() {
        // Arrange
        AbilityElementCreateDTO dto = new AbilityElementCreateDTO();
        dto.setElementName("要删除的要素");
        dto.setCategoryId(categoryId);
        Long id = abilityElementService.createElement(dto);

        // Act
        abilityElementService.deleteElement(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            abilityElementService.getElementById(id);
        });
    }

    @Test
    @DisplayName("应该查询所有能力要素")
    void shouldListAllAbilityElements() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            AbilityElementCreateDTO dto = new AbilityElementCreateDTO();
            dto.setElementName("要素" + i);
            dto.setCategoryId(categoryId);
            abilityElementService.createElement(dto);
        }

        // Act
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(1);
        pageRequest.setPageSize(10);
        PageResult<AbilityElementVO> result = abilityElementService.pageElements(pageRequest);

        // Assert
        assertEquals(3, result.getList().size());
        assertEquals(3L, result.getTotal());
    }

    @Test
    @DisplayName("应该根据类别ID查询能力要素")
    void shouldListElementsByCategoryId() {
        // Arrange
        for (int i = 1; i <= 2; i++) {
            AbilityElementCreateDTO dto = new AbilityElementCreateDTO();
            dto.setElementName("要素" + i);
            dto.setCategoryId(categoryId);
            abilityElementService.createElement(dto);
        }

        // Act
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(1);
        pageRequest.setPageSize(10);
        PageResult<AbilityElementVO> result = abilityElementService.pageElementsByCategoryId(categoryId, pageRequest);

        // Assert
        assertEquals(2, result.getList().size());
    }

    @Test
    @DisplayName("应该在要素不存在时抛出异常")
    void shouldThrowExceptionWhenElementNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            abilityElementService.getElementById(999L);
        });
    }
}
