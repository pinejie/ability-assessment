package com.company.ability.controller;

import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import com.company.ability.service.PaReportService;
import com.company.ability.vo.PaReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人测评报告（方案 17 §7.2）：只读，替代原「测评数据汇总」的弹窗式报告。
 *
 * <p>为什么不复用 {@code /api/v1/assess/sheets}：那个接口读的是旧页面表
 * （{@code uf_assess_sheet}），P3 起旧表已 DROP。两条链路的字段口径没有任何交集，
 * 并存只会让前端拿到一堆 NULL 列。</p>
 */
@Tag(name = "个人测评报告", description = "测评报告列表与明细（只读）")
@RestController
@RequestMapping("/api/v1/pa/reports")
@RequiredArgsConstructor
public class PaReportController {

    private final PaReportService reportService;

    @Operation(summary = "个人测评报告列表（分页）")
    @GetMapping
    public Result<PageResult<PaReportVO.ListItem>> page(
            @RequestParam Integer year,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(reportService.page(year, resourceId, departmentId, status, pageNum, pageSize));
    }

    @Operation(summary = "个人测评报告明细")
    @GetMapping("/{sheetId}")
    public Result<PaReportVO.Detail> detail(@PathVariable Long sheetId) {
        return Result.success(reportService.detail(sheetId));
    }

    // ==================== 封存 / 解除封存（2026-11-24） ====================

    /**
     * 封存是自助视角的可见性开关，所以这两个写接口<b>不进</b> DimensionContextFilter 的豁免名单：
     * 它们本来就该受维度授权约束（能封存的只有管理员与维度管理员，见服务层校验）。
     */

    @Operation(summary = "确认封存测评报告（封存后本人 / 直接上级才看得到）")
    @PostMapping("/{sheetId}/seal")
    public Result<PaReportVO.Detail> seal(@PathVariable Long sheetId) {
        return Result.success(reportService.seal(sheetId));
    }

    @Operation(summary = "解除封存（解除后自助视角立刻重新不可见）")
    @PostMapping("/{sheetId}/unseal")
    public Result<Void> unseal(@PathVariable Long sheetId) {
        reportService.unseal(sheetId);
        return Result.success(null);
    }

    // ==================== 我的测评（本人视角，2026-11-23） ====================

    /**
     * 「我的测评」页的三个接口：查询主体由服务端从登录态取，<b>不接受任何人员参数</b>。
     *
     * <p>为什么不复用上面的 /api/v1/pa/reports：那条链路按「维度上下文 + 维度授权」过滤，
     * 被测评人是普通员工，通常没有维度授权，一进页面就 403。本人只会看自己的，
     * 判定口径是「登录人 == 被测评人」，与该不该有维度授权无关。
     * 三个路径在 DimensionContextFilter 里豁免，越权防护由服务层归属校验承担。</p>
     */

    @Operation(summary = "我的测评：可看年度（跨维度，标记进行中）")
    @GetMapping("/my-reports/years")
    public Result<List<PaReportVO.MyYear>> myYears() {
        return Result.success(reportService.myYears());
    }

    @Operation(summary = "我的测评：按年度列出我自己的报告；不传年度取进行中年度")
    @GetMapping("/my-reports")
    public Result<List<PaReportVO.ListItem>> myReports(
            @RequestParam(required = false) Integer year) {
        return Result.success(reportService.myReports(year));
    }

    @Operation(summary = "我的测评：本人某张页面的报告详情（姓名不返回）")
    @GetMapping("/my-reports/{sheetId}")
    public Result<PaReportVO.Detail> myDetail(@PathVariable Long sheetId) {
        return Result.success(reportService.myDetail(sheetId));
    }

    // ==================== 下属的报告（2026-11-24） ====================

    /**
     * 直接下属的测评报告。同样是「查询主体从登录态取，不接受任何人员参数」——
     * 放行 {@code resourceId} 就等于让任何登录人拿 id 试别人的报告。
     *
     * <p>三条路径必须与 my-reports 一起留在 {@code DimensionContextFilter} 的白名单里：
     * 有下属的基层管理者通常没有维度授权，按维度头拦截会让他一进页面就 403。</p>
     */

    @Operation(summary = "我的测评：我有没有直接下属（没有就整个 tab 不渲染）")
    @GetMapping("/sub-reports/exists")
    public Result<Boolean> hasSubordinates() {
        return Result.success(reportService.hasSubordinates());
    }

    @Operation(summary = "我的测评：直接下属的报告列表；不传年度取进行中年度")
    @GetMapping("/sub-reports")
    public Result<List<PaReportVO.ListItem>> subordinateReports(
            @RequestParam(required = false) Integer year) {
        return Result.success(reportService.subordinateReports(year));
    }

    @Operation(summary = "我的测评：某位直接下属的报告详情（姓名不返回）")
    @GetMapping("/sub-reports/{sheetId}")
    public Result<PaReportVO.Detail> subordinateDetail(@PathVariable Long sheetId) {
        return Result.success(reportService.subordinateDetail(sheetId));
    }
}
