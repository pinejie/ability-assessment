package com.company.ability.controller;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.AssessDimensionUpdateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AssessDimensionService;
import com.company.ability.vo.AssessDimensionAuthVO;
import com.company.ability.vo.AssessDimensionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 测评维度控制器（类租户）
 */
@Tag(name = "测评维度管理", description = "维度的增删改查与授权配置")
@RestController
@RequestMapping("/api/v1/assess-dimensions")
@RequiredArgsConstructor
public class AssessDimensionController {

    private final AssessDimensionService assessDimensionService;

    @Operation(summary = "查询全部维度（管理页）")
    @GetMapping
    public Result<List<AssessDimensionVO>> listAll() {
        return Result.success(assessDimensionService.listAll());
    }

    @Operation(summary = "查询指定人员可用的维度（顶栏切换器；不传 resourceId 返回全部启用维度）")
    @GetMapping("/authorized")
    public Result<List<AssessDimensionVO>> authorized(
            @RequestParam(required = false) Long resourceId) {
        return Result.success(assessDimensionService.authorized(resourceId));
    }

    @Operation(summary = "创建维度")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AssessDimensionCreateDTO dto) {
        return Result.success("创建成功", assessDimensionService.create(dto));
    }

    @Operation(summary = "更新维度")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody AssessDimensionUpdateDTO dto) {
        assessDimensionService.update(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除维度")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assessDimensionService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "查询维度授权列表")
    @GetMapping("/{id}/auths")
    public Result<List<AssessDimensionAuthVO>> listAuths(@PathVariable Long id) {
        return Result.success(assessDimensionService.listAuths(id));
    }

    @Operation(summary = "整体覆盖保存维度授权")
    @PutMapping("/{id}/auths")
    public Result<Void> saveAuths(@PathVariable Long id,
                                  @RequestBody(required = false) List<DimensionAuthItemDTO> items) {
        assessDimensionService.saveAuths(id, items);
        return Result.success("保存成功", null);
    }
}
