package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessTaskDTO;
import com.company.ability.entity.AssessTask;
import com.company.ability.entity.AssessTaskRater;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessTaskMapper;
import com.company.ability.mapper.AssessTaskRaterMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.AssessTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测评任务发起（S1）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §4.1 主流程、§4.2 角色要素分配、§7.1
 *
 * 职责边界：
 * - 本服务只负责「把一次评价征集发起起来」：校验 → 同步页面 → 生成评价人 → 发链接。
 * - 评级写入与算分在 {@link AssessFillService}；撤回/取消/催办在 S3。
 *
 * 发起前必须全部通过，任一失败整单回滚：
 * ① 被测人有人员能力要求（否则无页可评）
 * ② 年度存在且未关闭
 * ③ 领导存在且 != 被测人
 * ④ 客户名单与「客户可评要素」一致：有客户要素则非空，无则必须为空
 * ⑤ 该页面没有进行中的任务（避免两人同时评、互相覆盖）
 */
@Slf4j
@Service
public class AssessTaskService {

    static final int ERR_TASK_EXISTS = 40301;
    static final int ERR_NO_REQUIREMENT = 40302;
    static final int ERR_PERIOD_CLOSED = 40303;
    static final int ERR_LEADER_INVALID = 40304;
    static final int ERR_CLIENT_MISMATCH = 40305;
    /** 被测人本身不可用（不存在 / 无登录名 / 已离职等），见 RaterEligibilityService.Reason */
    static final int ERR_TARGET_INVALID = 40306;

    /** 进行中 */
    private static final int TASK_RUNNING = 0;
    /** 任务已撤回/取消：链接失效的两种终态 */
    static final int RATER_PENDING = 0;
    static final int RATER_SUBMITTED = 1;
    static final int RATER_VOIDED = 3;

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AssessTaskMapper taskMapper;
    private final AssessTaskRaterMapper raterMapper;
    private final AssessSheetSyncService syncService;
    private final AssessPushService pushService;
    private final RaterEligibilityService eligibility;
    private final long companyId;

    public AssessTaskService(JdbcTemplate jdbc, AssessTaskMapper taskMapper,
                             AssessTaskRaterMapper raterMapper, AssessSheetSyncService syncService,
                             AssessPushService pushService, RaterEligibilityService eligibility,
                             @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.taskMapper = taskMapper;
        this.raterMapper = raterMapper;
        this.syncService = syncService;
        this.pushService = pushService;
        this.eligibility = eligibility;
        this.companyId = companyId;
    }

