package com.company.ability.controller;

import com.company.ability.config.DimensionContextFilter;
import com.company.ability.context.DimensionContext;
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
import com.company.ability.vo.DimensionScopeVO;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * 当前维度的组织范围（部门/人员候选收窄用）。
     *
     * <p>不加管理员门：本接口只为「收窄自己看得到的候选」服务，返回的是维度授权的组织范围，
     * 不涉及任何业务数据；能进这个维度的人本来就已通过 {@code DimensionContextFilter} 校验。</p>
     *
     * <p>维度 ID 直接读请求头，不走 {@code DimensionContext}：本控制器整个路径前缀
     * {@code /api/v1/assess-dimensions} 在过滤器的豁免名单里（顶栏切换器/目录自身不能被 403），
     * 豁免分支不写上下文，读 {@code getOrDefault()} 会静默落回默认维度 1 —— 那是「不限」，
     * 等于这条收窄从来没生效过。头缺失时才回落到默认维度。</p>
     */
    @Operation(summary = "查询当前维度的组织范围（授权分部下的部门）")
    @GetMapping("/current-scope")
    public Result<DimensionScopeVO> currentScope(
            @RequestHeader(name = DimensionContextFilter.HEADER_DIMENSION_ID, required = false) Long dimensionId) {
        return Result.success(assessDimensionService.currentScope(
                dimensionId != null && dimensionId > 0 ? dimensionId : DimensionContext.DEFAULT_DIMENSION_ID));
    }

    @Operation(summary = "查询维度授权列表（仅管理员）")
    @GetMapping("/{id}/auths")    public Result<List<AssessDimensionAuthVO>> listAuths(@PathVariable Long id) {
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
