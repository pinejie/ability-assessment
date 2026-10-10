package com.company.ability.controller;

import com.company.ability.dto.AbilityElementLevelCreateDTO;
import com.company.ability.dto.AbilityElementLevelUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import com.company.ability.service.AbilityElementLevelService;
import com.company.ability.vo.AbilityElementLevelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 能力要素等级控制器
 */
@Tag(name = "能力要素等级管理", description = "能力要素等级配置的增删改查")
@RestController
@RequestMapping("/api/v1/ability-element-levels")
@RequiredArgsConstructor
public class AbilityElementLevelController {

    private final AbilityElementLevelService abilityElementLevelService;

    @Operation(summary = "创建等级配置")
    @PostMapping
    public Result<Long> createLevel(@Valid @RequestBody AbilityElementLevelCreateDTO dto) {
        Long id = abilityElementLevelService.createLevel(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新等级配置")
    @PutMapping
    public Result<Void> updateLevel(@Valid @RequestBody AbilityElementLevelUpdateDTO dto) {
        abilityElementLevelService.updateLevel(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除等级配置")
    @DeleteMapping("/{id}")
    public Result<Void> deleteLevel(@PathVariable Long id) {
        abilityElementLevelService.deleteLevel(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询等级配置")
    @GetMapping("/{id}")
    public Result<AbilityElementLevelVO> getLevelById(@PathVariable Long id) {
        AbilityElementLevelVO vo = abilityElementLevelService.getLevelById(id);
        return Result.success(vo);
    }

    @Operation(summary = "根据能力要素ID分页查询等级列表")
    @GetMapping("/element/{elementId}")
    public Result<PageResult<AbilityElementLevelVO>> pageLevelsByElementId(
            @PathVariable Long elementId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        PageResult<AbilityElementLevelVO> result = abilityElementLevelService.pageLevelsByElementId(elementId, pageRequest);
        return Result.success(result);
    }
}
