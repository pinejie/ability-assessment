package com.company.ability.controller;

import com.company.ability.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @Operation(summary = "查询所有分公司")
    @GetMapping("/sub-companies")
    public Result<List<Map<String, Object>>> listSubCompanies() {
        try {
            String sql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE ISNULL(canceled, 0)=0 ORDER BY subcompanyname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询分公司列表失败", e);
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

    @Operation(summary = "查询所有部门")
    @GetMapping("/departments")
    public Result<List<Map<String, Object>>> listDepartments() {
        try {
            String sql = "SELECT id, departmentmark, supdepid, subcompanyid1 FROM HrmDepartment WHERE ISNULL(canceled, 0)=0 ORDER BY departmentmark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询部门列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询岗位类别")
    @GetMapping("/job-groups")
    public Result<List<Map<String, Object>>> listJobGroups() {
        try {
            String sql = "SELECT id, jobgroupremark FROM HrmJobGroups WHERE id IN (707, 708) ORDER BY jobgroupremark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            // 添加 hasChildren 字段：岗位类别有子节点（职务）
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
            // 添加 hasChildren 字段：职务有子节点（岗位）
            for (Map<String, Object> activity : list) {
                activity.put("hasChildren", true);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询职务列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询职务")
    @GetMapping("/job-activities")
    public Result<List<Map<String, Object>>> listJobActivities() {
        try {
            String sql = "SELECT id, jobactivitymark, jobgroupid FROM HrmJobActivities ORDER BY jobactivitymark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询职务列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询指定职务下的岗位")
    @GetMapping("/job-titles/by-activity")
    public Result<List<Map<String, Object>>> listJobTitlesByActivity(@RequestParam Long activityId) {
        try {
            String sql = "SELECT id, jobtitlemark, jobactivityid FROM HrmJobTitles WHERE ISNULL(canceled, 0)=0 AND jobactivityid=? ORDER BY jobtitlemark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, activityId);
            // 添加 hasChildren 字段：岗位是叶子节点，没有子节点
            for (Map<String, Object> title : list) {
                title.put("hasChildren", false);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询岗位列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询所有岗位")
    @GetMapping("/job-titles")
    public Result<List<Map<String, Object>>> listJobTitles() {
        try {
            String sql = "SELECT id, jobtitlemark, jobactivityid FROM HrmJobTitles WHERE ISNULL(canceled, 0)=0 ORDER BY jobtitlemark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询岗位列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询指定部门下的人员")
    @GetMapping("/resources/by-department")
    public Result<List<Map<String, Object>>> listResourcesByDepartment(@RequestParam Long departmentId) {
        try {
            String sql = "SELECT id, lastname, departmentid FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3) AND departmentid=? ORDER BY lastname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, departmentId);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询人员列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "查询所有人员")
    @GetMapping("/resources")
    public Result<List<Map<String, Object>>> listResources() {
        try {
            String sql = "SELECT id, lastname, departmentid FROM HrmResource WHERE ISNULL(status, -1) IN (0, 1, 2, 3) ORDER BY lastname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql);
            return Result.success(list);
        } catch (Exception e) {
            log.error("查询人员列表失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "搜索部门")
    @GetMapping("/search/departments")
    public Result<List<Map<String, Object>>> searchDepartments(@RequestParam String keyword) {
        try {
            // 查询匹配的部门
            String sql = "SELECT d.id, d.departmentmark, d.subcompanyid1, d.supdepid, " +
                         "s.subcompanyname as companyName, s.supsubcomid " +
                         "FROM HrmDepartment d " +
                         "LEFT JOIN HrmSubCompany s ON d.subcompanyid1 = s.id " +
                         "WHERE ISNULL(d.canceled, 0) = 0 " +
                         "AND d.departmentmark LIKE ? " +
                         "ORDER BY d.departmentmark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, "%" + keyword + "%");

            // 为每个部门构建公司层级路径
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map<String, Object> dept : list) {
                Map<String, Object> item = new java.util.HashMap<>(dept);

                // 构建公司路径（从根公司到当前公司）
                List<Map<String, Object>> companyPath = new java.util.ArrayList<>();
                Object subcompanyId = dept.get("subcompanyid1");
                if (subcompanyId != null) {
                    Long currentId = ((Number) subcompanyId).longValue();
                    List<Map<String, Object>> pathReversed = new java.util.ArrayList<>();

                    // 向上追溯公司层级
                    while (currentId != null && currentId != 0) {
                        String companySql = "SELECT id, subcompanyname, supsubcomid FROM HrmSubCompany WHERE id = ? AND ISNULL(canceled, 0) = 0";
                        List<Map<String, Object>> companyList = jdbcTemplate.queryForList(companySql, currentId);
                        if (companyList.isEmpty()) break;

                        Map<String, Object> company = companyList.get(0);
                        pathReversed.add(0, company); // 插入到开头，保持从根到叶的顺序

                        Object parentId = company.get("supsubcomid");
                        if (parentId == null || ((Number) parentId).longValue() == 0) break;
                        currentId = ((Number) parentId).longValue();
                    }

                    companyPath = pathReversed;
                }

                item.put("companyPath", companyPath);
                result.add(item);
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("搜索部门失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "搜索岗位")
    @GetMapping("/search/job-titles")
    public Result<List<Map<String, Object>>> searchJobTitles(@RequestParam String keyword) {
        try {
            String sql = "SELECT t.id, t.jobtitlemark, t.jobactivityid, " +
                         "a.jobactivitymark as activityName, " +
                         "a.jobgroupid, " +
                         "g.jobgroupremark as groupName " +
                         "FROM HrmJobTitles t " +
                         "LEFT JOIN HrmJobActivities a ON t.jobactivityid = a.id " +
                         "LEFT JOIN HrmJobGroups g ON a.jobgroupid = g.id " +
                         "WHERE ISNULL(t.canceled, 0) = 0 " +
                         "AND t.jobtitlemark LIKE ? " +
                         "ORDER BY t.jobtitlemark";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, "%" + keyword + "%");
            return Result.success(list);
        } catch (Exception e) {
            log.error("搜索岗位失败", e);
            return Result.success(List.of());
        }
    }

    @Operation(summary = "搜索人员")
    @GetMapping("/search/resources")
    public Result<List<Map<String, Object>>> searchResources(@RequestParam String keyword) {
        try {
            String sql = "SELECT r.id, r.lastname, r.departmentid, " +
                         "d.departmentmark as departmentName, " +
                         "d.subcompanyid1, " +
                         "s.subcompanyname as companyName, s.supsubcomid " +
                         "FROM HrmResource r " +
                         "LEFT JOIN HrmDepartment d ON r.departmentid = d.id " +
                         "LEFT JOIN HrmSubCompany s ON d.subcompanyid1 = s.id " +
                         "WHERE ISNULL(r.status, -1) IN (0, 1, 2, 3) " +
                         "AND r.lastname LIKE ? " +
                         "ORDER BY r.lastname";
            List<Map<String, Object>> list = jdbcTemplate.queryForList(sql, "%" + keyword + "%");

            // 为每个人员构建公司层级路径
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map<String, Object> resource : list) {
                Map<String, Object> item = new java.util.HashMap<>(resource);

                // 构建公司路径（从根公司到当前公司）
                List<Map<String, Object>> companyPath = new java.util.ArrayList<>();
                Object subcompanyId = resource.get("subcompanyid1");
                if (subcompanyId != null) {
                    Long currentId = ((Number) subcompanyId).longValue();
                    List<Map<String, Object>> pathReversed = new java.util.ArrayList<>();

                    // 向上追溯公司层级
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

                item.put("companyPath", companyPath);
                result.add(item);
            }

            return Result.success(result);
        } catch (Exception e) {
            log.error("搜索人员失败", e);
            return Result.success(List.of());
        }
    }
}
