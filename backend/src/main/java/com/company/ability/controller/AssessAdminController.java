package com.company.ability.controller;

import com.company.ability.dto.AssessAdminCreateDTO;
import com.company.ability.dto.Result;
import com.company.ability.service.AdminService;
import com.company.ability.vo.AssessAdminVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统管理员配置（仅管理员可操作：入口自身也要求管理员，防止扩权面失控）
 */
@Tag(name = "管理员配置", description = "系统管理员名单维护")
@RestController
@RequestMapping("/api/v1/assess-admins")
@RequiredArgsConstructor
public class AssessAdminController {

    private final AdminService adminService;

    @Operation(summary = "管理员名单（含配置超管）")
    @GetMapping
    public Result<List<AssessAdminVO>> list() {
        adminService.requireAdmin();
        return Result.success(adminService.list());
    }

    @Operation(summary = "新增管理员（按泛微在职人员）")
    @PostMapping
    public Result<Long> add(@Valid @RequestBody AssessAdminCreateDTO dto) {
        adminService.requireAdmin();
        return Result.success("添加成功", adminService.add(dto));
    }

    @Operation(summary = "移除管理员（配置超管与本人不可删）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminService.requireAdmin();
        adminService.delete(id);
        return Result.success("删除成功", null);
    }
}
