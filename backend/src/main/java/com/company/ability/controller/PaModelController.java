package com.company.ability.controller;

import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.dto.PaCategoryCreateDTO;
import com.company.ability.dto.PaCategoryUpdateDTO;
import com.company.ability.dto.PaItemCreateDTO;
import com.company.ability.dto.PaItemUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaModelService;
import com.company.ability.vo.PaBandVO;
import com.company.ability.vo.PaModelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 岗位能力模型控制器。
 *
 * <p>路径全部挂在岗位下（{@code /pa/posts/{postId}/...}）：类别/能力项/区间只对所属岗位有意义，
 * 把 postId 放在路径里，服务层每次都能校验「这个对象是不是这个岗位的」，
 * 避免用别人的 categoryId 往自己岗位里塞数据。</p>
 *
 * <p>授权沿用维度授权（{@code DimensionContextFilter} 在请求级校验 X-Dimension-Id），
 * 与 {@link PaPostController} 一致。</p>
 */
@Tag(name = "岗位能力模型", description = "岗位内能力类别 / 能力项 / 等级 / 分值区间")
@RestController
@RequestMapping("/api/v1/pa/posts/{postId}")
@RequiredArgsConstructor
public class PaModelController {

    private final PaModelService modelService;

    @Operation(summary = "读取岗位能力模型（类别+能力项+等级+区间+权重合计）")
    @GetMapping("/model")
    public Result<PaModelVO> getModel(@PathVariable Long postId) {
        return Result.success(modelService.getModel(postId));
    }

    // ---------------------------------------------------------------- 类别

    @Operation(summary = "新增能力类别")
    @PostMapping("/categories")
    public Result<Long> createCategory(@PathVariable Long postId,
                                       @Valid @RequestBody PaCategoryCreateDTO dto) {
        return Result.success("创建成功", modelService.createCategory(postId, dto));
    }

    @Operation(summary = "更新能力类别")
    @PutMapping("/categories")
    public Result<Void> updateCategory(@PathVariable Long postId,
                                       @Valid @RequestBody PaCategoryUpdateDTO dto) {
        modelService.updateCategory(postId, dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除能力类别（类别下有能项时拒绝）")
    @DeleteMapping("/categories/{categoryId}")
    public Result<Void> deleteCategory(@PathVariable Long postId, @PathVariable Long categoryId) {
        modelService.deleteCategory(postId, categoryId);
        return Result.success("删除成功", null);
    }

    // ---------------------------------------------------------------- 能力项

    @Operation(summary = "新增能力项（含等级，一次事务）")
    @PostMapping("/items")
    public Result<Long> createItem(@PathVariable Long postId, @Valid @RequestBody PaItemCreateDTO dto) {
        return Result.success("创建成功", modelService.createItem(postId, dto));
    }

    @Operation(summary = "更新能力项（levels 传 null 表示不动等级，传列表则整批覆盖）")
    @PutMapping("/items")
    public Result<Void> updateItem(@PathVariable Long postId, @Valid @RequestBody PaItemUpdateDTO dto) {
        modelService.updateItem(postId, dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除能力项（被测评引用时拒绝）")
    @DeleteMapping("/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long postId, @PathVariable Long itemId) {
        modelService.deleteItem(postId, itemId);
        return Result.success("删除成功", null);
    }

    // ---------------------------------------------------------------- 分值区间

    @Operation(summary = "查询分值区间")
    @GetMapping("/bands")
    public Result<List<PaBandVO>> listBands(@PathVariable Long postId) {
        return Result.success(modelService.listBands(postId));
    }

    @Operation(summary = "整批保存分值区间（重叠/断档会被拒绝）")
    @PutMapping("/bands")
    public Result<Void> saveBands(@PathVariable Long postId,
                                  @Valid @RequestBody List<PaBandSaveDTO> bands) {
        modelService.saveBands(postId, bands);
        return Result.success("保存成功", null);
    }
}
