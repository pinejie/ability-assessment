package com.company.ability.service.impl;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.entity.AssessTask;
import com.company.ability.entity.AssessTaskRater;
import com.company.ability.entity.AssessTaskRaterItem;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaSheet;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessTaskMapper;
import com.company.ability.mapper.AssessTaskRaterItemMapper;
import com.company.ability.mapper.AssessTaskRaterMapper;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AssessPushService;
import com.company.ability.service.PaSheetService;
import com.company.ability.service.PaTaskService;
import com.company.ability.service.RaterEligibilityService;
import com.company.ability.vo.PaTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测评任务发起实现（方案 17 §7.2）。
 *
 * <p>发起前的闸门顺序是刻意的：先查<b>便宜且必然失败</b>的（年度/岗位/上级），
 * 再装配页面（会写库），最后查人员可登录性（一次 SQL 查完）。
 * 反过来会让「上级没维护」这种秒级就能发现的错误先去写一堆页面行再回滚。</p>
 */
@Slf4j
@Service
public class PaTaskServiceImpl implements PaTaskService {

    static final int ERR_TASK_EXISTS = 40301;
    static final int ERR_PERIOD_CLOSED = 40302;
    static final int ERR_LEADER_INVALID = 40303;
    static final int ERR_TARGET_INVALID = 40304;
    static final int ERR_RATER_INVALID = 40305;
    static final int ERR_RATER_SELF = 40306;
    static final int ERR_POST_INVALID = 40307;
    static final int ERR_POST_DEPT_MISMATCH = 40308;

    /** uf_assess_task.status */
    private static final int TASK_RUNNING = 0;
    /** uf_assess_period.status */
    private static final int PERIOD_CLOSED = 1;
    /** uf_assess_task_rater.status */
    static final int RATER_PENDING = 0;

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AssessTaskMapper taskMapper;
    private final AssessTaskRaterMapper raterMapper;
    private final AssessPushService pushService;
    private final RaterEligibilityService eligibility;
    private final PaSheetService sheetService;
    private final PaPostMapper postMapper;
    private final PaItemMapper itemMapper;
    private final PaCategoryMapper categoryMapper;
    private final AssessTaskRaterItemMapper raterItemMapper;
    private final long companyId;

    public PaTaskServiceImpl(JdbcTemplate jdbc, AssessTaskMapper taskMapper, AssessTaskRaterMapper raterMapper,
                             AssessPushService pushService, RaterEligibilityService eligibility,
                             PaSheetService sheetService,
                             PaPostMapper postMapper, PaItemMapper itemMapper,
                             PaCategoryMapper categoryMapper,
                             AssessTaskRaterItemMapper raterItemMapper,
                             @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.taskMapper = taskMapper;
        this.raterMapper = raterMapper;
        this.pushService = pushService;
        this.eligibility = eligibility;
        this.sheetService = sheetService;
        this.postMapper = postMapper;
        this.itemMapper = itemMapper;
        this.categoryMapper = categoryMapper;
        this.raterItemMapper = raterItemMapper;
        this.companyId = companyId;
    }

