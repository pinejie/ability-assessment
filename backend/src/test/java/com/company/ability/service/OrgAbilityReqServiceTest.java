package com.company.ability.service;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.OrgAbilityReqCreateDTO;
import com.company.ability.dto.OrgAbilityReqUpdateDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.OrgAbilityReqVO;
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
 * 部门能力要求配置服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("部门能力要求配置服务测试")
class OrgAbilityReqServiceTest {

    @Autowired
    private OrgAbilityReqService orgAbilityReqService;

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    @Autowired
    private AbilityElementService abilityElementService;

    private Long elementId;

    @BeforeEach
    void setUp() {
        // 清理数据
        orgAbilityReqService.remove(null);
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
    @DisplayName("应该创建部门能力要求")
    void shouldCreateOrgAbilityReq() {
        // Arrange
        OrgAbilityReqCreateDTO dto = new OrgAbilityReqCreateDTO();
        dto.setDepartmentId(100L);
        dto.setElementId(elementId);
        dto.setDescription("测试描述");

        // Act
        Long id = orgAbilityReqService.createOrgAbilityReq(dto);

        // Assert
        assertNotNull(id);
        OrgAbilityReqVO vo = orgAbilityReqService.getOrgAbilityReqById(id);
        assertEquals(100L, vo.getDepartmentId());
        assertEquals(elementId, vo.getElementId());
        assertEquals("测试描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该更新部门能力要求")
    void shouldUpdateOrgAbilityReq() {
        // Arrange
        OrgAbilityReqCreateDTO createDTO = new OrgAbilityReqCreateDTO();
        createDTO.setDepartmentId(100L);
        createDTO.setElementId(elementId);
        createDTO.setDescription("原始描述");
        Long id = orgAbilityReqService.createOrgAbilityReq(createDTO);

        OrgAbilityReqUpdateDTO updateDTO = new OrgAbilityReqUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setDescription("更新后的描述");

        // Act
        orgAbilityReqService.updateOrgAbilityReq(updateDTO);

        // Assert
        OrgAbilityReqVO vo = orgAbilityReqService.getOrgAbilityReqById(id);
        assertEquals("更新后的描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该删除部门能力要求")
    void shouldDeleteOrgAbilityReq() {
        // Arrange
        OrgAbilityReqCreateDTO dto = new OrgAbilityReqCreateDTO();
        dto.setDepartmentId(100L);
        dto.setElementId(elementId);
        Long id = orgAbilityReqService.createOrgAbilityReq(dto);

        // Act
        orgAbilityReqService.deleteOrgAbilityReq(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            orgAbilityReqService.getOrgAbilityReqById(id);
        });
    }

    @Test
    @DisplayName("应该查询所有部门能力要求")
    void shouldListAllOrgAbilityReqs() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            OrgAbilityReqCreateDTO dto = new OrgAbilityReqCreateDTO();
            dto.setDepartmentId((long) (100 + i));
            dto.setElementId(elementId);
            orgAbilityReqService.createOrgAbilityReq(dto);
        }

        // Act
        PageResult<OrgAbilityReqVO> result = orgAbilityReqService.pageOrgAbilityReqs(new PageRequest());

        // Assert
        assertEquals(3, result.getList().size());
    }

    @Test
    @DisplayName("应该根据部门ID查询能力要求")
    void shouldListOrgAbilityReqsByDepartmentId() {
        // Arrange
        for (int i = 1; i <= 2; i++) {
            OrgAbilityReqCreateDTO dto = new OrgAbilityReqCreateDTO();
            dto.setDepartmentId(100L);
            dto.setElementId(elementId);
            orgAbilityReqService.createOrgAbilityReq(dto);
        }

        // Act
        PageResult<OrgAbilityReqVO> result = orgAbilityReqService.pageOrgAbilityReqsByDepartmentId(100L, new PageRequest());

        // Assert
        assertEquals(2, result.getList().size());
    }

    @Test
    @DisplayName("应该在要求不存在时抛出异常")
    void shouldThrowExceptionWhenReqNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            orgAbilityReqService.getOrgAbilityReqById(999L);
        });
    }
}