    /**
     * 发起测评任务。
     *
     * 要素口径统一按 uf_ability_element 的开关判定（§6.5）：
     * - 领导/自评：assess_in_flow = 1
     * - 客户：assess_in_flow = 1 且 allow_client_assess = 1
     * 不读 uf_assess_sheet_row.active —— 两者已由 sql/40 对齐，但口径来源只有要素表一处。
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessTaskVO.CreateResult create(AssessTaskDTO.CreateRequest req) {
        long dimensionId = DimensionContext.getOrDefault();
        long operatorId = currentOperatorId();

        // 1. 年度必须存在且未关闭
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, req.getYear(), dimensionId);
        if (periods.isEmpty()) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    req.getYear() + " 年度不存在，请先在当前维度下的年度设置中新增");
        }
        if (num(periods.get(0).get("status")) == 1) {
            throw new BusinessException(ERR_PERIOD_CLOSED, req.getYear() + " 年度已关闭，不能发起测评");
        }
        long periodId = num(periods.get(0).get("id"));

        // 2. 被测人必须已有人员能力要求（这是页面的唯一来源）
        Integer reqCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_user_ability_req_new WHERE resource_id = ? AND assess_year = ? AND dimension_id = ?",
                Integer.class, req.getResourceId(), req.getYear(), dimensionId);
        if (reqCount == null || reqCount == 0) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    "该人员没有 " + req.getYear() + " 年度的人员能力要求，请先在「人员能力要求配置」中配置后再发起");
        }

        // 3. 同步页面（复用既有服务：生成/更新页面头与页面行、算标准分）。
        //    同步后页面一定存在，且要素开关与页面行一致。
        syncService.syncRequirement(req.getResourceId(), req.getYear(), true);

        long sheetId = num(jdbc.queryForObject(
                "SELECT id FROM uf_assess_sheet WHERE period_id = ? AND resource_id = ?",
                Long.class, periodId, req.getResourceId()));

        // 4. 重复发起保护：同一页面同时只能有一个进行中的任务
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task WHERE sheet_id = ? AND status = ?",
                Integer.class, sheetId, TASK_RUNNING);
        if (running != null && running > 0) {
            throw new BusinessException(ERR_TASK_EXISTS,
                    "该被测人本期已有进行中的测评任务，请先撤回后再重新发起");
        }

        // 5. 角色与要素范围
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.element_id, ISNULL(e.assess_in_flow, 1) AS assess_in_flow, " +
                "       ISNULL(e.allow_client_assess, 1) AS allow_client_assess " +
                "FROM uf_assess_sheet_row r JOIN uf_ability_element e ON e.id = r.element_id " +
                "WHERE r.sheet_id = ? AND e.deleted = 0 AND ISNULL(e.assess_in_flow, 1) = 1",
                sheetId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    "该人员没有需要评价的能力要素，请先在人员能力要求中勾选「流程中评价」");
        }
        int ratingCount = rows.size();
        boolean hasClientElement = rows.stream().anyMatch(m -> num(m.get("allow_client_assess")) == 1);

        // 5b. 口径一致性护栏（§14 阻断级风险）。
        //     存储过程按 uf_assess_sheet_row.active 判定有效要素，网页按 uf_ability_element.assess_in_flow。
        //     两者不一致时「网页收 19 条、@n 算成 20」→ 领导/自评 COUNT(*) <> @n → 全员提交也不出分。
        //     sql/40 已对齐存量数据；此处兜住后续漂移，宁可发起失败也不发一个注定算不出分的任务。
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_sheet_row r JOIN uf_ability_element e ON e.id = r.element_id " +
                "WHERE r.sheet_id = ? AND r.active = 1 AND e.deleted = 0 AND ISNULL(e.assess_in_flow, 1) = 1",
                Integer.class, sheetId);
        if (activeCount == null || activeCount != ratingCount) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    "测评页面有效要素与能力要素开关不一致（页面 " + activeCount + " 条 / 要素 " + ratingCount
                            + " 条），请重新保存该人员的人员能力要求后再发起");
        }

        // 5c. 另一种口径缺口：usp_assess_recalc 的 #act 还会纳入「inactive 但有 EXTERNAL 评级」的行，
        //     并计入 @n。这类行领导/自评无法评价，任务将永远凑不齐计数、分数恒为 NULL。
        //     外部导入通道与网页通道不可在同一页面混用，此处直接拒绝并说明。
        Integer orphanExternal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_sheet_row r JOIN uf_ability_element e ON e.id = r.element_id " +
                "WHERE r.sheet_id = ? AND r.active = 0 AND ISNULL(e.assess_in_flow, 1) = 0 AND EXISTS (" +
                "  SELECT 1 FROM uf_assess_sheet_rating x WHERE x.sheet_id = r.sheet_id" +
                "    AND x.element_id = r.element_id AND x.source = 'EXTERNAL')",
                Integer.class, sheetId);
        if (orphanExternal != null && orphanExternal > 0) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    "该页面有 " + orphanExternal + " 个「流程中不评价」的要素存在外部导入评级，"
                            + "网页评价无法覆盖这些要素，发起后将算不出分。请先清空外部导入或调整要素开关");
        }

        // 6. 直接领导：只认 OA 组织关系，不再接受人工指定。
        //    发起页把上级做成只读展示（2026-11-14），若这里仍允许入参覆盖，
        //    页面显示的上级与实际落库的上级就会是两个人，所以以 HR 为唯一口径。
        //    入参 leaderResourceId 保留但不再参与决策，仅用于发现调用方口径不一致时告警。
        AssessTaskVO.LeaderInfo leaderInfo = resolveLeader(req.getResourceId());
        if (req.getLeaderResourceId() != null && !req.getLeaderResourceId().equals(leaderInfo.getLeaderResourceId())) {
            log.warn("发起测评 leaderResourceId 入参与 OA 组织关系不一致，以 OA 为准: resourceId={}, 入参={}, OA={}",
                    req.getResourceId(), req.getLeaderResourceId(), leaderInfo.getLeaderResourceId());
        }
        Long leaderId = leaderInfo.getLeaderResourceId();
        if (leaderId == null || leaderId == 0L) {
            throw new BusinessException(ERR_LEADER_INVALID,
                    "「" + nameOfResource(req.getResourceId()) + "」在 OA 中没有直接上级，请先在泛微维护组织关系后再发起");
        }
        if (leaderId.equals(req.getResourceId())) {
            throw new BusinessException(ERR_LEADER_INVALID, "直接上级不能是被测人本人");
        }

        // 7. 客户名单：有客户可评要素时必须非空，没有时必须为空
        //    （后者防止录入了不生效的客户，发起后既收不到要素又占着任务名额）
        List<Long> clientIds = dedupe(req.getClientResourceIds());
        if (hasClientElement && clientIds.isEmpty()) {
            throw new BusinessException(ERR_CLIENT_MISMATCH,
                    "该人员存在需要客户评价的要素，请至少选择一位客户评价人");
        }
        if (!hasClientElement && !clientIds.isEmpty()) {
            throw new BusinessException(ERR_CLIENT_MISMATCH,
                    "该人员的全部要素都设置为「不需客户评价」，不能指定客户评价人");
        }
        for (Long clientId : clientIds) {
            if (clientId.equals(req.getResourceId())) {
                throw new BusinessException(ERR_CLIENT_MISMATCH, "被测人本人不能作为客户评价人");
            }
        }

        // 7b. 全员可登录性校验（发起侧闸门，2026-11-13 新增）。
        //     被测人 / 直接上级 / 全部客户评价人一起校验，判据与 SSO 登录逐字对齐
        //     （status IN (0,1,2,3) 且 loginid 非空），见 RaterEligibilityService。
        //     唯一的硬门槛是「没有 OA 登录名」（实测 1343 人）——
        //     他们一旦被选中，链接从发出的那一刻就是死链，任务永远凑不齐人、永远算不出分。
        //     状态口径 2026-11-13 已放宽到在岗四态（业务口径：只要在职就可以），
        //     试用/临时/试用延期不再拦截。
        //     这里一次性查全并列出**所有**问题人员，避免 HR 反复试错。
        assertAllCanLogin(req.getResourceId(), leaderId, clientIds);

        // 8. 建任务
        AssessTask task = new AssessTask();
        task.setCompanyId(companyId);
        task.setDimensionId(dimensionId);
        task.setPeriodId(periodId);
        task.setSheetId(sheetId);
        task.setResourceId(req.getResourceId());
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT department_id, job_title_id FROM uf_assess_sheet WHERE id = ?", sheetId);
        task.setDepartmentId(head.get("department_id") == null ? null : num(head.get("department_id")));
        task.setJobTitleId(head.get("job_title_id") == null ? null : num(head.get("job_title_id")));
        task.setLeaderResourceId(leaderId);
        task.setStatus(TASK_RUNNING);
        task.setRatingCount(ratingCount);
        task.setClientCount(clientIds.size());
        task.setRemark(req.getRemark());
        task.setCreateBy(operatorId);
        taskMapper.insert(task);
        long taskId = task.getId();

        // 9. 生成评价人（自评 1 + 领导 1 + 客户 N），每人一个 token
        List<AssessTaskVO.RaterLink> links = new ArrayList<>();
        links.add(createRater(taskId, "SELF", req.getResourceId()));
        links.add(createRater(taskId, "LEADER", leaderId));
        for (Long clientId : clientIds) {
            links.add(createRater(taskId, "CLIENT", clientId));
        }

        // 10. 回填页面头：领导可能是发起时新指定的，让页面与任务口径一致
        jdbc.update("UPDATE uf_assess_sheet SET leader_resource_id = ?, client_count = ?, update_time = SYSDATETIME() " +
                "WHERE id = ?", leaderId, clientIds.size(), sheetId);

        writeLog(sheetId, "TASK_CREATE", String.format(
                "{\"taskId\":%d,\"raters\":%d,\"clients\":%d}", taskId, links.size(), clientIds.size()), dimensionId);

        AssessTaskVO.CreateResult result = new AssessTaskVO.CreateResult();
        result.setTaskId(taskId);
        result.setSheetId(sheetId);
        result.setStatus(TASK_RUNNING);
        result.setRatingCount(ratingCount);
        result.setClientCount(clientIds.size());
        result.setFillBaseUrl("/assess/fill/");
        result.setRaters(links);

        // 11. 写邀请消息（§8.5）。只插 uf_push_msg，不判重 —— 重复发起是新任务、新 token，
        //     本就该各发一条。消息写入失败不影响发起本身 —— 链接已在响应里返回，HR 可手工转达。
        try {
            int pushed = pushService.writeInvites(taskId, req.getYear(), ratingCount,
                    queryName(req.getResourceId()), links);
            result.setNoticeCount(pushed);
        } catch (Exception e) {
            log.warn("发起邀请消息写入失败 taskId={}: {}", taskId, e.getMessage());
            result.setNoticeCount(0);
            result.setNoticeError("邀请消息写入失败：" + e.getMessage() + "，请手工转达评价链接");
        }

        log.info("发起测评任务 taskId={}, sheetId={}, resourceId={}, 评价人={}（客户 {}）",
                taskId, sheetId, req.getResourceId(), links.size(), clientIds.size());
        return result;
    }

    // ==================== 私有 ====================

    /**
     * 解析被测人的直接上级（发起页只读展示 + 发起时的唯一口径）。
     *
     * 来源只有一处：{@code HrmResource.managerid}。AssessSheetSyncService 同步页面头时
     * 写的 {@code uf_assess_sheet.leader_resource_id} 也是从这里取的值，两者同源；
     * 直接读 HR 而不是读页面头，是为了让「人员还没同步过页面」的新人也能正确带出上级。
     *
     * 返回 null 字段而不抛异常：发起页需要在选择被测人的瞬间就知道「这个人能不能发起」，
     * 抛异常只会变成一个没人处理的 500。真正拦截放在 {@link #create} 里。
     */
    public AssessTaskVO.LeaderInfo resolveLeader(Long resourceId) {
        AssessTaskVO.LeaderInfo info = new AssessTaskVO.LeaderInfo();
        if (resourceId == null) {
            info.setMessage("请先选择被测人");
            return info;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.managerid, m.lastname AS leader_name, d.departmentname AS dept_name " +
                "FROM HrmResource r " +
                "LEFT JOIN HrmResource m ON m.id = r.managerid " +
                "LEFT JOIN HrmDepartment d ON d.id = m.departmentid " +
                "WHERE r.id = ?", resourceId);
        if (rows.isEmpty()) {
            info.setMessage("该人员不存在，请重新选择");
            return info;
        }
        Object managerId = rows.get(0).get("managerid");
        String leaderName = (String) rows.get(0).get("leader_name");
        if (managerId == null || num(managerId) == 0L || leaderName == null) {
            info.setMessage("该人员在 OA 中未维护直接上级，请先在泛微补齐组织关系后再发起");
            return info;
        }
        info.setLeaderResourceId(num(managerId));
        info.setLeaderName(leaderName);
        info.setDeptName((String) rows.get(0).get("dept_name"));
        return info;
    }

