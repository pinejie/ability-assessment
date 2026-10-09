package com.company.ability.controller;

import com.company.ability.dto.AbilityCategoryCreateDTO;
import com.company.ability.dto.AbilityCategoryUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.vo.AbilityCategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 能力类别控制器
 */
@Tag(name = "能力类别管理", description = "能力类别的增删改查")
@RestController
@RequestMapping("/api/v1/ability-categories")
@RequiredArgsConstructor
public class AbilityCategoryController {

    private final AbilityCategoryService abilityCategoryService;

    @Operation(summary = "创建能力类别")
    @PostMapping
    public Result<Long> createCategory(@Valid @RequestBody AbilityCategoryCreateDTO dto) {
        Long id = abilityCategoryService.createCategory(dto);
        return Result.success("创建成功", id);
    }

    @Operation(summary = "更新能力类别")
    @PutMapping
    public Result<Void> updateCategory(@Valid @RequestBody AbilityCategoryUpdateDTO dto) {
        abilityCategoryService.updateCategory(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除能力类别")
    @DeleteMapping("/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        abilityCategoryService.deleteCategory(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "根据ID查询能力类别")
    @GetMapping("/{id}")
    public Result<AbilityCategoryVO> getCategoryById(@PathVariable Long id) {
        AbilityCategoryVO vo = abilityCategoryService.getCategoryById(id);
        return Result.success(vo);
    }

    @Operation(summary = "查询所有能力类别")
    @GetMapping
    public Result<List<AbilityCategoryVO>> listAllCategories() {
        List<AbilityCategoryVO> list = abilityCategoryService.listAllCategories();
        return Result.success(list);
    }
}
