package com.company.ability.support;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.dto.PaCategoryCreateDTO;
import com.company.ability.dto.PaItemCreateDTO;
import com.company.ability.dto.PaItemLevelDTO;
import com.company.ability.dto.PaPostCreateDTO;
import com.company.ability.security.LoginUser;
import com.company.ability.service.DimensionIntegrationService;
import com.company.ability.service.PaModelService;
import com.company.ability.service.PaPostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 测评重构（方案 17）P2 测试的公共夹具。
 *
 * <p>为什么单独一个类：发起 / 填写 / 撤回三块测试都要「一个岗位 + 一套启用中的能力项 +
 * 一条人员岗位映射 + 若干在职人员」。各写一份的话，某天等级排序规则一改，四个文件一起红，
 * 且改法各不相同。这里保证四块测的是同一套数据形态。</p>
 */
public final class PaTestData {

    /** 测试维度（类租户） */
    public static final long DIM = 1L;
    /** 部门：人力资源部 */
    public static final long DEPT = 801L;
    /** 被测人 */
    public static final long TARGET = 8101L;
    /** 直接上级（被测人的 managerid） */
    public static final long LEADER = 8102L;
    /** 360 测评人 */
    public static final long RATER_A = 8103L;
    public static final long RATER_B = 8104L;
    /** 离职人员：status = 5 */
    public static final long RESIGNED = 8105L;
    /** 无 OA 登录名：登不进系统 */
    public static final long NO_LOGIN = 8106L;

    public static final int YEAR = 2026;

    private final JdbcTemplate jdbc;

    /** 用于在种完配置后清缓存：集成配置是整表缓存的，测试改库不会自动生效 */
    private final DimensionIntegrationService integrationService;

