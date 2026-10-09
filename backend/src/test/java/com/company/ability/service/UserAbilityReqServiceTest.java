package com.company.ability.service;

import com.company.ability.dto.UserAbilityReqCreateDTO;
import com.company.ability.dto.UserAbilityReqUpdateDTO;
import com.company.ability.dto.AbilityElementLevelCreateDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.UserAbilityReqVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 人员能力要求配置服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("人员能力要求配置服务测试")
class UserAbilityReqServiceTest {

    @Autowired
    private UserAbilityReqService userAbilityReqService;

    @Autowired
    private AbilityCategoryService abilityCategoryService;

    @Autowired
    private AbilityElementService abilityElementService;

    @Autowired
    private AbilityElementLevelService abilityElementLevelService;

    private Long elementId;
    private Long levelId;

    @BeforeEach
    void setUp() {
        // 清理数据
        userAbilityReqService.remove(null);
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

        // 创建测试等级
        var levelDTO = new AbilityElementLevelCreateDTO();
        levelDTO.setElementId(elementId);
        levelDTO.setLevel(3);
        levelDTO.setLevelName("高级");
        levelDTO.setLevelRequirement("高级要求");
        levelDTO.setScore(new BigDecimal("90"));
        levelId = abilityElementLevelService.createLevel(levelDTO);
    }

    @Test
    @DisplayName("应该创建人员能力要求（一对多）")
    void shouldCreateUserAbilityReq() {
        // Arrange
        UserAbilityReqCreateDTO dto = new UserAbilityReqCreateDTO();
        dto.setResourceId(1001L);

        List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
        UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
        config.setElementId(elementId);
        config.setLevelId(levelId);
        config.setScore(new BigDecimal("90"));
        config.setDescription("测试描述");
        configs.add(config);
        dto.setElementConfigs(configs);

        // Act
        Long id = userAbilityReqService.createUserAbilityReq(dto);

        // Assert
        assertNotNull(id);
        List<UserAbilityReqVO> list = userAbilityReqService.listUserAbilityReqsByResourceId(1001L);
        assertEquals(1, list.size());
        UserAbilityReqVO vo = list.get(0);
        assertEquals(1001L, vo.getResourceId());
        assertEquals(elementId, vo.getElementId());
        assertEquals(levelId, vo.getLevelId());
    }

    @Test
    @DisplayName("应该更新人员能力要求")
    void shouldUpdateUserAbilityReq() {
        // Arrange
        UserAbilityReqCreateDTO createDTO = new UserAbilityReqCreateDTO();
        createDTO.setResourceId(1001L);

        List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
        UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
        config.setElementId(elementId);
        config.setLevelId(levelId);
        config.setScore(new BigDecimal("90"));
        config.setDescription("原始描述");
        configs.add(config);
        createDTO.setElementConfigs(configs);

        Long id = userAbilityReqService.createUserAbilityReq(createDTO);
        Long reqId = userAbilityReqService.listUserAbilityReqsByResourceId(1001L).get(0).getId();

        UserAbilityReqUpdateDTO updateDTO = new UserAbilityReqUpdateDTO();
        updateDTO.setId(reqId);
        updateDTO.setDescription("更新后的描述");

        // Act
        userAbilityReqService.updateUserAbilityReq(updateDTO);

        // Assert
        UserAbilityReqVO vo = userAbilityReqService.getUserAbilityReqById(reqId);
        assertEquals("更新后的描述", vo.getDescription());
    }

    @Test
    @DisplayName("应该删除人员能力要求")
    void shouldDeleteUserAbilityReq() {
        // Arrange
        UserAbilityReqCreateDTO createDTO = new UserAbilityReqCreateDTO();
        createDTO.setResourceId(1001L);

        List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
        UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
        config.setElementId(elementId);
        config.setLevelId(levelId);
        config.setScore(new BigDecimal("90"));
        configs.add(config);
        createDTO.setElementConfigs(configs);

        userAbilityReqService.createUserAbilityReq(createDTO);
        Long reqId = userAbilityReqService.listUserAbilityReqsByResourceId(1001L).get(0).getId();

        // Act
        userAbilityReqService.deleteUserAbilityReq(reqId);

        // Assert
        assertThrows(BusinessException.class, () -> {
            userAbilityReqService.getUserAbilityReqById(reqId);
        });
    }

    @Test
    @DisplayName("应该根据人员ID删除所有能力要求")
    void shouldDeleteUserAbilityReqsByResourceId() {
        // Arrange
        UserAbilityReqCreateDTO createDTO = new UserAbilityReqCreateDTO();
        createDTO.setResourceId(1001L);

        List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
        UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
        config.setElementId(elementId);
        config.setLevelId(levelId);
        config.setScore(new BigDecimal("90"));
        configs.add(config);
        createDTO.setElementConfigs(configs);

        userAbilityReqService.createUserAbilityReq(createDTO);

        // Act
        userAbilityReqService.deleteUserAbilityReqsByResourceId(1001L);

        // Assert
        List<UserAbilityReqVO> list = userAbilityReqService.listUserAbilityReqsByResourceId(1001L);
        assertEquals(0, list.size());
    }

    @Test
    @DisplayName("应该查询所有人员能力要求")
    void shouldListAllUserAbilityReqs() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            UserAbilityReqCreateDTO dto = new UserAbilityReqCreateDTO();
            dto.setResourceId((long) (1000 + i));

            List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
            UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
            config.setElementId(elementId);
            config.setLevelId(levelId);
            config.setScore(new BigDecimal("90"));
            configs.add(config);
            dto.setElementConfigs(configs);

            userAbilityReqService.createUserAbilityReq(dto);
        }

        // Act
        List<UserAbilityReqVO> list = userAbilityReqService.listAllUserAbilityReqs();

        // Assert
        assertEquals(3, list.size());
    }

    @Test
    @DisplayName("应该根据人员ID查询能力要求")
    void shouldListUserAbilityReqsByResourceId() {
        // Arrange
        for (int i = 1; i <= 2; i++) {
            UserAbilityReqCreateDTO dto = new UserAbilityReqCreateDTO();
            dto.setResourceId(1001L);

            List<UserAbilityReqCreateDTO.UserAbilityElementConfig> configs = new ArrayList<>();
            UserAbilityReqCreateDTO.UserAbilityElementConfig config = new UserAbilityReqCreateDTO.UserAbilityElementConfig();
            config.setElementId(elementId);
            config.setLevelId(levelId);
            config.setScore(new BigDecimal("90"));
            config.setDescription("描述" + i);
            configs.add(config);
            dto.setElementConfigs(configs);

            userAbilityReqService.createUserAbilityReq(dto);
        }

        // Act
        List<UserAbilityReqVO> list = userAbilityReqService.listUserAbilityReqsByResourceId(1001L);

        // Assert
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("应该在要求不存在时抛出异常")
    void shouldThrowExceptionWhenReqNotFound() {
        // Act & Assert
        assertThrows(BusinessException.class, () -> {
            userAbilityReqService.getUserAbilityReqById(999L);
        });
    }
}
