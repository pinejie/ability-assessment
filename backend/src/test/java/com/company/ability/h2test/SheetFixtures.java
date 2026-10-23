package com.company.ability.h2test;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测评域 H2 测试共享辅助：AssessSheetSyncServiceTest / AssessSheetQueryServiceTest / AssessReportServiceTest
 * 的通用种子与清理。表清单集中一处，避免多份漂移。
 */
public final class SheetFixtures {

    public static final long RESOURCE_ID = 100L;
    public static final int YEAR = 2026;

    /** 测评域测试涉及的全部业务表（cleanAll 顺序无关，均为全表删除） */
    private static final String[] ALL_TABLES = {
            "uf_assess_sheet_rating", "uf_assess_comment", "uf_user_ability_req_item", "uf_user_ability_req_new",
            "uf_assess_sheet_log", "uf_assess_sheet_row", "uf_assess_sheet", "uf_assess_period",
            "uf_assess_level_score", "uf_assess_weight_ability", "uf_assess_weight_assessor", "uf_assess_grade_level",
            "uf_ability_element_level", "uf_ability_element", "uf_ability_category", "HrmResource"};

    private SheetFixtures() {
    }

    public static void cleanAll(JdbcTemplate jdbc) {
        for (String t : ALL_TABLES) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    public static long idOf(JdbcTemplate jdbc, String selectSql) {
        return jdbc.queryForObject(selectSql, Long.class);
    }

    public static long insertReturning(JdbcTemplate jdbc, String insertSql, String selectSql) {
        jdbc.update(insertSql);
        return idOf(jdbc, selectSql);
    }

    public static long insertCategory(JdbcTemplate jdbc, String name) {
        return insertReturning(jdbc,
                "INSERT INTO uf_ability_category (category_name, status, deleted) VALUES ('" + name + "', 1, 0)",
                "SELECT id FROM uf_ability_category WHERE category_name = '" + name + "'");
    }

    public static long insertElement(JdbcTemplate jdbc, String name, long categoryId, int assessInFlow) {
        return insertReturning(jdbc,
                "INSERT INTO uf_ability_element (element_name, category_id, assess_in_flow, allow_client_assess, " +
                        "status, sort, deleted) VALUES ('" + name + "', " + categoryId + ", " + assessInFlow +
                        ", 1, 1, 0, 0)",
                "SELECT id FROM uf_ability_element WHERE element_name = '" + name + "'");
    }

    /** 要素等级配置：统一 2 级、分值 40（配合等级分值表 2 级=40） */
    public static long insertLevel2(JdbcTemplate jdbc, long elementId) {
        jdbc.update("INSERT INTO uf_ability_element_level (element_id, level, level_name, score) VALUES (?, 2, '二级', 40)",
                elementId);
        return idOf(jdbc, "SELECT id FROM uf_ability_element_level WHERE element_id = " + elementId + " AND level = 2");
    }

    public static void insertPeriod(JdbcTemplate jdbc, int year) {
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, 0, 1)", year);
    }

    public static void insertResource(JdbcTemplate jdbc, long resourceId) {
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, departmentid, jobtitle, managerid) " +
                "VALUES (?, '张三', 1, 5, 7, 88)", resourceId);
    }

    public static void insertCategoryWeight(JdbcTemplate jdbc, long categoryId, int weight) {
        jdbc.update("INSERT INTO uf_assess_weight_ability (company_id, dimension_id, category_id, weight) VALUES (1, 1, ?, ?)",
                categoryId, weight);
    }

    /** 等级分值 1..5 级 = 20..100（rate_value 恒等于等级号，等价于旧"等级=评价"隐式映射） */
    public static void insertLevelScores(JdbcTemplate jdbc) {
        for (int level = 1; level <= 5; level++) {
            jdbc.update("INSERT INTO uf_assess_level_score (company_id, dimension_id, level_no, level_desc, rate_value, score) " +
                    "VALUES (1, 1, ?, ?, ?, ?)", level, "等级" + level, level, level * 20);
        }
    }

    public static long insertRequirement(JdbcTemplate jdbc, long resourceId, int year) {
        return insertReturning(jdbc,
                "INSERT INTO uf_user_ability_req_new (resource_id, assess_year, dimension_id) VALUES ("
                        + resourceId + ", " + year + ", 1)",
                "SELECT id FROM uf_user_ability_req_new WHERE resource_id = " + resourceId + " AND assess_year = " + year);
    }

    /** 要求明细 + 配套 2 级等级 */
    public static void addReqItem(JdbcTemplate jdbc, long reqId, long elementId, long categoryId) {
        jdbc.update("INSERT INTO uf_user_ability_req_item (req_id, element_id, category_id, level_id) VALUES (?, ?, ?, ?)",
                reqId, elementId, categoryId, insertLevel2(jdbc, elementId));
    }

    public static long sheetId(JdbcTemplate jdbc, long resourceId, int year) {
        return jdbc.queryForObject("SELECT s.id FROM uf_assess_sheet s " +
                "JOIN uf_assess_period p ON p.id = s.period_id " +
                "WHERE p.year = ? AND s.resource_id = ?", Long.class, year, resourceId);
    }

    public static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