    /**
     * 发起前的人员预检：用与 {@link #create} 完全相同的判据，但把结果**全量返回**而不是抛异常。
     *
     * 存在的理由：create 一次只报一个错误码，前端拿到后再逐条改、逐条试，
     * HR 要来回好几轮。预检让表单填完就能看到全部问题人员。
     *
     * 注意：预检通过不代表 create 一定成功 —— 年度是否关闭、是否已有进行中任务、
     * 要素口径是否一致等闸门仍在 create 里。预检只负责「人」这一层。
     */
    public AssessTaskVO.PrecheckResult precheck(AssessTaskDTO.PrecheckRequest req) {
        // 上级口径与 create 完全对齐：一律取 OA 组织关系，不再读页面头。
        // 读页面头的老写法在「被测人还没同步过页面」时拿不到上级，上级就漏检了。
        Long leaderId = resolveLeader(req.getResourceId()).getLeaderResourceId();

        List<Long> clientIds = dedupe(req.getClientResourceIds());
        List<Long> all = new ArrayList<>();
        all.add(req.getResourceId());
        if (leaderId != null && leaderId > 0) {
            all.add(leaderId);
        }
        all.addAll(clientIds);

        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(req.getResourceId(), "被测人");
        if (leaderId != null && leaderId > 0) {
            roles.put(leaderId, "直接上级");
        }
        for (Long clientId : clientIds) {
            roles.put(clientId, "客户评价人");
        }

        List<RaterEligibilityService.Verdict> problems = eligibility.problems(all, roles);

        AssessTaskVO.PrecheckResult result = new AssessTaskVO.PrecheckResult();
        result.setOk(problems.isEmpty());
        List<AssessTaskVO.IneligiblePerson> people = new ArrayList<>();
        for (RaterEligibilityService.Verdict v : problems) {
            AssessTaskVO.IneligiblePerson p = new AssessTaskVO.IneligiblePerson();
            p.setResourceId(v.resourceId());
            p.setName(nameOf(v));
            p.setRole(v.role());
            p.setReason(v.reason().name());
            p.setMessage(v.message());
            people.add(p);
        }
        result.setProblems(people);
        result.setMessage(problems.isEmpty() ? null : RaterEligibilityService.describe(problems));
        return result;
    }

