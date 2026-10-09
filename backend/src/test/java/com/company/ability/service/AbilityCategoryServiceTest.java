package com.company.ability.service;

import com.company.ability.dto.AbilityCategoryCreateDTO;
import com.company.ability.dto.AbilityCategoryUpdateDTO;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.AbilityCategoryVO;
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
 * 能力类别服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("能力类别服务测试")
class AbilityCategoryServiceTest {

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    @BeforeEach
    void setUp() {
        // 每个测试前清理数据
        abilityCategoryService.remove(null);
    }

    @Test
    @DisplayName("应该创建能力类别")
    void shouldCreateAbilityCategory() {
        // Arrange
        AbilityCategoryCreateDTO dto = new AbilityCategoryCreateDTO();
        dto.setCategoryName("测试类别");
        dto.setDescription("测试描述");
        dto.setStatus(1);
        dto.setSort(1);

        // Act
        Long id = abilityCategoryService.createCategory(dto);

        // Assert
        assertNotNull(id);
        AbilityCategoryVO vo = abilityCategoryService.getCategoryById(id);
        assertEquals("测试类别", vo.getCategoryName());
        assertEquals("测试描述", vo.getDescription());
        assertEquals(1, vo.getStatus());
    }

    @Test
    @DisplayName("应该更新能力类别")
    void shouldUpdateAbilityCategory() {
        // Arrange
        AbilityCategoryCreateDTO createDTO = new AbilityCategoryCreateDTO();
        createDTO.setCategoryName("原始名称");
        Long id = abilityCategoryService.createCategory(createDTO);

        AbilityCategoryUpdateDTO updateDTO = new AbilityCategoryUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setCategoryName("更新后的名称");
        updateDTO.setDescription("更新后的描述");

        // Act
        abilityCategoryService.updateCategory(updateDTO);

        // Assert
        AbilityCategoryVO vo = abilityCategoryService.getCategoryById(id);
        assertEquals("更新后的名称", vo.getCategoryName());
        assertEquals("更新后的描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该删除能力类别")
    void shouldDeleteAbilityCategory() {
        // Arrange
        AbilityCategoryCreateDTO dto = new AbilityCategoryCreateDTO();
        dto.setCategoryName("要删除的类别");
        Long id = abilityCategoryService.createCategory(dto);

        // Act
        abilityCategoryService.deleteCategory(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            abilityCategoryService.getCategoryById(id);
        });
    }

    @Test
    @DisplayName("应该查询所有能力类别")
    void shouldListAllCategories() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            AbilityCategoryCreateDTO dto = new AbilityCategoryCreateDTO();
            dto.setCategoryName("类别" + i);
            abilityCategoryService.createCategory(dto);
        }

        // Act
        List<AbilityCategoryVO> list = abilityCategoryService.listAllCategories();

        // Assert
        assertEquals(3, list.size());
    }

    @Test
    @DisplayName("应该在类别不存在时抛出异常")
    void shouldThrowExceptionWhenCategoryNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            abilityCategoryService.getCategoryById(999L);
        });
    }
}
