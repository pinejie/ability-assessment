package com.company.ability.service;

import com.company.ability.dto.AssessFillDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.AssessTaskVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评价填写：取数、暂存草稿、提交（S1）。
 * 方案：project/能力测评/15-评价网页化改造方案.md §7.2、§4.2.2、§4.2.3、§4.1.1
 *
 * 三条铁律：
 * ① 单人提交只写评级、不算分 —— 走 {@code usp_assess_rate @skip_recalc=1, @source='WEB'}。
 * ② 算分只发生在「本任务全部评价人提交完毕」那一刻，由 {@code usp_assess_task_finish} 在同一请求内完成。
 * ③ 防代填靠「登录人 == rater_resource_id」，token 只是入口不是身份（§10.2）。
 */
@Slf4j
@Service
public class AssessFillService {

    static final int ERR_TOKEN_INVALID = 40401;
    static final int ERR_FORBIDDEN = 40402;
    static final int ERR_TASK_NOT_RUNNING = 40403;
    static final int ERR_RATINGS_INCOMPLETE = 40404;
    static final int ERR_LEVEL_INVALID = 40405;
    static final int ERR_DRAFT_CONFLICT = 40406;
    static final int ERR_ALREADY_SUBMITTED = 40407;
    /** 评价已提交，不允许再次提交（2026-11-16 由「提交后可改」改为单向提交） */
    static final int ERR_RESUBMIT_FORBIDDEN = 40408;

    /** 任务状态（uf_assess_task.status） */
    private static final int TASK_RUNNING = 0;
    private static final int TASK_DONE = 2;
    private static final int TASK_CANCELLED = 3;
    private static final int TASK_VOIDED = 4;

    /** 评价人状态（uf_assess_task_rater.status） */
    private static final int RATER_PENDING = 0;
    private static final int RATER_SUBMITTED = 1;
    private static final int RATER_VOIDED = 3;

    private static final Map<String, String> RATER_TYPE_NAMES = Map.of(
            "SELF", "自评", "LEADER", "直接上级", "CLIENT", "客户");

    /** 需要写评价意见的身份（2026-11-17）：自评不写，上级/客户必填 */
    private static final Set<String> COMMENT_RATER_TYPES = Set.of("LEADER", "CLIENT");

    /** 评价意见最大长度，与 uf_assess_task_rater.comment NVARCHAR(1000) 一致 */
    private static final int COMMENT_MAX_LEN = 1000;

    static final int ERR_COMMENT_REQUIRED = 40409;
    static final int ERR_COMMENT_TOO_LONG = 40410;
    /** 年度已关闭，不再接受新的评价提交（2026-11-19） */
    static final int ERR_PERIOD_CLOSED = 40411;

    /** 年度状态（uf_assess_period.status）：0 进行中 1 已关闭 */
    private static final int PERIOD_CLOSED = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final long companyId;

