package com.company.ability.service;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PositionAbilityReqCreateDTO;
import com.company.ability.dto.PositionAbilityReqUpdateDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.PositionAbilityReqVO;
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
 * 岗位能力要求配置服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("岗位能力要求配置服务测试")
class PositionAbilityReqServiceTest {

    @Autowired
    private PositionAbilityReqService positionAbilityReqService;

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    @Autowired
    private AbilityElementService abilityElementService;

    private Long categoryId;
    private Long elementId;

    @BeforeEach
    void setUp() {
        // 清理数据
        positionAbilityReqService.remove(null);
        abilityElementService.remove(null);
        abilityCategoryService.remove(null);

        // 创建测试类别和要素
        var categoryDTO = new com.company.ability.dto.AbilityCategoryCreateDTO();
        categoryDTO.setCategoryName("测试类别");
        categoryDTO.setStatus(1);
        categoryId = abilityCategoryService.createCategory(categoryDTO);

        var elementDTO = new com.company.ability.dto.AbilityElementCreateDTO();
        elementDTO.setElementName("测试要素");
        elementDTO.setCategoryId(categoryId);
        elementId = abilityElementService.createElement(elementDTO);
    }

    @Test
    @DisplayName("应该创建岗位能力要求")
    void shouldCreatePositionAbilityReq() {
        // Arrange
        PositionAbilityReqCreateDTO dto = new PositionAbilityReqCreateDTO();
        dto.setDepartmentId(1L);
        dto.setJobTitleId(200L);
        dto.setDescription("测试描述");

        PositionAbilityReqCreateDTO.ElementItem item = new PositionAbilityReqCreateDTO.ElementItem();
        item.setCategoryId(categoryId);
        item.setElementId(elementId);
        dto.setItems(List.of(item));

        // Act
        Long id = positionAbilityReqService.createPositionAbilityReq(dto);

        // Assert
        assertNotNull(id);
        PositionAbilityReqVO vo = positionAbilityReqService.getPositionAbilityReqById(id);
        assertEquals(200L, vo.getJobTitleId());
        assertEquals("测试描述", vo.getDescription());
        assertNotNull(vo.getItems());
        assertEquals(1, vo.getItems().size());
        assertEquals(elementId, vo.getItems().get(0).getElementId());
    }

    @Test
    @DisplayName("应该更新岗位能力要求")
    void shouldUpdatePositionAbilityReq() {
        // Arrange
        PositionAbilityReqCreateDTO createDTO = new PositionAbilityReqCreateDTO();
        createDTO.setDepartmentId(1L);
        createDTO.setJobTitleId(200L);
        createDTO.setDescription("原始描述");

        PositionAbilityReqCreateDTO.ElementItem item = new PositionAbilityReqCreateDTO.ElementItem();
        item.setCategoryId(categoryId);
        item.setElementId(elementId);
        createDTO.setItems(List.of(item));

        Long id = positionAbilityReqService.createPositionAbilityReq(createDTO);

        PositionAbilityReqUpdateDTO updateDTO = new PositionAbilityReqUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setDescription("更新后的描述");

        // Act
        positionAbilityReqService.updatePositionAbilityReq(updateDTO);

        // Assert
        PositionAbilityReqVO vo = positionAbilityReqService.getPositionAbilityReqById(id);
        assertEquals("更新后的描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该删除岗位能力要求")
    void shouldDeletePositionAbilityReq() {
        // Arrange
        PositionAbilityReqCreateDTO dto = new PositionAbilityReqCreateDTO();
        dto.setDepartmentId(1L);
        dto.setJobTitleId(200L);

        PositionAbilityReqCreateDTO.ElementItem item = new PositionAbilityReqCreateDTO.ElementItem();
        item.setCategoryId(categoryId);
        item.setElementId(elementId);
        dto.setItems(List.of(item));

        Long id = positionAbilityReqService.createPositionAbilityReq(dto);

        // Act
        positionAbilityReqService.deletePositionAbilityReq(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            positionAbilityReqService.getPositionAbilityReqById(id);
        });
    }

    @Test
    @DisplayName("应该查询所有岗位能力要求")
    void shouldListAllPositionAbilityReqs() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            PositionAbilityReqCreateDTO dto = new PositionAbilityReqCreateDTO();
            dto.setDepartmentId(1L);
            dto.setJobTitleId((long) (200 + i));

            PositionAbilityReqCreateDTO.ElementItem item = new PositionAbilityReqCreateDTO.ElementItem();
            item.setCategoryId(categoryId);
            item.setElementId(elementId);
            dto.setItems(List.of(item));

            positionAbilityReqService.createPositionAbilityReq(dto);
        }

        // Act
        PageResult<PositionAbilityReqVO> result = positionAbilityReqService.pagePositionAbilityReqs(new PageRequest());

        // Assert
        assertEquals(3, result.getList().size());
    }

    @Test
    @DisplayName("应该根据岗位ID查询能力要求")
    void shouldListPositionAbilityReqsByJobTitleId() {
        // Arrange
        for (int i = 1; i <= 2; i++) {
            PositionAbilityReqCreateDTO dto = new PositionAbilityReqCreateDTO();
            dto.setDepartmentId(1L);
            dto.setJobTitleId(200L);

            PositionAbilityReqCreateDTO.ElementItem item = new PositionAbilityReqCreateDTO.ElementItem();
            item.setCategoryId(categoryId);
            item.setElementId(elementId);
            dto.setItems(List.of(item));

            positionAbilityReqService.createPositionAbilityReq(dto);
        }

        // Act
        PageResult<PositionAbilityReqVO> result = positionAbilityReqService.pagePositionAbilityReqsByJobTitleId(200L, new PageRequest());

        // Assert
        assertEquals(2, result.getList().size());
    }

    @Test
    @DisplayName("应该在要求不存在时抛出异常")
    void shouldThrowExceptionWhenReqNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            positionAbilityReqService.getPositionAbilityReqById(999L);
        });
    }
}