    /**
     * 全员可登录性校验：被测人 + 直接上级 + 客户评价人一起过一遍。
     *
     * 为什么三者都查而不只查评价人：被测人的自评链接也是任务的一部分，
     * 被测人登不进来 → 自评永远交不上 → 全员齐备才算分的闸门永远打不开。
     *
     * 为什么一次查全而不逐个抛：HR 一次填完表单，应该一次看到所有问题人员，
     * 而不是改一个、再被拒一次、再改下一个。
     */
    private void assertAllCanLogin(long targetId, long leaderId, List<Long> clientIds) {
        List<Long> all = new ArrayList<>();
        all.add(targetId);
        all.add(leaderId);
        all.addAll(clientIds);

        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(targetId, "被测人");
        roles.put(leaderId, "直接上级");
        for (Long clientId : clientIds) {
            roles.put(clientId, "客户评价人");
        }

        List<RaterEligibilityService.Verdict> problems = eligibility.problems(all, roles);
        if (problems.isEmpty()) {
            return;
        }

        // 被测人 / 领导的问题沿用各自的错误码，前端可分支处理；其余归到评价人名单问题
        for (RaterEligibilityService.Verdict v : problems) {
            if (v.resourceId() == targetId) {
                throw invalidTarget(v, problems);
            }
            if (v.resourceId() == leaderId) {
                throw invalidLeader(v, problems);
            }
        }
        throw invalidRaters(problems);
    }

