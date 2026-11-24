package com.company.ability.controller;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PaPostCreateDTO;
import com.company.ability.dto.PaPostUpdateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.PaPostService;
import com.company.ability.vo.PaPostVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 测评岗位控制器（岗位能力模型重构 §4.1）。
 *
 * <p>岗位 = 部门 + 岗位名，是整套能力模型的锚点。授权沿用维度授权
 * （由 {@code DimensionContextFilter} 在请求级校验 X-Dimension-Id），不再单独设管理员门。</p>
 */
@Tag(name = "测评岗位", description = "部门+岗位维度的能力模型锚点")
@RestController
@RequestMapping("/api/v1/pa/posts")
@RequiredArgsConstructor
public class PaPostController {

    private final PaPostService postService;

    @Operation(summary = "创建岗位")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PaPostCreateDTO dto) {
        return Result.success("创建成功", postService.createPost(dto));
    }

    @Operation(summary = "更新岗位")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody PaPostUpdateDTO dto) {
        postService.updatePost(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除岗位（有引用时拒绝）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.deletePost(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "查询岗位详情（含类别数/能力项数/权重合计）")
    @GetMapping("/{id}")
    public Result<PaPostVO> detail(@PathVariable Long id) {
        return Result.success(postService.getPostById(id));
    }

    @Operation(summary = "分页查询岗位")
    @GetMapping
    public Result<PageResult<PaPostVO>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Boolean generic) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(pageNum);
        pageRequest.setPageSize(pageSize);
        return Result.success(postService.pagePosts(pageRequest, departmentId, keyword, status, generic));
    }

    @Operation(summary = "岗位下拉列表（仅启用）")
    @GetMapping("/list")
    public Result<List<PaPostVO>> list(@RequestParam(required = false) Long departmentId) {
        return Result.success(postService.listPosts(departmentId));
    }

    @Operation(summary = "全公司通用岗位（仅启用）")
    @GetMapping("/list/generic")
    public Result<List<PaPostVO>> listGeneric() {
        return Result.success(postService.listGenericPosts());
    }
}
