package com.company.ability.service;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.ScoreWeightCreateDTO;
import com.company.ability.dto.ScoreWeightUpdateDTO;
import com.company.ability.entity.ScoreWeight;
import com.company.ability.exception.BusinessException;
import com.company.ability.vo.ScoreWeightVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评分权重配置服务测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("评分权重配置服务测试")
class ScoreWeightServiceTest {

    @Autowired
    private ScoreWeightService scoreWeightService;

    @BeforeEach
    void setUp() {
        // 每个测试前清理数据
        scoreWeightService.remove(null);
    }

    @Test
    @DisplayName("应该创建评分权重配置当权重总和为100%")
    void shouldCreateScoreWeightWhenWeightSumIs100() {
        // Arrange
        ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
        dto.setCompanyId(1L);
        dto.setWeightName("标准权重");
        dto.setCategoryWeight(new BigDecimal("30.00"));
        dto.setElementWeight(new BigDecimal("70.00"));
        dto.setDescription("测试描述");

        // Act
        Long id = scoreWeightService.createScoreWeight(dto);

        // Assert
        assertNotNull(id);
        ScoreWeightVO vo = scoreWeightService.getScoreWeightById(id);
        assertEquals("标准权重", vo.getWeightName());
        assertEquals(new BigDecimal("30.00"), vo.getCategoryWeight());
        assertEquals(new BigDecimal("70.00"), vo.getElementWeight());
    }

    @Test
    @DisplayName("应该抛出异常当权重总和不等于100%")
    void shouldThrowExceptionWhenWeightSumIsNot100() {
        // Arrange
        ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
        dto.setCompanyId(1L);
        dto.setWeightName("错误权重");
        dto.setCategoryWeight(new BigDecimal("30.00"));
        dto.setElementWeight(new BigDecimal("50.00")); // 总和 = 80，不等于100

        // Act & Assert
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            scoreWeightService.createScoreWeight(dto);
        });
        assertEquals("类别权重和要素权重之和必须等于100%", exception.getMessage());
    }

    @Test
    @DisplayName("应该更新评分权重配置")
    void shouldUpdateScoreWeight() {
        // Arrange
        ScoreWeightCreateDTO createDTO = new ScoreWeightCreateDTO();
        createDTO.setCompanyId(1L);
        createDTO.setWeightName("原始方案");
        createDTO.setCategoryWeight(new BigDecimal("40.00"));
        createDTO.setElementWeight(new BigDecimal("60.00"));
        Long id = scoreWeightService.createScoreWeight(createDTO);

        ScoreWeightUpdateDTO updateDTO = new ScoreWeightUpdateDTO();
        updateDTO.setId(id);
        updateDTO.setWeightName("更新后的方案");
        updateDTO.setCategoryWeight(new BigDecimal("50.00"));
        updateDTO.setElementWeight(new BigDecimal("50.00"));

        // Act
        scoreWeightService.updateScoreWeight(updateDTO);

        // Assert
        ScoreWeightVO vo = scoreWeightService.getScoreWeightById(id);
        assertEquals("更新后的方案", vo.getWeightName());
        assertEquals(new BigDecimal("50.00"), vo.getCategoryWeight());
        assertEquals(new BigDecimal("50.00"), vo.getElementWeight());
    }

    @Test
    @DisplayName("应该删除评分权重配置")
    void shouldDeleteScoreWeight() {
        // Arrange
        ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
        dto.setCompanyId(1L);
        dto.setWeightName("要删除的方案");
        dto.setCategoryWeight(new BigDecimal("30.00"));
        dto.setElementWeight(new BigDecimal("70.00"));
        Long id = scoreWeightService.createScoreWeight(dto);

        // Act
        scoreWeightService.deleteScoreWeight(id);

        // Assert
        assertThrows(BusinessException.class, () -> {
            scoreWeightService.getScoreWeightById(id);
        });
    }

    @Test
    @DisplayName("应该查询所有评分权重配置")
    void shouldListAllScoreWeights() {
        // Arrange
        for (int i = 1; i <= 3; i++) {
            ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
            dto.setCompanyId(1L);
            dto.setWeightName("方案" + i);
            dto.setCategoryWeight(new BigDecimal("30.00"));
            dto.setElementWeight(new BigDecimal("70.00"));
            scoreWeightService.createScoreWeight(dto);
        }

        // Act
        PageResult<ScoreWeightVO> result = scoreWeightService.pageScoreWeights(new PageRequest());

        // Assert
        assertEquals(3, result.getList().size());
    }

    @Test
    @DisplayName("应该根据分公司ID查询权重配置")
    void shouldListScoreWeightsByCompanyId() {
        // Arrange
        for (int i = 1; i <= 2; i++) {
            ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
            dto.setCompanyId(1L);
            dto.setWeightName("分公司1方案" + i);
            dto.setCategoryWeight(new BigDecimal("30.00"));
            dto.setElementWeight(new BigDecimal("70.00"));
            scoreWeightService.createScoreWeight(dto);
        }

        ScoreWeightCreateDTO dto = new ScoreWeightCreateDTO();
        dto.setCompanyId(2L);
        dto.setWeightName("分公司2方案");
        dto.setCategoryWeight(new BigDecimal("40.00"));
        dto.setElementWeight(new BigDecimal("60.00"));
        scoreWeightService.createScoreWeight(dto);

        // Act
        PageResult<ScoreWeightVO> result = scoreWeightService.pageScoreWeightsByCompanyId(1L, new PageRequest());

        // Assert
        assertEquals(2, result.getList().size());
    }
}