    private BusinessException invalidTarget(RaterEligibilityService.Verdict v,
                                            List<RaterEligibilityService.Verdict> all) {
        return new BusinessException(ERR_TARGET_INVALID,
                "被测人「" + nameOf(v) + "」" + v.message() + "。" + tailHint(all, v));
    }

    private BusinessException invalidLeader(RaterEligibilityService.Verdict v,
                                            List<RaterEligibilityService.Verdict> all) {
        return new BusinessException(ERR_LEADER_INVALID,
                "直接上级「" + nameOf(v) + "」" + v.message() + "。" + tailHint(all, v));
    }

    private BusinessException invalidRaters(List<RaterEligibilityService.Verdict> all) {
        return new BusinessException(ERR_CLIENT_MISMATCH, RaterEligibilityService.describe(all));
    }

    /** 若还有其他问题人员，一并提示，避免 HR 改完一个又被拒一次 */
    private static String tailHint(List<RaterEligibilityService.Verdict> all,
                                   RaterEligibilityService.Verdict current) {
        List<RaterEligibilityService.Verdict> others = new ArrayList<>();
        for (RaterEligibilityService.Verdict v : all) {
            if (v != current) {
                others.add(v);
            }
        }
        if (others.isEmpty()) {
            return "";
        }
        return "另有 " + others.size() + " 人也存在问题：" + RaterEligibilityService.describe(others);
    }

