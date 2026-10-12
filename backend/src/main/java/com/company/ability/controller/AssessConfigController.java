package com.company.ability.controller;

import com.company.ability.dto.AssessConfigDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessConfigService;
import com.company.ability.vo.AssessConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能力测评配置控制器
 */
@Tag(name = "能力测评配置", description = "能力类型权重、测评对象权重、等级标准")
@RestController
@RequestMapping("/api/v1/assess-config")
@RequiredArgsConstructor
public class AssessConfigController {

    private final AssessConfigService assessConfigService;

    @Operation(summary = "查询能力类型权重")
    @GetMapping("/ability-weights")
    public Result<List<AssessConfigVO.AbilityWeightVO>> getAbilityWeights(@RequestParam Long companyId) {
        return Result.success(assessConfigService.getAbilityWeights(companyId));
    }

    @Operation(summary = "保存能力类型权重")
    @PutMapping("/ability-weights")
    public Result<Void> saveAbilityWeights(@Valid @RequestBody AssessConfigDTO.AbilityWeightSaveDTO dto) {
        assessConfigService.saveAbilityWeights(dto);
        return Result.success("保存成功", null);
    }

    @Operation(summary = "查询测评对象权重")
    @GetMapping("/assessor-weights")
    public Result<List<AssessConfigVO.AssessorWeightVO>> getAssessorWeights(@RequestParam Long companyId) {
        return Result.success(assessConfigService.getAssessorWeights(companyId));
    }

    @Operation(summary = "保存测评对象权重")
    @PutMapping("/assessor-weights")
    public Result<Void> saveAssessorWeights(@Valid @RequestBody AssessConfigDTO.AssessorWeightSaveDTO dto) {
        assessConfigService.saveAssessorWeights(dto);
        return Result.success("保存成功", null);
    }

    @Operation(summary = "查询等级标准")
    @GetMapping("/grade-levels")
    public Result<List<AssessConfigVO.GradeLevelVO>> getGradeLevels(@RequestParam Long companyId) {
        return Result.success(assessConfigService.getGradeLevels(companyId));
    }

    @Operation(summary = "保存等级标准")
    @PutMapping("/grade-levels")
    public Result<Void> saveGradeLevels(@Valid @RequestBody AssessConfigDTO.GradeLevelSaveDTO dto) {
        assessConfigService.saveGradeLevels(dto);
        return Result.success("保存成功", null);
    }

    @Operation(summary = "查询等级分值")
    @GetMapping("/level-scores")
    public Result<List<AssessConfigVO.LevelScoreVO>> getLevelScores(@RequestParam Long companyId) {
        return Result.success(assessConfigService.getLevelScores(companyId));
    }

    @Operation(summary = "保存等级分值")
    @PutMapping("/level-scores")
    public Result<Void> saveLevelScores(@Valid @RequestBody AssessConfigDTO.LevelScoreSaveDTO dto) {
        assessConfigService.saveLevelScores(dto);
        return Result.success("保存成功", null);
    }
}