    // ================================================================ 发起

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaTaskVO.CreateResult create(PaTaskDTO.CreateRequest req) {
        long dimensionId = DimensionContext.getOrDefault();

        // 1. 年度必须存在且未关闭
        Map<String, Object> period = requireOpenPeriod(req.getYear(), dimensionId);
        long periodId = num(period.get("id"));

        // 2. 直接上级：只认 OA 组织关系（口径 #11）
        PaTaskVO.LeaderInfo leaderInfo = resolveLeader(req.getResourceId());
        Long leaderId = leaderInfo.getLeaderResourceId();
        if (leaderId == null || leaderId == 0L) {
            throw new BusinessException(ERR_LEADER_INVALID,
                    "「" + nameOfResource(req.getResourceId()) + "」" + leaderInfo.getMessage());
        }
        if (leaderId.equals(req.getResourceId())) {
            throw new BusinessException(ERR_LEADER_INVALID, "直接上级不能是被测人本人");
        }

        // 3. 岗位必须在装配页面之前定下来：360 是<b>按能力项</b>指派的（2026-11-23），
        //    校验「这个人评的那一项是否真配了 360 主体」需要岗位的能力模型。
        PaPost post = postMapper.selectById(req.getPostId());
        if (post == null) {
            throw new BusinessException(ERR_POST_INVALID, "岗位不存在（可能已被删除），请重新选择");
        }
        if (post.getStatus() == null || post.getStatus() != 1) {
            throw new BusinessException(ERR_POST_INVALID,
                    "岗位「" + post.getPostName() + "」已停用，不能再用于新的测评");
        }

        // 4. 360 指派：按项分组 + 人员并集。老入参（整批人评全部 360 项）在这里被翻译成同样的结构，
        //    所以建库与填写只有一条运行路径，不存在「新老任务两套逻辑」的分叉。
        Assign360 assign = resolve360(req, post.getId());
        // 「把自己勾成 360」是笔误，必须挡 —— 否则一人两条链接、两条都提交不了。
        if (assign.raters.contains(req.getResourceId())) {
            throw new BusinessException(ERR_RATER_SELF, "被测人本人不能作为 360 测评人（自评是独立的链接）");
        }

        // 4.1 被测人必须属于岗位所在部门（2026-11-22）：页面把 department_id 取自岗位，
        //     放行跨部门组合会生成「质量科的人 / 数字化推进办公室的岗位」这种报告，
        //     报告列表上部门和岗位对不上，算出来的等级也没有业务含义。
        //
        //     全公司通用岗位（department_id 为 null，sql/63）不受这条限制 —— 它本来就属于所有部门。
        //     它的测评页部门快照改取被测人自己的部门（见 PaSheetServiceImpl.prepare），
        //     所以报告上仍然有真实部门，不会出现空部门。
        Long targetDeptId = departmentOf(req.getResourceId());
        if (targetDeptId == null) {
            throw new BusinessException(ERR_POST_DEPT_MISMATCH,
                    "「" + nameOfResource(req.getResourceId()) + "」在 OA 中没有部门，无法按部门岗位测评，请先补齐组织信息");
        }
        if (post.getDepartmentId() != null && !targetDeptId.equals(post.getDepartmentId())) {
            throw new BusinessException(ERR_POST_DEPT_MISMATCH,
                    "「" + nameOfResource(req.getResourceId()) + "」不属于岗位「" + postName(post)
                            + "」所在部门，被测人只能选该部门的人");
        }
        PaSheet sheet = sheetService.prepare(req.getYear(), req.getResourceId(), post.getId());

        // 5. 重复发起保护：同一页面同时只能有一个进行中的任务
        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task WHERE sheet_id = ? AND status = ?",
                Integer.class, sheet.getId(), TASK_RUNNING);
        if (running != null && running > 0) {
            throw new BusinessException(ERR_TASK_EXISTS,
                    "该被测人本期已有进行中的测评任务，请先撤回后再重新发起");
        }

        // 6. 全员可登录性：被测人 / 上级 / 全部 360 一次查全并列出所有问题人员
        assertAllCanLogin(req.getResourceId(), leaderId, assign.raters);

        // 7. 建任务
        AssessTask task = new AssessTask();
        task.setCompanyId(companyId);
        task.setDimensionId(dimensionId);
        task.setPeriodId(periodId);
        task.setSheetId(sheet.getId());
        task.setResourceId(req.getResourceId());
        task.setDepartmentId(sheet.getDepartmentId());
        task.setLeaderResourceId(leaderId);
        task.setStatus(TASK_RUNNING);
        task.setRatingCount(sheet.getItemCount() == null ? 0 : sheet.getItemCount());
        task.setClientCount(assign.raters.size());
        task.setRemark(req.getRemark());
        task.setCreateBy(currentOperatorId());
        taskMapper.insert(task);
        long taskId = task.getId();

        // 8. 生成评价人（自评 1 + 上级 1 + 360 N），每人一个 token；
        //    360 额外落「他评哪几项」的指派行 —— 一个人被指派几项，就一条链接填几项。
        List<PaTaskVO.RaterLink> links = new ArrayList<>();
        links.add(createRater(taskId, "SELF", req.getResourceId()));
        links.add(createRater(taskId, "LEADER", leaderId));
        for (Long rater360Id : assign.raters) {
            List<Long> myItems = assign.itemsOf(rater360Id);
            PaTaskVO.RaterLink link = createRater(taskId, "RATER360", rater360Id);
            link.setItemCount(myItems.size());
            writeRaterItems(taskId, link.getRaterId(), myItems);
            links.add(link);
        }