    private static String nameOf(RaterEligibilityService.Verdict v) {
        return v.name() == null || v.name().isBlank() ? "ID " + v.resourceId() : v.name();
    }

    private AssessTaskVO.RaterLink createRater(long taskId, String raterType, long raterResourceId) {
        AssessTaskRater rater = new AssessTaskRater();
        rater.setTaskId(taskId);
        rater.setRaterType(raterType);
        rater.setRaterResourceId(raterResourceId);
        rater.setStatus(RATER_PENDING);
        rater.setDraftVersion(0);
        rater.setRemindCount(0);
        rater.setAccessToken(newToken());
        raterMapper.insert(rater);

        AssessTaskVO.RaterLink link = new AssessTaskVO.RaterLink();
        link.setRaterId(rater.getId());
        link.setRaterType(raterType);
        link.setRaterResourceId(raterResourceId);
        link.setRaterName(queryName(raterResourceId));
        link.setAccessToken(rater.getAccessToken());
        link.setPath("/assess/fill/" + rater.getAccessToken());
        return link;
    }

    /** 32 字节 SecureRandom → Base64URL 无填充，长度 43，VARCHAR(64) 足够 */
    private static String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String queryName(long resourceId) {
        List<String> names = jdbc.queryForList(
                "SELECT lastname FROM HrmResource WHERE id = ?", String.class, resourceId);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 提示语用的人名，查不到时退回 ID，避免出现「「」在 OA 中没有直接上级」这种半句话 */
    private String nameOfResource(Long resourceId) {
        String name = resourceId == null ? null : queryName(resourceId);
        return name == null || name.isBlank() ? "ID " + resourceId : name;
    }

    private static List<Long> dedupe(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                seen.add(id);
            }
        }
        return new ArrayList<>(seen);
    }

    private void writeLog(long sheetId, String action, String detailJson, long dimensionId) {
        jdbc.update(
                "INSERT INTO uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time, dimension_id) " +
                "VALUES (?, ?, ?, ?, SYSDATETIME(), ?)",
                sheetId, action, UserContext.currentResourceIdOrNull(), detailJson, dimensionId);
    }

    private long currentOperatorId() {
        LoginUser user = UserContext.currentOrNull();
        return user == null || user.resourceId() == null ? 0L : user.resourceId();
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
