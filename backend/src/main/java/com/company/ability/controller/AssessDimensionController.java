package com.company.ability.controller;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.AssessDimensionUpdateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.dto.Result;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AdminService;
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
 * 测评维度控制器（类租户）。
 * 管理口（增删改、授权、全量列表）仅管理员；切换器口（/authorized）登录者按授权过滤、管理员全量。
 */
@Tag(name = "测评维度管理", description = "维度的增删改查与授权配置")
@RestController
@RequestMapping("/api/v1/assess-dimensions")
@RequiredArgsConstructor
public class AssessDimensionController {

    private final AssessDimensionService assessDimensionService;
    private final AdminService adminService;

    @Operation(summary = "查询全部维度（管理页，仅管理员）")
    @GetMapping
    public Result<List<AssessDimensionVO>> listAll() {
        adminService.requireAdmin();
        return Result.success(assessDimensionService.listAll());
    }

    @Operation(summary = "查询当前登录用户可用的维度（顶栏切换器；管理员为全部启用维度）")
    @GetMapping("/authorized")
    public Result<List<AssessDimensionVO>> authorized(
            @RequestParam(required = false) Long resourceId) {
        // SSO 登录后身份取自 JWT（服务端权威，不可伪造）；无登录态时回退查询参数（宽松兼容旧调用）
        LoginUser current = UserContext.currentOrNull();
        boolean admin = current != null && adminService.isAdmin(current.loginid());
        Long currentResourceId = UserContext.currentResourceIdOrNull();
        return Result.success(assessDimensionService.authorized(
                currentResourceId != null ? currentResourceId : resourceId, admin));
    }

    @Operation(summary = "创建维度（仅管理员）")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AssessDimensionCreateDTO dto) {
        adminService.requireAdmin();
        return Result.success("创建成功", assessDimensionService.create(dto));
    }

    @Operation(summary = "更新维度（仅管理员）")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody AssessDimensionUpdateDTO dto) {
        adminService.requireAdmin();
        assessDimensionService.update(dto);
        return Result.success("更新成功", null);
    }

    @Operation(summary = "删除维度（仅管理员）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminService.requireAdmin();
        assessDimensionService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "查询维度授权列表（仅管理员）")
    @GetMapping("/{id}/auths")
    public Result<List<AssessDimensionAuthVO>> listAuths(@PathVariable Long id) {
        adminService.requireAdmin();
        return Result.success(assessDimensionService.listAuths(id));
    }

    @Operation(summary = "整体覆盖保存维度授权（仅管理员）")
    @PutMapping("/{id}/auths")
    public Result<Void> saveAuths(@PathVariable Long id,
                                  @RequestBody(required = false) List<DimensionAuthItemDTO> items) {
        adminService.requireAdmin();
        assessDimensionService.saveAuths(id, items);
        return Result.success("保存成功", null);
    }
}