        PaTaskVO.CreateResult result = new PaTaskVO.CreateResult();
        result.setTaskId(taskId);
        result.setSheetId(sheet.getId());
        result.setStatus(TASK_RUNNING);
        result.setItemCount(task.getRatingCount());
        result.setRater360Count(assign.raters.size());
        result.setFillBaseUrl("/pa/assess-fill/");
        result.setRaters(links);

        // 9. 邀请消息。写失败不影响发起本身 —— 链接已在响应里返回，HR 可手工转达。
        try {
            result.setNoticeCount(pushService.writeInvites(taskId, req.getYear(), task.getRatingCount(),
                    queryName(req.getResourceId()), links));
        } catch (Exception e) {
            log.warn("发起邀请消息写入失败 taskId={}: {}", taskId, e.getMessage());
            result.setNoticeCount(0);
            result.setNoticeError("邀请消息写入失败：" + e.getMessage() + "，请手工转达评价链接");
        }

        log.info("发起测评任务 taskId={}, sheetId={}, resourceId={}, 能力项={}, 评价人={}（360 {} 位，"
                        + "{} 项按项指派{}）",
                taskId, sheet.getId(), req.getResourceId(), task.getRatingCount(),
                links.size(), assign.raters.size(), assign.byItem.size(),
                assign.legacy ? "，旧入参按全部 360 项分发" : "");
        return result;
    }

    // ================================================================ 预检

    @Override
    public PaTaskVO.PrecheckResult precheck(PaTaskDTO.PrecheckRequest req) {
        Long leaderId = resolveLeader(req.getResourceId()).getLeaderResourceId();
        // 预检只关心「这次会牵连到哪些人」：前端把逐项选人拍平成一份去重名单，
        // 一人评多项也只查一次，漏掉就等于把死链放出去。
        List<Long> rater360Ids = req.getRater360ResourceIds() == null
                ? List.of()
                : req.getRater360ResourceIds().stream().filter(Objects::nonNull).distinct().toList();

        List<Long> all = new ArrayList<>();
        all.add(req.getResourceId());
        if (leaderId != null && leaderId > 0) {
            all.add(leaderId);
        }
        all.addAll(rater360Ids);

        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(req.getResourceId(), "被测人");
        if (leaderId != null && leaderId > 0) {
            roles.put(leaderId, "直接上级");
        }
        for (Long id : rater360Ids) {
            roles.put(id, "360 测评人");
        }

        List<RaterEligibilityService.Verdict> problems = eligibility.problems(all, roles);

        PaTaskVO.PrecheckResult result = new PaTaskVO.PrecheckResult();
        result.setOk(problems.isEmpty());
        List<PaTaskVO.IneligiblePerson> people = new ArrayList<>();
        for (RaterEligibilityService.Verdict v : problems) {
            PaTaskVO.IneligiblePerson p = new PaTaskVO.IneligiblePerson();
            p.setResourceId(v.resourceId());
            p.setName(v.name());
            p.setRole(v.role());
            p.setReason(v.reason().name());
            p.setMessage(v.message());
            people.add(p);
        }
        result.setProblems(people);
        result.setMessage(problems.isEmpty() ? null : RaterEligibilityService.describe(problems));
        return result;
    }

    // ================================================================ 读取

    @Override
    public PaTaskVO.LeaderInfo resolveLeader(Long resourceId) {
        PaTaskVO.LeaderInfo info = new PaTaskVO.LeaderInfo();
        if (resourceId == null) {
            info.setMessage("请先选择被测人");
            return info;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.managerid, m.lastname AS leader_name, d.departmentname AS dept_name" +
                " FROM HrmResource r" +
                " LEFT JOIN HrmResource m ON m.id = r.managerid" +
                " LEFT JOIN HrmDepartment d ON d.id = m.departmentid" +
                " WHERE r.id = ?", resourceId);
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

    @Override
    public PaTaskVO.PostInfo resolvePost(Long postId) {
        PaTaskVO.PostInfo info = new PaTaskVO.PostInfo();
        if (postId == null) {
            info.setMessage("请先选择岗位");
            return info;
        }
        PaPost post = postMapper.selectById(postId);
        if (post == null) {
            info.setMessage("岗位不存在（可能已被删除），请重新选择");
            return info;
        }
        if (post.getStatus() == null || post.getStatus() != 1) {
            info.setMessage("岗位「" + post.getPostName() + "」已停用，不能再用于新的测评");
            return info;
        }

        // 排序与「岗位能力模型」页保持一致（类别 sort → 项 sort → id）：
        // 两处列同一批能力项，顺序不一致会让人以为看的是两套数据
        List<PaCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<PaCategory>()
                .eq(PaCategory::getPostId, post.getId())
                .orderByAsc(PaCategory::getSort).orderByAsc(PaCategory::getId));
        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, post.getId())
                .eq(PaItem::getStatus, 1)
                .orderByAsc(PaItem::getCategoryId).orderByAsc(PaItem::getSort).orderByAsc(PaItem::getId));
        info.setPostId(post.getId());
        info.setDepartmentId(post.getDepartmentId());
        info.setDepartmentName(post.getDepartmentName());
        info.setPostName(post.getPostName());
        info.setGeneric(post.isGeneric());
        info.setItemCount(items.size());
        info.setRater360ItemCount((int) items.stream()
                .filter(i -> Objects.equals(i.getRater360(), 1)).count());
        info.setRater360Items(rater360Briefs(items, categories));
        info.setWeightTotal(items.stream()
                .map(i -> i.getWeight() == null ? BigDecimal.ZERO : i.getWeight())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (items.isEmpty()) {
            info.setMessage("岗位「" + postName(post) + "」下还没有启用的能力项，请先在「岗位能力模型」中配置");
        }
        return info;
    }

    // ================================================================ 私有

    /**
     * 把「要 360 评价的能力项」压成发起页表格用的行。
     *
     * <p>类别名从一次查出的类别列表里取（不逐项查库）：项可能挂在已停用的类别下，
     * 这时名照给 —— 项本身还是启用就要选人，看不出它属于哪一类反而更困惑。</p>
     */
    private List<PaTaskVO.Rater360ItemBrief> rater360Briefs(List<PaItem> items, List<PaCategory> categories) {
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(PaCategory::getId, PaCategory::getCategoryName, (a, b) -> a));
        List<PaTaskVO.Rater360ItemBrief> briefs = new ArrayList<>();
        for (PaItem item : items) {
            if (!Objects.equals(item.getRater360(), 1)) {
                continue;
            }
            PaTaskVO.Rater360ItemBrief brief = new PaTaskVO.Rater360ItemBrief();
            brief.setItemId(item.getId());
            brief.setItemName(item.getItemName());
            brief.setCategoryName(categoryNames.get(item.getCategoryId()));
            // 评价主体原文照抄：不按 raterLeader/rater360 反推（见 Rater360ItemBrief 注释）
            brief.setRaterText(item.getRaterText());
            briefs.add(brief);
        }
        return briefs;
    }

    private Map<String, Object> requireOpenPeriod(int year, long dimensionId) {
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, year, dimensionId);
        if (periods.isEmpty()) {
            throw new BusinessException(ERR_PERIOD_CLOSED,
                    year + " 年度不存在，请先在当前维度下的年度设置中新增");
        }
        if (num(periods.get(0).get("status")) == PERIOD_CLOSED) {
            throw new BusinessException(ERR_PERIOD_CLOSED, year + " 年度已关闭，不能发起测评");
        }
        return periods.get(0);
    }

    /**
     * 全员可登录性校验：被测人 + 直接上级 + 360 测评人一起过一遍。
     *
     * <p>为什么三者都查而不只查评价人：被测人的自评链接也是任务的一部分。
     * 但自评<b>不阻断出分</b>（口径 #13），所以这里挡住的是「人根本进不来系统」
     * 这类硬错，不是「他没交卷」。</p>
     *
     * <p>为什么一次查全而不逐个抛：HR 一次填完表单，应该一次看到所有问题人员。</p>
     */
    private void assertAllCanLogin(long targetId, long leaderId, List<Long> rater360Ids) {
        List<Long> all = new ArrayList<>();
        all.add(targetId);
        all.add(leaderId);
        all.addAll(rater360Ids);

        Map<Long, String> roles = new LinkedHashMap<>();
        roles.put(targetId, "被测人");
        roles.put(leaderId, "直接上级");
        for (Long id : rater360Ids) {
            roles.put(id, "360 测评人");
        }

        List<RaterEligibilityService.Verdict> problems = eligibility.problems(all, roles);
        if (problems.isEmpty()) {
            return;
        }
        for (RaterEligibilityService.Verdict v : problems) {
            if (v.resourceId() == targetId) {
                throw new BusinessException(ERR_TARGET_INVALID,
                        "被测人「" + display(v) + "」" + v.message() + "。" + tailHint(problems, v));
            }
            if (v.resourceId() == leaderId) {
                throw new BusinessException(ERR_LEADER_INVALID,
                        "直接上级「" + display(v) + "」" + v.message() + "。" + tailHint(problems, v));
            }
        }
        throw new BusinessException(ERR_RATER_INVALID, RaterEligibilityService.describe(problems));
    }

    private static String display(RaterEligibilityService.Verdict v) {
        return v.name() == null || v.name().isBlank() ? "ID " + v.resourceId() : v.name();
    }

    /** 若还有其他问题人员一并提示，避免 HR 改完一个又被拒一次 */
    private static String tailHint(List<RaterEligibilityService.Verdict> all,
                                   RaterEligibilityService.Verdict current) {
        List<RaterEligibilityService.Verdict> others = new ArrayList<>();
        for (RaterEligibilityService.Verdict v : all) {
            if (v != current) {
                others.add(v);
            }
        }
        return others.isEmpty() ? "" : "另有 " + others.size() + " 人也存在问题：" + RaterEligibilityService.describe(others);
    }

    private PaTaskVO.RaterLink createRater(long taskId, String raterType, long raterResourceId) {
        AssessTaskRater rater = new AssessTaskRater();
        rater.setTaskId(taskId);
        rater.setRaterType(raterType);
        rater.setRaterResourceId(raterResourceId);
        rater.setStatus(RATER_PENDING);
        rater.setDraftVersion(0);
        rater.setRemindCount(0);
        rater.setAccessToken(newToken());
        raterMapper.insert(rater);

        PaTaskVO.RaterLink link = new PaTaskVO.RaterLink();
        link.setRaterId(rater.getId());
        link.setRaterType(raterType);
        link.setRaterResourceId(raterResourceId);
        link.setRaterName(queryName(raterResourceId));
        link.setAccessToken(rater.getAccessToken());
        link.setPath("/pa/assess-fill/" + rater.getAccessToken());
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

    /**
     * 人员的 OA 部门。查不到人、或部门为空/0 时返回 null
     * （0 在泛微里是「无部门」，不是「总部的部门 ID」）。
     */
    private Long departmentOf(long resourceId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT departmentid FROM HrmResource WHERE id = ?", Long.class, resourceId);
        if (ids.isEmpty() || ids.get(0) == null || ids.get(0) <= 0) {
            return null;
        }
        return ids.get(0);
    }

    /** 提示语用的人名，查不到时退回 ID，避免出现「「」在 OA 中没有直接上级」这种半句话 */
    private String nameOfResource(long resourceId) {
        String name = queryName(resourceId);
        return name == null || name.isBlank() ? "ID " + resourceId : name;
    }

    private static List<Long> dedupe(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                seen.add(id);
            }
        }
        return new ArrayList<>(seen);
    }

    // ================================================================ 360 按项指派

    /**
     * 360 指派结果：一个人一条链接，一个人可以被指派多项。
     *
     * <p>{@code byItem} 是 itemId → 该项的测评人（保持前端传的顺序，去重）；
     * {@code raters} 是全体人员并集，顺序 = 首次出现顺序 —— 生成的 rater 行和链接顺序都由它决定，
     * 页面上的「测评人1、2…」也就是这个顺序。</p>
     */
    private static final class Assign360 {
        private final Map<Long, List<Long>> byItem = new LinkedHashMap<>();
        private final List<Long> raters = new ArrayList<>();
        /** 旧入参路径：将这些人铺到岗位里每一个 360 项上 */
        private boolean legacy;

        List<Long> itemsOf(Long raterId) {
            List<Long> out = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> e : byItem.entrySet()) {
                if (e.getValue().contains(raterId)) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
    }

    /**
     * 把两种入参统一成「按项指派」。
     *
     * <p>为什么统一而不是新老各走一条：建 rater 行、发消息、写指派表这三件事，
     * 分成两套写法后必然有一天只改了一边（改新忘旧或反之），而症状是「消息里的项数对不上」，
     * 排查要从发起追到消息表。翻译成同一结构后，运行路径只有一条。</p>
     *
     * <p>新语义（传了 {@code rater360Items}）下每一行都校验：项必须属于该岗位、已启用、
     * 且配了「360 测评人」主体。悄悄忽略非法项比报错更糟 —— HR 会以为选的人已经生效，
     * 直到有人收到一条只含半份能力项的链接。</p>
     */
    private Assign360 resolve360(PaTaskDTO.CreateRequest req, long postId) {
        Assign360 out = new Assign360();
        if (req.getRater360Items() != null) {
            Map<Long, PaItem> allowed = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                            .eq(PaItem::getPostId, postId)
                            .eq(PaItem::getStatus, 1))
                    .stream().collect(Collectors.toMap(PaItem::getId, i -> i));
            for (PaTaskDTO.Rater360Item row : req.getRater360Items()) {
                PaItem item = allowed.get(row.getItemId());
                if (item == null) {
                    throw new BusinessException(ERR_RATER_INVALID,
                            "能力项 " + row.getItemId() + " 不在所选岗位的启用能力项里，请重新选择");
                }
                if (!Objects.equals(item.getRater360(), 1)) {
                    throw new BusinessException(ERR_RATER_INVALID,
                            "能力项「" + item.getItemName() + "」没有配置「360 测评人」评级主体，不能指派 360");
                }
                List<Long> people = dedupe(row.getResourceIds());
                out.byItem.put(item.getId(), people);
                for (Long id : people) {
                    if (!out.raters.contains(id)) {
                        out.raters.add(id);
                    }
                }
            }
            return out;
        }

        // 旧入参：这批人评岗位里全部 360 项。岗位没有 360 项时 byItem 为空，
        // 那些人仍然建 rater 行（口径 #9：后端不因空名单/无 360 项拒绝），
        // 只是填写页按旧口径回退到「快照 rater_360=1 的项」，这里不产生指派行。
        List<Long> people = dedupe(req.getRater360ResourceIds());
        if (people.isEmpty()) {
            return out;
        }
        out.legacy = true;
        out.raters.addAll(people);
        List<PaItem> items360 = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId)
                .eq(PaItem::getStatus, 1)
                .eq(PaItem::getRater360, 1));
        for (PaItem item : items360) {
            out.byItem.put(item.getId(), new ArrayList<>(people));
        }
        return out;
    }

    /**
     * 落指派行。
     *
     * <p>一项都没指派时不写行 —— 那种情况下填写侧要回退到「全部 360 项」的旧口径，
     * 写一行空指派反而不存在（关系表没有「空」这个概念）。旧入参路径的项集合
     * 恰好等于旧口径，写不写结果一样，所以统一按 {@code myItems} 是否为空决策。</p>
     */
    private void writeRaterItems(long taskId, long raterId, List<Long> itemIds) {
        for (Long itemId : itemIds) {
            AssessTaskRaterItem row = new AssessTaskRaterItem();
            row.setTaskId(taskId);
            row.setRaterId(raterId);
            row.setItemId(itemId);
            raterItemMapper.insert(row);
        }
    }

    /** 「部门 · 岗位」；通用岗位没有部门，只显示岗位名 */
    private static String postName(PaPost post) {
        return post.getDepartmentName() == null || post.getDepartmentName().isBlank()
                ? post.getPostName()
                : post.getDepartmentName() + " · " + post.getPostName();
    }

    private long currentOperatorId() {
        LoginUser user = UserContext.currentOrNull();
        return user == null || user.resourceId() == null ? 0L : user.resourceId();
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
