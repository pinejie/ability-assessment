package com.company.ability.controller;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.ScoreWeightCreateDTO;
import com.company.ability.dto.ScoreWeightUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.ScoreWeightService;
import com.company.ability.vo.ScoreWeightVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评分权重配置控制器
 */
@Tag(name = "评分权重配置管理", description = "评分权重配置的增删改查")
@RestController
@RequestMapping("/api/v1/score-weights")
@RequiredArgsConstructor
public class ScoreWeightController {

    private final ScoreWeightService scoreWeightService;

    @Operation(summary = "创建评分权重配置")
    @PostMapping
    public Result<Long> createScoreWeight(@Valid @RequestBody ScoreWeightCreateDTO dto) {
        Long id = scoreWeightService.createScoreWeight(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新评分权重配置")
    @PutMapping
    public Result<Void> updateScoreWeight(@Valid @RequestBody ScoreWeightUpdateDTO dto) {
        scoreWeightService.updateScoreWeight(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除评分权重配置")
    @DeleteMapping("/{id}")
    public Result<Void> deleteScoreWeight(@PathVariable Long id) {
        scoreWeightService.deleteScoreWeight(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询评分权重配置")
    @GetMapping("/{id}")
    public Result<ScoreWeightVO> getScoreWeightById(@PathVariable Long id) {
        ScoreWeightVO vo = scoreWeightService.getScoreWeightById(id);
        return Result.success(vo);
    }

    @Operation(summary = "分页查询评分权重配置")
    @GetMapping
    public Result<PageResult<ScoreWeightVO>> pageScoreWeights(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        return Result.success(scoreWeightService.pageScoreWeights(pageRequest));
    }

    @Operation(summary = "根据分公司ID分页查询权重配置列表")
    @GetMapping("/company/{companyId}")
    public Result<PageResult<ScoreWeightVO>> pageScoreWeightsByCompanyId(
            @PathVariable Long companyId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        return Result.success(scoreWeightService.pageScoreWeightsByCompanyId(companyId, pageRequest));
    }
}