    public AssessFillService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                             @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.companyId = companyId;
    }

    /**
     * 填写页取数。
     *
     * 已撤回/已取消时只返回状态与说明，不含姓名、要素、草稿（§4.3.8）：
     * 撤回后评级已清除，再回显任何内容都会与「回到评价初」矛盾。
     */
    public AssessTaskVO.FillPage page(String token) {
        RaterContext ctx = loadByToken(token);

        if (ctx.taskStatus == TASK_VOIDED) {
            return statusOnly("VOIDED", "该测评已被管理员撤回。如需继续参与，请联系人力资源部重新发起测评。");
        }
        if (ctx.taskStatus == TASK_CANCELLED) {
            return statusOnly("CANCELLED", "该测评已被管理员取消。如有疑问，请联系人力资源部。");
        }
        if (ctx.raterStatus == RATER_VOIDED) {
            return statusOnly("VOIDED", "该测评已被管理员撤回。如需继续参与，请联系人力资源部重新发起测评。");
        }
        // 年度已关闭：不再接受新的评价（2026-11-19）。
        // 已提交的豁免 —— 关闭年度冻结的是报告，不该连评价人回看自己被评内容的入口一起砍掉；
        // 而且关闭后报告快照是唯一凭证，挡掉等于让人无从核对。
        if (ctx.periodStatus == PERIOD_CLOSED && ctx.raterStatus != RATER_SUBMITTED) {
            return statusOnly("PERIOD_CLOSED",
                    "该年度已关闭，不能再提交评价。如需补评，请联系人力资源部重新开启年度。");
        }
        // 防代填：token 可被转发，但只有本人能打开
        assertCurrentUser(ctx);

        AssessTaskVO.FillPage page = new AssessTaskVO.FillPage();
        page.setStatus(ctx.raterStatus == RATER_SUBMITTED ? "SUBMITTED" : "FILLING");
        page.setTaskId(ctx.taskId);
        page.setRaterId(ctx.raterId);
        page.setRaterType(ctx.raterType);
        page.setRaterTypeName(RATER_TYPE_NAMES.getOrDefault(ctx.raterType, ctx.raterType));
        page.setYear(ctx.year);
        page.setDimensionName(ctx.dimensionName);
        page.setTargetName(ctx.targetName);
        page.setDeptName(ctx.deptName);
        page.setDraftVersion(ctx.draftVersion);
        page.setComment(ctx.comment);
        page.setCommentRequired(COMMENT_RATER_TYPES.contains(ctx.raterType));

        boolean masked = "CLIENT".equals(ctx.raterType);
        page.setMasked(masked);
        page.setElements(elements(ctx.sheetId, ctx.raterType, masked, ctx.dimensionId));
        page.setDraft(parseDraft(ctx.draftJson));

        int[] progress = progress(ctx.taskId);
        page.setSubmittedCount(progress[0]);
        page.setRaterTotal(progress[1]);
        if (ctx.raterStatus == RATER_SUBMITTED) {
            page.setMessage(ctx.taskStatus == TASK_DONE
                    ? "本测评已由全部评价人提交完毕并完成算分，您的评价已锁定。"
                    : "您已提交，评价已锁定、不可再修改，等待其他评价人完成后系统自动计算得分。");
        }
        return page;
    }

    /** 暂存草稿：只写 draft_json，不写评级、不算分（§4.2.2） */
    public AssessTaskVO.DraftResult saveDraft(String token, AssessFillDTO.DraftRequest req) {
        RaterContext ctx = loadByToken(token);
        assertSubmittable(ctx);
        // 已提交者草稿即最终答案，不允许再改（2026-11-16 单向提交）。
        // 否则前端会自动暂存，用户以为改动能生效，直到提交时才被拒 —— 白改一遍还丢改动。
        assertNotSubmitted(ctx);

        if (!Integer.valueOf(ctx.draftVersion).equals(req.getVersion())) {
            // 版本冲突：返回库内最新草稿让前端合并，不覆盖（§4.2.2）
            AssessTaskVO.DraftResult conflict = new AssessTaskVO.DraftResult();
            conflict.setConflict(true);
            conflict.setDraftVersion(ctx.draftVersion);
            conflict.setLatestDraft(parseDraft(ctx.draftJson));
            conflict.setRatedCount(conflict.getLatestDraft().size());
            return conflict;
        }

        Map<Long, Integer> merged = new LinkedHashMap<>(parseDraft(ctx.draftJson));
        if (req.getRatings() != null) {
            for (AssessFillDTO.Rating r : req.getRatings()) {
                merged.put(r.getElementId(), r.getLevelNo());
            }
        }
        int nextVersion = ctx.draftVersion + 1;
        // 评语：只有上级/客户写。req 没带 comment 字段（null）时不覆盖已存评语，
        // 否则「只改了评级」的自动暂存会把刚写的评语清空。
        String comment = COMMENT_RATER_TYPES.contains(ctx.raterType)
                ? normalizeComment(req.getComment()) : null;
        if (comment != null) {
            jdbc.update("UPDATE uf_assess_task_rater SET draft_json = ?, draft_time = SYSDATETIME(), " +
                    "draft_version = ?, comment = ? WHERE id = ?", toJson(merged), nextVersion, comment, ctx.raterId);
        } else {
            jdbc.update("UPDATE uf_assess_task_rater SET draft_json = ?, draft_time = SYSDATETIME(), " +
                    "draft_version = ? WHERE id = ?", toJson(merged), nextVersion, ctx.raterId);
        }

        AssessTaskVO.DraftResult result = new AssessTaskVO.DraftResult();
        result.setConflict(false);
        result.setDraftVersion(nextVersion);
        result.setRatedCount(merged.size());
        return result;
    }

    /**
     * 提交评价。
     *
     * 校验顺序（§7.2，任一失败即拒绝，不做部分写入）：
     * 1. token 有效、评价人未作废
     * 2. 登录人 == rater_resource_id（防代填）
     * 3. 任务进行中
     * 3b. 本人尚未提交 —— 提交是单向的，提交后不可再改（2026-11-16 变更）
     * 4. 评价意见非空（上级 / 客户必填，自评不写，2026-11-17 新增）
     * 5. 提交的要素集合 == 该角色应有集合（不多不少）
     * 6. 每个 levelNo 命中该要素已配置的档位
     * 7. 写评级（@skip_recalc=1，@source='WEB'），评价人置已提交
     * 8. 全员齐备 → usp_assess_task_finish 算分
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessTaskVO.SubmitResult submit(String token, AssessFillDTO.SubmitRequest req) {
        RaterContext ctx = loadByToken(token);
        assertSubmittable(ctx);
        // 提交后不可更改（2026-11-16 业务决策，取代原 §4.2.2「提交后可改、覆盖式写入」）：
        // 评价值一旦成为算分依据就必须稳定，改分统一走「HR 撤回 → 重新发起」。
        assertNotSubmitted(ctx);

        // 4. 评价意见（2026-11-17）：上级 / 客户必填，自评不写。
        //    评语和评级一样是报告的组成部分，缺了报告「评价意见」栏就是空的，
        //    所以要挡在写入前，而不是提交成功后再让 HR 去催。
        String comment = null;
        if (COMMENT_RATER_TYPES.contains(ctx.raterType)) {
            comment = normalizeComment(req.getComment());
            if (comment == null) {
                throw new BusinessException(ERR_COMMENT_REQUIRED, "请填写评价意见后再提交");
            }
        }

        // 5. 要素集合必须与应有集合完全一致。
        //    少一个 → recalc 的 COUNT(*) = @n 不成立 → 该角色得分为 NULL → 分数永不出且难定位，
        //    所以把残缺挡在写入前（§4.2.3）。多一个 → 会写进不属于该角色的要素，同样拒绝。
        Set<Long> expected = elements(ctx.sheetId, ctx.raterType, false, ctx.dimensionId).stream()
                .map(AssessTaskVO.ElementItem::getElementId)
                .collect(Collectors.toSet());
        Map<Long, Integer> submitted = new LinkedHashMap<>();
        for (AssessFillDTO.Rating r : req.getRatings()) {
            if (submitted.put(r.getElementId(), r.getLevelNo()) != null) {
                throw new BusinessException(ERR_RATINGS_INCOMPLETE, "要素重复提交：" + r.getElementId());
            }
        }
        if (!expected.equals(submitted.keySet())) {
            Set<Long> missing = new HashSet<>(expected);
            missing.removeAll(submitted.keySet());
            Set<Long> extra = new HashSet<>(submitted.keySet());
            extra.removeAll(expected);
            throw new BusinessException(ERR_RATINGS_INCOMPLETE,
                    "评价不完整：还差 " + missing.size() + " 个要素" + (extra.isEmpty() ? "" : "，多出 " + extra.size() + " 个"));
        }

        // 6. 评级值必须是**该要素自己**配置过的档位对应的评价号。
        //    不能只校验「命中维度的 rate_value 集合」—— 维度有 5 档而某要素只配 3 档时，
        //    那样会放行 4/5 档，等于允许按业务上不存在的档位打分（§6.3、§6.5.1）。
        Map<Long, Set<Integer>> allowed = allowedRateValues(ctx.sheetId, ctx.raterType, ctx.dimensionId);
        for (Map.Entry<Long, Integer> e : submitted.entrySet()) {
            Set<Integer> ok = allowed.get(e.getKey());
            if (e.getValue() == null || ok == null || !ok.contains(e.getValue())) {
                throw new BusinessException(ERR_LEVEL_INVALID,
                        "要素 " + e.getKey() + " 的评级 " + e.getValue() + " 不是该要素已配置的档位");
            }
        }

        // 7. 写评级：@skip_recalc=1 只写不算分；@source='WEB' 让撤回能精确删除本通道数据。
        //    @workflow_request_id 传 task_id —— 语义已泛化为「来源单据 ID」（§9.1）。
        //    @client_ids_json 对 CLIENT 传全量客户名单：usp_assess_rate 第 6 步会删除不在名单中的
        //    历史客户评级；网页侧名单发起即冻结，等效幂等。
        //    领导/自评传 NULL 而不是空数组 —— 存储过程对 NULL 完全不执行第 6 步，
        //    传 [] 会把该页面全部客户评级清空（钩子正是这么用的）。
        jdbc.queryForList("{call usp_assess_rate(?,?,?,?,?,?,?,?,?,?)}",
                companyId, ctx.year, ctx.targetResourceId, ctx.raterType, ctx.raterResourceId,
                ctx.taskId, toRatingsJson(submitted),
                "CLIENT".equals(ctx.raterType) ? clientIdsJson(ctx.taskId) : null,
                1, "WEB");

        jdbc.update("UPDATE uf_assess_task_rater SET status = ?, submit_time = SYSDATETIME(), " +
                "draft_json = ?, draft_time = SYSDATETIME(), draft_version = draft_version + 1, comment = ? " +
                "WHERE id = ?", RATER_SUBMITTED, toJson(submitted), comment, ctx.raterId);

        // 8. 全员齐备 → 算分。pending 只数 status=0（作废者不算未提交）。
        int pending = pendingCount(ctx.taskId);
        AssessTaskVO.SubmitResult result = new AssessTaskVO.SubmitResult();
        result.setSubmitted(true);
        int total = progress(ctx.taskId)[1];
        AssessTaskVO.Progress p = new AssessTaskVO.Progress();
        p.setTotal(total);
        p.setSubmitted(total - pending);
        result.setTaskProgress(p);

        if (pending > 0) {
            result.setCalculated(false);
            result.setMessage("您已提交，还有 " + pending + " 位评价人未提交，全部提交后系统将自动计算得分");
        } else {
            // usp_assess_task_finish 内部再校验一次任务状态与 pending，
            // 失败会抛错并整体回滚本次提交 —— 不静默吞错（§14 风险表）。
            jdbc.queryForList("{call usp_assess_task_finish(?)}", ctx.taskId);
            result.setCalculated(true);
            result.setMessage("您已提交，全部评价人已完成，系统已计算得分");
        }
        log.info("评价提交 taskId={}, raterId={}, type={}, 已算分={}",
                ctx.taskId, ctx.raterId, ctx.raterType, result.getCalculated());
        return result;
    }

    // ==================== 私有 ====================

    /** token → 评价人 + 任务 + 页面上下文。token 查不到直接拒绝，不区分「错 token」与「无权限」。 */
    private RaterContext loadByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ERR_TOKEN_INVALID, "链接无效，请从消息或「我的评价」重新进入");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.id AS rater_id, tr.task_id, tr.rater_type, tr.rater_resource_id, tr.status AS rater_status," +
                "       tr.draft_json, tr.draft_version, tr.comment," +
                "       t.status AS task_status, t.sheet_id, t.resource_id, t.dimension_id," +
                "       p.status AS period_status, p.year, d.dimension_name," +
                "       h.lastname AS target_name, dept.departmentname AS dept_name" +
                " FROM uf_assess_task_rater tr" +
                " JOIN uf_assess_task t ON t.id = tr.task_id" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = " +
                "      (SELECT departmentid FROM HrmResource WHERE id = t.resource_id)" +
                " WHERE tr.access_token = ?", token);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_TOKEN_INVALID, "链接无效，请从消息或「我的评价」重新进入");
        }
        Map<String, Object> m = rows.get(0);

        RaterContext ctx = new RaterContext();
        ctx.raterId = num(m.get("rater_id"));
        ctx.taskId = num(m.get("task_id"));
        ctx.raterType = (String) m.get("rater_type");
        ctx.raterResourceId = num(m.get("rater_resource_id"));
        ctx.raterStatus = (int) num(m.get("rater_status"));
        ctx.draftJson = (String) m.get("draft_json");
        ctx.draftVersion = (int) num(m.get("draft_version"));
        ctx.comment = (String) m.get("comment");
        ctx.taskStatus = (int) num(m.get("task_status"));
        ctx.periodStatus = (int) num(m.get("period_status"));
        ctx.sheetId = num(m.get("sheet_id"));
        ctx.targetResourceId = num(m.get("resource_id"));
        ctx.dimensionId = num(m.get("dimension_id"));
        ctx.year = (int) num(m.get("year"));
        ctx.dimensionName = (String) m.get("dimension_name");
        ctx.targetName = (String) m.get("target_name");
        ctx.deptName = (String) m.get("dept_name");
        return ctx;
    }

    /** 只返回状态页所需的两个字段，其余一律为空（§4.3.8 不泄漏信息） */
    private AssessTaskVO.FillPage statusOnly(String status, String message) {
        AssessTaskVO.FillPage page = new AssessTaskVO.FillPage();
        page.setStatus(status);
        page.setMessage(message);
        return page;
    }

    /**
     * 评语归一化（2026-11-17）。
     *
     * <p>只在「上级 / 客户」身份调用；自评的评语一律丢弃（业务要求自评不写评语）。
     * 空白视为未填 —— 返回 null 让调用方决定是「不覆盖」还是「拒绝提交」。
     * 超长直接拒绝而不是截断：截断会静默丢内容，用户以为写全了。
     */
    private String normalizeComment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > COMMENT_MAX_LEN) {
            throw new BusinessException(ERR_COMMENT_TOO_LONG,
                    "评价意见不能超过 " + COMMENT_MAX_LEN + " 字");
        }
        return trimmed;
    }

    private void assertCurrentUser(RaterContext ctx) {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null || !user.resourceId().equals(ctx.raterResourceId)) {
            throw new BusinessException(ERR_FORBIDDEN,
                    "该评价链接不属于当前登录账号，请用收到通知的账号登录后再打开");
        }
    }

    /** 写操作（暂存/提交）的公共前置：身份 + 任务状态 */
    private void assertSubmittable(RaterContext ctx) {
        if (ctx.taskStatus == TASK_VOIDED || ctx.raterStatus == RATER_VOIDED) {
            throw new BusinessException(ERR_TASK_NOT_RUNNING, "该测评已被撤回，无法继续填写");
        }
        if (ctx.taskStatus == TASK_CANCELLED) {
            throw new BusinessException(ERR_TASK_NOT_RUNNING, "该测评已被取消，无法继续填写");
        }
        if (ctx.taskStatus == TASK_DONE) {
            throw new BusinessException(ERR_ALREADY_SUBMITTED, "该测评已完成，不能再修改评价");
        }
        if (ctx.taskStatus != TASK_RUNNING) {
            throw new BusinessException(ERR_TASK_NOT_RUNNING, "该测评当前不可填写");
        }
        // 年度已关闭：暂存与提交一律拒绝（2026-11-19）。
        // 放在任务状态之后，是为了让「已撤回/已取消/已完成」优先报出更具体的原因。
        if (ctx.periodStatus == PERIOD_CLOSED) {
            throw new BusinessException(ERR_PERIOD_CLOSED,
                    "该年度已关闭，不能再提交评价。如需补评，请联系人力资源部重新开启年度");
        }
        assertCurrentUser(ctx);
    }

    /**
     * 提交是单向的：本人已提交后，暂存与提交都拒绝（2026-11-16）。
     *
     * <p>与 {@link #assertSubmittable} 的分工：那个管「任务/评价人是否还活着」，
     * 这个管「本人是否已经交过卷」。任务完成（status=2）时本人必然已提交，
     * 但两者错误码不同 —— 任务完成是整单结束，这里是本卷已锁。
     */
    private void assertNotSubmitted(RaterContext ctx) {
        if (ctx.raterStatus == RATER_SUBMITTED) {
            throw new BusinessException(ERR_RESUBMIT_FORBIDDEN,
                    "该评价已提交，不能重复提交或修改。如需更正，请联系人力资源部撤回后重新发起");
        }
    }

    /**
     * 该角色可见的要素集合（§6.5）。
     * 口径来源是 uf_ability_element 的开关，不读 uf_assess_sheet_row.active —— 见 §1.3、§14。
     * 客户视角遮蔽要求等级（§6.4）：服务端不返回，而不是前端隐藏。
     *
     * <p>每个要素带上**自己的**档位清单：档位数量按要素配置，不按维度配置（§6.3）。
     * 档位一次批量查出后按 elementId 分组，避免逐要素查询（N+1）。
     */
    private List<AssessTaskVO.ElementItem> elements(long sheetId, String raterType, boolean masked, long dimensionId) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.element_id, r.element_name, r.category_id, r.category_name, r.required_level_no," +
                "       e.description" +
                " FROM uf_assess_sheet_row r JOIN uf_ability_element e ON e.id = r.element_id" +
                " WHERE r.sheet_id = ? AND e.deleted = 0 AND ISNULL(e.assess_in_flow, 1) = 1");
        List<Object> args = new ArrayList<>();
        args.add(sheetId);
        if ("CLIENT".equals(raterType)) {
            sql.append(" AND ISNULL(e.allow_client_assess, 1) = 1");
        }
        sql.append(" ORDER BY r.category_id, r.sort_no, r.id");

        List<AssessTaskVO.ElementItem> items = jdbc.query(sql.toString(), (rs, i) -> {
            AssessTaskVO.ElementItem item = new AssessTaskVO.ElementItem();
            item.setElementId(rs.getLong("element_id"));
            item.setElementName(rs.getString("element_name"));
            item.setDescription(rs.getString("description"));
            item.setCategoryId(rs.getLong("category_id"));
            item.setCategoryName(rs.getString("category_name"));
            item.setRequiredLevelNo(masked ? null : (Integer) rs.getObject("required_level_no"));
            return item;
        }, args.toArray());

        Map<Long, List<AssessTaskVO.LevelItem>> byElement = levelsByElement(dimensionId);
        for (AssessTaskVO.ElementItem item : items) {
            item.setLevels(byElement.getOrDefault(item.getElementId(), List.of()));
        }
        return items;
    }

    /**
     * 全维度一次查出「要素 × 档位」，按 elementId 分组（§6.3）。
     *
     * <p>档位的**存在性**来自 uf_ability_element_level（要素级），
     * 分值换算的**评价号**来自 uf_assess_level_score（维度级，按 level_no 对应）。
     * 维度未配置该等级号的行不会出现在结果里 —— 那种档位无法算分，不出选项才是安全的。
     */
    private Map<Long, List<AssessTaskVO.LevelItem>> levelsByElement(long dimensionId) {
        return jdbc.query(
                "SELECT l.element_id, l.level, l.level_name, l.level_requirement," +
                "       s.rate_value, s.level_desc" +
                " FROM uf_ability_element_level l" +
                " JOIN uf_assess_level_score s" +
                "      ON s.company_id = ? AND s.dimension_id = ? AND s.level_no = l.level" +
                " ORDER BY l.element_id, l.level",
                rs -> {
                    Map<Long, List<AssessTaskVO.LevelItem>> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        AssessTaskVO.LevelItem lv = new AssessTaskVO.LevelItem();
                        lv.setLevelNo((Integer) rs.getObject("level"));
                        lv.setRateValue((Integer) rs.getObject("rate_value"));
                        String name = rs.getString("level_name");
                        lv.setLevelDesc(name == null || name.isBlank() ? rs.getString("level_desc") : name);
                        lv.setRequirement(rs.getString("level_requirement"));
                        map.computeIfAbsent(rs.getLong("element_id"), k -> new ArrayList<>()).add(lv);
                    }
                    return map;
                }, companyId, dimensionId);
    }

    /** 提交校验用：elementId → 该要素允许的评级值（rate_value）集合 */
    private Map<Long, Set<Integer>> allowedRateValues(long sheetId, String raterType, long dimensionId) {
        Map<Long, Set<Integer>> result = new LinkedHashMap<>();
        for (AssessTaskVO.ElementItem item : elements(sheetId, raterType, false, dimensionId)) {
            Set<Integer> set = new HashSet<>();
            for (AssessTaskVO.LevelItem lv : item.getLevels()) {
                if (lv.getRateValue() != null) {
                    set.add(lv.getRateValue());
                }
            }
            result.put(item.getElementId(), set);
        }
        return result;
    }

    /** 本任务客户名单，格式 [3785,2381] */
    private String clientIdsJson(long taskId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT rater_resource_id FROM uf_assess_task_rater WHERE task_id = ? AND rater_type = 'CLIENT'",
                Long.class, taskId);
        if (ids.isEmpty()) {
            return "[]";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private int pendingCount(long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = ?",
                Integer.class, taskId, RATER_PENDING);
        return n == null ? 0 : n;
    }

    /** @return [已提交人数, 评价人总数] */
    private int[] progress(long taskId) {
        Map<String, Object> m = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS submitted" +
                " FROM uf_assess_task_rater WHERE task_id = ?", RATER_SUBMITTED, taskId);
        int total = (int) num(m.get("total"));
        int submitted = (int) num(m.get("submitted"));
        return new int[]{submitted, total};
    }

    private String toRatingsJson(Map<Long, Integer> ratings) {
        List<Map<String, Object>> items = ratings.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("elementId", e.getKey());
                    m.put("levelNo", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
        return toJson(items);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BusinessException("评级序列化失败");
        }
    }

    /** 草稿 {"59":3,"60":4} → {59:3, 60:4}；损坏的草稿按空处理，不阻断填写 */
    private Map<Long, Integer> parseDraft(String draftJson) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (draftJson == null || draftJson.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(draftJson, Map.class);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    result.put(Long.valueOf(e.getKey().toString()), n.intValue());
                }
            }
        } catch (Exception e) {
            log.warn("草稿解析失败，按空草稿处理: {}", e.getMessage());
        }
        return result;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** token 解析出的上下文，避免 page/submit 反复查同一批字段 */
    private static class RaterContext {
        long raterId;
        long taskId;
        String raterType;
        long raterResourceId;
        int raterStatus;
        String draftJson;
        int draftVersion;
        /** 已写的评价意见（上级 / 客户才有） */
        String comment;
        int taskStatus;
        /** 该任务所属年度的状态：0 进行中 1 已关闭（2026-11-19） */
        int periodStatus;
        long sheetId;
        long targetResourceId;
        long dimensionId;
        int year;
        String dimensionName;
        String targetName;
        String deptName;
    }
}
