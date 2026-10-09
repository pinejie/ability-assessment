package com.company.ability.controller;

import com.company.ability.dto.AbilityElementCreateDTO;
import com.company.ability.dto.AbilityElementUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AbilityElementService;
import com.company.ability.vo.AbilityElementVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能力要素控制器
 */
@Tag(name = "能力要素管理", description = "能力要素的增删改查")
@RestController
@RequestMapping("/api/v1/ability-elements")
@RequiredArgsConstructor
public class AbilityElementController {

    private final AbilityElementService abilityElementService;

    @Operation(summary = "创建能力要素")
    @PostMapping
    public Result<Long> createElement(@Valid @RequestBody AbilityElementCreateDTO dto) {
        Long id = abilityElementService.createElement(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新能力要素")
    @PutMapping
    public Result<Void> updateElement(@Valid @RequestBody AbilityElementUpdateDTO dto) {
        abilityElementService.updateElement(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除能力要素")
    @DeleteMapping("/{id}")
    public Result<Void> deleteElement(@PathVariable Long id) {
        abilityElementService.deleteElement(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询能力要素")
    @GetMapping("/{id}")
    public Result<AbilityElementVO> getElementById(@PathVariable Long id) {
        AbilityElementVO vo = abilityElementService.getElementById(id);
        return Result.success(vo);
    }

    @Operation(summary = "查询所有能力要素")
    @GetMapping
    public Result<List<AbilityElementVO>> listAllElements() {
        List<AbilityElementVO> list = abilityElementService.listAllElements();
        return Result.success(list);
    }

    @Operation(summary = "根据类别ID查询要素列表")
    @GetMapping("/category/{categoryId}")
    public Result<List<AbilityElementVO>> listElementsByCategoryId(@PathVariable Long categoryId) {
        List<AbilityElementVO> list = abilityElementService.listElementsByCategoryId(categoryId);
        return Result.success(list);
    }
}