    public PaTestData(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public PaTestData(JdbcTemplate jdbc, DimensionIntegrationService integrationService) {
        this.jdbc = jdbc;
        this.integrationService = integrationService;
    }

    /** 清空所有 uf_pa_* / 任务表 / 人员表，让每条用例从同一片干净土地开始 */
    public void reset() {
        for (String t : List.of("uf_assess_task_rater_item", "uf_pa_rating", "uf_pa_sheet_item", "uf_pa_sheet",
                "uf_pa_band", "uf_pa_item_level", "uf_pa_item", "uf_pa_category", "uf_pa_post",
                "uf_assess_task_rater", "uf_assess_task")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM uf_assess_period");
        jdbc.update("DELETE FROM uf_assess_dimension_auth");
        jdbc.update("DELETE FROM uf_assess_dimension_integration");
        jdbc.update("DELETE FROM uf_assess_dimension");
        jdbc.update("DELETE FROM HrmDepartment");
        jdbc.update("DELETE FROM HrmResource");
        jdbc.update("DELETE FROM uf_push_msg");
        seedIntegration();
    }

    /**
     * 维度集成配置：推送渠道 = 泛微。
     *
     * <p>2026-11-24 起入队侧按维度配置的 {@code push_channel} 决定往哪推、维度没配就不入队
     * （见 AssessPushService#resolveChannel）。测试库若没有这行配置，所有消息都会被跳过，
     * 「应发 N 条」的断言全部变成 0 —— 所以它属于基础夹具，和人员数据一起准备。</p>
     *
     * <p>维度主表那行也要建：读配置的 SQL 与 {@code uf_assess_dimension} 内连，
     * 只看集成表的话，缺维度行会让配置整行被过滤掉（表现为「配置写了却不生效」）。</p>
     */
    private void seedIntegration() {
        jdbc.update("INSERT INTO uf_assess_dimension (id, dimension_name, dimension_code, status, sort, deleted)"
                + " VALUES (?, '默认维度', 'DEFAULT', 1, 0, 0)", DIM);
        // 密钥用明文：测试环境没配 integration.master-key，写 enc:v1: 前缀会解密失败。
        // 服务本身兼容明文（手工填库调试路径），这里正好走那条分支。
        jdbc.update("INSERT INTO uf_assess_dimension_integration (dimension_id, sso_enabled, sso_provider_type,"
                        + " sso_client_id, sso_client_secret, sso_base_url, sso_userinfo_field, sso_match_field,"
                        + " push_enabled, push_channel) VALUES (?, 1, 'weaver', ?, ?, ?, 'id', 'loginid', 1, 'weaver')",
                DIM, "test-client", "test-secret", "http://oa.example.com");
        // 配置是整表缓存的：上一条用例读过的快照会盖住这里刚插的行，必须清掉
        if (integrationService != null) {
            integrationService.invalidate();
        }
    }

    /** 建部门 + 年度 + 人员。返回 periodId */
    public long seedPeriod(PaPostService postService, int periodStatus) {
        jdbc.update("INSERT INTO HrmDepartment (id, departmentname, departmentmark, canceled) VALUES (?, ?, ?, '0')",
                DEPT, "人力资源部", "人力资源部");
        jdbc.update("INSERT INTO uf_assess_period (company_id, year, status, dimension_id) VALUES (1, ?, ?, ?)",
                YEAR, periodStatus, DIM);
        DimensionContext.set(DIM);
        return jdbc.queryForObject(
                "SELECT id FROM uf_assess_period WHERE dimension_id = ? AND year = ?", Long.class, DIM, YEAR);
    }

    /**
     * 全员：被测人、上级、两位 360。
     * status 取遍在岗四态（0 在职 / 1 试用 / 2 兼职 / 3 借调）——这些都必须能参与评价；
     * 另备一个离职（5）与一个无 OA 登录名的人，用于发起侧的闸门用例。
     */
    public void seedPeople() {
        person(TARGET, "张被测", "zhangbc", 1, LEADER, 0);
        person(LEADER, "李上级", "lishangji", 1, 0, 1);
        person(RATER_A, "王甲", "wangjia", 0, 0, 1);
        person(RATER_B, "赵乙", "zhaoyi", 2, 0, 1);
        person(RESIGNED, "孙离职", "sunlizhi", 5, 0, 1);
        person(NO_LOGIN, "周无账号", null, 1, 0, 1);
    }

    private void person(long id, String name, String loginid, int status, long managerId, long jobTitle) {
        jdbc.update("INSERT INTO HrmResource (id, lastname, status, loginid, departmentid, jobtitle, managerid," +
                        " subcompanyid1) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                id, name, status, loginid, DEPT, jobTitle, managerId);
    }

    /** 建岗位并挂一套能力项。返回 postId */
    public long seedPost(PaPostService postService, PaModelService modelService, String postName) {
        PaPostCreateDTO post = new PaPostCreateDTO();
        post.setDepartmentId(DEPT);
        post.setPostName(postName);
        return postService.createPost(post);
    }

    /**
     * 建一个能力项。
     *
     * @param raterLeader 1 表示直接上级要评
     * @param rater360    1 表示 360 测评人也要评
     */
    public long item(PaModelService modelService, long postId, long categoryId, String name,
                     String weight, int raterLeader, int rater360) {
        PaItemCreateDTO dto = new PaItemCreateDTO();
        dto.setCategoryId(categoryId);
        dto.setItemName(name);
        dto.setWeight(new BigDecimal(weight));
        dto.setRaterLeader(raterLeader);
        dto.setRater360(rater360);
        // 两档就够用：算分只看 level.score，档位个数与算分无关
        dto.setLevels(List.of(level("待提升", "2", 1), level("优秀", "5", 2)));
        return modelService.createItem(postId, dto);
    }

    public long category(PaModelService modelService, long postId, String name) {
        PaCategoryCreateDTO dto = new PaCategoryCreateDTO();
        dto.setCategoryName(name);
        return modelService.createCategory(postId, dto);
    }

    public static PaItemLevelDTO level(String name, String score, int sort) {
        PaItemLevelDTO dto = new PaItemLevelDTO();
        dto.setLevelName(name);
        dto.setScore(new BigDecimal(score));
        dto.setSort(sort);
        return dto;
    }

    /** 三档分值区间：[null,2) / [2,4) / [4,null) */
    public void bands(PaModelService modelService, long postId) {
        List<PaBandSaveDTO> bands = new ArrayList<>();
        bands.add(band("待提升", null, "2", 0));
        bands.add(band("合格", "2", "4", 1));
        bands.add(band("优秀", "4", null, 2));
        modelService.saveBands(postId, bands);
    }

    private static PaBandSaveDTO band(String name, String lower, String upper, int sort) {
        PaBandSaveDTO dto = new PaBandSaveDTO();
        dto.setBandName(name);
        dto.setLowerBound(lower == null ? null : new BigDecimal(lower));
        dto.setUpperBound(upper == null ? null : new BigDecimal(upper));
        dto.setSort(sort);
        return dto;
    }

    /** 让 {@code UserContext.currentOrNull()} 返回该人员：填写页要校验「登录人 == 评价人」 */
    public static void login(long resourceId) {
        loginAs(resourceId, "u" + resourceId);
    }

    /** 指定 OA 登录名登录：管理员判定看 loginid（{@code uf_assess_admin.loginid}） */
    public static void loginAs(long resourceId, String loginid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(loginid, resourceId, "测试人", DEPT, 1L), null, List.of()));
    }

    public static void logout() {
        SecurityContextHolder.clearContext();
    }
}
