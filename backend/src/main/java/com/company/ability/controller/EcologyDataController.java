package com.company.ability.controller;

import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.Result;
import com.company.ability.utils.JdbcPageHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 泛微数据控制器
 */
@Tag(name = "泛微数据查询", description = "查询泛微系统中的部门、岗位、人员数据")
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EcologyDataController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 分公司 ====================

    @Operation(summary = "分页查询所有分公司")
    @GetMapping("/sub-companies")
    public Result<PageResult<Map<String, Object>>> pageSubCompanies(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE ISNULL(canceled, 0)=0 ORDER BY subcompanyname";
            String countSql = "SELECT COUNT(*) FROM HrmSubCompany WHERE ISNULL(canceled, 0)=0";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, pageRequest));
        } catch (Exception e) {
            log.error("查询分公司列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "一级分公司全量列表（顶层分部，授权选择器数据源；不分页不截断）")
    @GetMapping("/sub-companies/level1")
    public Result<List<Map<String, Object>>> listLevel1SubCompanies() {
        try {
            String sql = "SELECT id, subcompanyname FROM HrmSubCompany "
                    + "WHERE ISNULL(canceled, 0)=0 AND ISNULL(supsubcomid, 0)=0 ORDER BY id";
            return Result.success(jdbcTemplate.queryForList(sql));
        } catch (Exception e) {
            log.error("查询一级分公司列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "全部分公司的树形列表（授权选择器：可勾父分部、也可单独勾某一级下级）")
    @GetMapping("/sub-companies/tree")
    public Result<List<Map<String, Object>>> listSubCompanyTree() {
        try {
            // 分部表全量只有几十行（实测 30 行），一次拉回内存拼树，不做递归 SQL。
            // 不截断：授权选择器少一个分部，等于那个分部的人永远配不上权限，且没有任何提示。
            String sql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany "
                    + "WHERE ISNULL(canceled, 0)=0 ORDER BY id";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

            Map<Long, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> node = new java.util.LinkedHashMap<>();
                node.put("id", ((Number) row.get("id")).longValue());
                node.put("name", row.get("subcompanyname"));
                node.put("children", new ArrayList<Map<String, Object>>());
                byId.put(((Number) row.get("id")).longValue(), node);
            }
            List<Map<String, Object>> roots = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> node = byId.get(((Number) row.get("id")).longValue());
                Object sup = row.get("supsubcomid");
                Long parentId = sup == null ? null : ((Number) sup).longValue();
                Map<String, Object> parent = parentId == null || parentId == 0L ? null : byId.get(parentId);
                if (parent == null) {
                    // 上级是 0 / NULL，或指向一个已注销的分部 —— 都当根节点，
                    // 否则这些分部会从选择器里凭空消失
                    roots.add(node);
                } else {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> siblings =
                            (List<Map<String, Object>>) parent.get("children");
                    siblings.add(node);
                }
            }
            return Result.success(roots);
        } catch (Exception e) {
            log.error("查询分公司树失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询根分公司（无上级公司）")
    @GetMapping("/sub-companies/root")
    public Result<List<Map<String, Object>>> listRootSubCompanies() {
        try {
            String sql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE ISNULL(canceled, 0)=0 AND (supsubcomid IS NULL OR supsubcomid=0) ORDER BY subcompanyname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询根分公司列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询指定公司下的子公司")
    @GetMapping("/sub-companies/children")
    public Result<List<Map<String, Object>>> listChildSubCompanies(@RequestParam Long parentId) {
        try {
            String sql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE ISNULL(canceled, 0)=0 AND supsubcomid=? ORDER BY subcompanyname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, parentId);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询子公司列表失败", e);
            return Result.success(List.of());
        }
    }

    // ==================== 部门 ====================

    @Operation(summary = "分页查询所有部门")
    @GetMapping("/departments")
    public Result<PageResult<Map<String, Object>>> pageDepartments(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, departmentmark, supdepid, subcompanyid1 FROM HrmDepartment WHERE ISNULL(canceled, 0)=0 ORDER BY departmentmark";
            String countSql = "SELECT COUNT(*) FROM HrmDepartment WHERE ISNULL(canceled, 0)=0";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, pageRequest));
        } catch (Exception e) {
            log.error("查询部门列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "查询指定公司下的根部门")
    @GetMapping("/departments/root")
    public Result<List<Map<String, Object>>> listRootDepartments(@RequestParam Long subcompanyId) {
        try {
            String sql = "SELECT id, departmentmark, supdepid, subcompanyid1 FROM HrmDepartment WHERE ISNULL(canceled, 0)=0 AND subcompanyid1=? AND (supdepid IS NULL OR supdepid=0) ORDER BY departmentmark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, subcompanyId);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询根部门列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询指定部门下的子部门")
    @GetMapping("/departments/children")
    public Result<List<Map<String, Object>>> listChildDepartments(@RequestParam Long parentId) {
        try {
            String sql = "SELECT id, departmentmark, supdepid, subcompanyid1 FROM HrmDepartment WHERE ISNULL(canceled, 0)=0 AND supdepid=? ORDER BY departmentmark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, parentId);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询子部门列表失败", e);
            return Result.success(List.of());
        }
    }

    // ==================== 岗位类别/职务/岗位 ====================

    @Operation(summary = "查询岗位类别")
    @GetMapping("/job-groups")
    public Result<List<Map<String, Object>>> listJobGroups() {
        try {
            String sql = "SELECT id, jobgroupremark FROM HrmJobGroups WHERE id IN (707, 708) ORDER BY jobgroupremark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> group : list) {
                group.put("hasChildren", true);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询岗位类别失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询指定类别下的职务")
    @GetMapping("/job-activities/by-group")
    public Result<List<Map<String, Object>>> listJobActivitiesByGroup(@RequestParam Long groupId) {
        try {
            String sql = "SELECT id, jobactivitymark, jobgroupid FROM HrmJobActivities WHERE jobgroupid=? ORDER BY jobactivitymark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, groupId);
            for (Map<String, Object> activity : list) {
                activity.put("hasChildren", true);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询职务列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "分页查询职务")
    @GetMapping("/job-activities")
    public Result<PageResult<Map<String, Object>>> pageJobActivities(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, jobactivitymark, jobgroupid FROM HrmJobActivities ORDER BY jobactivitymark";
            String countSql = "SELECT COUNT(*) FROM HrmJobActivities";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, pageRequest));
        } catch (Exception e) {
            log.error("查询职务列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "查询指定职务下的岗位")
    @GetMapping("/job-titles/by-activity")
    public Result<List<Map<String, Object>>> listJobTitlesByActivity(@RequestParam Long activityId) {
        try {
            String sql = "SELECT id, jobtitlemark, jobactivityid FROM HrmJobTitles WHERE ISNULL(canceled, 0)=0 AND jobactivityid=? ORDER BY jobtitlemark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, activityId);
            for (Map<String, Object> title : list) {
                title.put("hasChildren", false);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询岗位列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "分页查询所有岗位")
    @GetMapping("/job-titles")
    public Result<PageResult<Map<String, Object>>> pageJobTitles(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, jobtitlemark, jobactivityid FROM HrmJobTitles WHERE ISNULL(canceled, 0)=0 ORDER BY jobtitlemark";
            String countSql = "SELECT COUNT(*) FROM HrmJobTitles WHERE ISNULL(canceled, 0)=0";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, pageRequest));
        } catch (Exception e) {
            log.error("查询岗位列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "查询指定部门下的岗位")
    @GetMapping("/job-titles/by-department")
    public Result<List<Map<String, Object>>> listJobTitlesByDepartment(@RequestParam Long departmentId) {
        try {
            String sql = "SELECT DISTINCT r.jobtitle as id, t.jobtitlemark as name " +
                         "FROM HrmResource r " +
                         "LEFT JOIN HrmJobTitles t ON r.jobtitle = t.id " +
                         "WHERE r.departmentid = ? " +
                         "AND r.jobtitle IS NOT NULL AND r.jobtitle > 0 " +
                         "AND ISNULL(r.status, -1) IN (0, 1, 2, 3) " +
                         "ORDER BY t.jobtitlemark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, departmentId);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询部门下岗位失败", e);
            return Result.success(List.of());
        }
    }

    // ==================== 人员 ====================

    @Operation(summary = "分页查询指定部门下的人员")
    @GetMapping("/resources/by-department")
    public Result<PageResult<Map<String, Object>>> pageResourcesByDepartment(
            @RequestParam Long departmentId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, lastname, departmentid FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3) AND departmentid=? ORDER BY lastname";
            String countSql = "SELECT COUNT(*) FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3) AND departmentid=?";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, List.<Object>of(departmentId), pageRequest));
        } catch (Exception e) {
            log.error("查询人员列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "分页查询所有人员")
    @GetMapping("/resources")
    public Result<PageResult<Map<String, Object>>> pageResources(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT id, lastname, departmentid FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3) ORDER BY lastname";
            String countSql = "SELECT COUNT(*) FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3)";
            return Result.success(JdbcPageHelper.page(jdbcTemplate, baseSql, countSql, pageRequest));
        } catch (Exception e) {
            log.error("查询人员列表失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "根据ID查询人员")
    @GetMapping("/resources/{id}")
    public Result<Map<String, Object>> getResourceById(@PathVariable Long id) {
        try {
            String sql = "SELECT r.id, r.lastname, " +
                         "r.departmentid as departmentid, " +
                         "r.jobtitle as jobtitle, " +
                         "d.departmentmark as departmentName, " +
                         "d.subcompanyid1, " +
                         "s.subcompanyname as companyName " +
                         "FROM HrmResource r " +
                         "LEFT JOIN HrmDepartment d ON r.departmentid = d.id " +
                         "LEFT JOIN HrmSubCompany s ON d.subcompanyid1 = s.id " +
                         "WHERE r.id = ? AND ISNULL(r.status, -1) IN (0, 1, 2, 3)";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, id);
            if (results.isEmpty()) {
                return Result.success(null);
            }
            return Result.success(results.get(0));
        } catch (Exception e) {
            log.error("查询人员信息失败", e);
            return Result.success(null);
        }
    }

    // ==================== 搜索 ====================

    @Operation(summary = "分页搜索部门")
    @GetMapping("/search/departments")
    public Result<PageResult<Map<String, Object>>> pageSearchDepartments(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT d.id, d.departmentmark, d.subcompanyid1, d.supdepid, " +
                         "s.subcompanyname as companyName, s.supsubcomid " +
                         "FROM HrmDepartment d " +
                         "LEFT JOIN HrmSubCompany s ON d.subcompanyid1 = s.id " +
                         "WHERE ISNULL(d.canceled, 0) = 0 " +
                         "AND d.departmentmark LIKE ? " +
                         "ORDER BY d.departmentmark";
            String countSql = "SELECT COUNT(*) FROM HrmDepartment WHERE ISNULL(canceled, 0) = 0 AND departmentmark LIKE ?";

            PageResult<Map<String, Object>> pageResult = JdbcPageHelper.page(
                jdbcTemplate, baseSql, countSql, List.<Object>of("%" + keyword + "%"), pageRequest);

            // 为分页后的结果构建公司层级路径
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> dept : pageResult.getList()) {
                Map<String, Object> item = new HashMap<>(dept);
                item.put("companyPath", buildCompanyPath(dept));
                enriched.add(item);
            }
            pageResult.setList(enriched);

            return Result.success(pageResult);
        } catch (Exception e) {
            log.error("搜索部门失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    @Operation(summary = "分页搜索岗位")
    @GetMapping("/search/job-titles")
    public Result<PageResult<Map<String, Object>>> pageSearchJobTitles(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String baseSql = "SELECT t.id, t.jobtitlemark, t.jobactivityid, " +
                         "a.jobactivitymark as activityName, " +
                         "a.jobgroupid, " +
                         "g.jobgroupremark as groupName " +
                         "FROM HrmJobTitles t " +
                         "LEFT JOIN HrmJobActivities a ON t.jobactivityid = a.id " +
                         "LEFT JOIN HrmJobGroups g ON a.jobgroupid = g.id " +
                         "WHERE ISNULL(t.canceled, 0) = 0 " +
                         "AND t.jobtitlemark LIKE ? " +
                         "ORDER BY t.jobtitlemark";
            String countSql = "SELECT COUNT(*) FROM HrmJobTitles WHERE ISNULL(canceled, 0) = 0"
                    + " AND jobtitlemark LIKE ?";

            return Result.success(JdbcPageHelper.page(
                jdbcTemplate, baseSql, countSql, List.<Object>of("%" + keyword + "%"), pageRequest));
        } catch (Exception e) {
            log.error("搜索岗位失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    /**
     * 分页搜索人员（支持姓名和拼音）。
     *
     * <p>默认返回全量在岗人员。发起测评时岗位由发起人当场选择（方案 17 增补，2026-11-21），
     * 被测人不再受「必须已配置岗位」的限制。</p>
     *
     * <p>{@code departmentIds}（2026-11-22 增补）：只搜这些部门的人。发起页的「被测人」用它
     * 把候选收敛到「有启用岗位的部门」——既然只配了 A 部门两个岗位，就不该能选 B 部门的人。
     * 不传或传空表示不限部门，360 测评人仍走全量（跨部门评价本就是 360 的语义）。</p>
     */
    @Operation(summary = "分页搜索人员（支持姓名和拼音）")
    @GetMapping("/search/resources")
    public Result<PageResult<Map<String, Object>>> pageSearchResources(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) List<Long> departmentIds) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);

            String searchPattern = "%" + keyword + "%";

            // 部门白名单：base 与 count 必须共用同一段条件，否则总数和列表对不上。
            // 参数顺序跟随 SQL —— deptFilter 在 LIKE 之前，所以部门 ID 也要排在前面。
            List<Object> params = new ArrayList<>();
            String deptFilter = "";
            if (departmentIds != null && !departmentIds.isEmpty()) {
                StringBuilder in = new StringBuilder();
                for (Long deptId : departmentIds) {
                    if (deptId == null || deptId <= 0) {
                        continue;
                    }
                    in.append(in.length() == 0 ? "" : ", ").append("?");
                    params.add(deptId);
                }
                // 全是非法 ID 时不能退化成「不过滤」——那等于把闸门悄悄拆了
                if (in.length() == 0) {
                    return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
                }
                deptFilter = "AND r.departmentid IN (" + in + ") ";
            }
            params.add(searchPattern);
            params.add(searchPattern);

            String baseSql = "SELECT r.id, r.lastname, " +
                         "r.departmentid as departmentid, " +
                         "r.jobtitle as jobtitle, " +
                         "d.departmentmark as departmentName, " +
                         "d.subcompanyid1, " +
                         "s.subcompanyname as companyName " +
                         "FROM HrmResource r " +
                         "LEFT JOIN HrmDepartment d ON r.departmentid = d.id " +
                         "LEFT JOIN HrmSubCompany s ON d.subcompanyid1 = s.id " +
                         "WHERE ISNULL(r.status, -1) IN (0, 1, 2, 3) " +
                         deptFilter +
                         "AND (r.lastname LIKE ? OR r.ecology_pinyin_search LIKE ?)" +
                         " ORDER BY r.lastname";
            String countSql = "SELECT COUNT(*) FROM HrmResource r WHERE ISNULL(r.status, -1) IN (0, 1, 2, 3) " +
                         deptFilter +
                         "AND (r.lastname LIKE ? OR r.ecology_pinyin_search LIKE ?)";

            return Result.success(JdbcPageHelper.page(
                jdbcTemplate, baseSql, countSql, params, pageRequest));
        } catch (Exception e) {
            log.error("搜索人员失败", e);
            return Result.success(new PageResult<>(List.of(), 0L, pageNum, pageSize));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建公司层级路径
     */
    private List<Map<String, Object>> buildCompanyPath(Map<String, Object> dept) {
        List<Map<String, Object>> companyPath = new ArrayList<>();
        Object subcompanyId = dept.get("subcompanyid1");
        if (subcompanyId != null) {
            Long currentId = ((Number) subcompanyId).longValue();
            List<Map<String, Object>> pathReversed = new ArrayList<>();

            while (currentId != null && currentId != 0) {
                String companySql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE id = ? AND ISNULL(canceled, 0) = 0";
                List<Map<String, Object>> companyList = jdbcTemplate.queryForList(companySql, currentId);
                if (companyList.isEmpty()) break;

                Map<String, Object> company = companyList.get(0);
                pathReversed.add(0, company);

                Object parentId = company.get("supsubcomid");
                if (parentId == null || ((Number) parentId).longValue() == 0) break;
                currentId = ((Number) parentId).longValue();
            }

            companyPath = pathReversed;
        }
        return companyPath;
    }
}
