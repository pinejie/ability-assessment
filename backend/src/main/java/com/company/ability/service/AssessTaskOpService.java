package com.company.ability.service;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessTaskOpDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.vo.AssessTaskVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测评任务的运营操作（S3）：撤回、取消、催办、重算、列表、详情。
 * 方案：project/能力测评/15-评价网页化改造方案.md §4.3、§7.1
 *
 * 权限口径（Q9 已定）：撤回 / 取消 / 重算 / 催办 一律要求「维度管理员或 HR」，
 * **发起人身份不再作为依据** —— 撤回会物理删除评级并通知所有当事人，
 * 是不可逆操作，权限应跟随当前身份而不是历史行为。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessTaskOpService {

    static final int ERR_TASK_NOT_FOUND = 40201;
    static final int ERR_TASK_ARCHIVED = 40202;
    static final int ERR_PERIOD_CLOSED = 40203;
    // 40204（存在流程评级 / 外部导入评级禁止撤回）已于 2026-11-18 停用：
    // 撤回会连同 FLOW/EXTERNAL 评级一起删除，不再拦截（见 sql/49）。
    // 40205（存在评价意见禁止撤回）已于 2026-11-17 停用：撤回会一并删除该页意见，不再拦截。
    static final int ERR_NEED_FORCE = 40206;
    static final int ERR_FORBIDDEN = 40207;
    static final int ERR_STATE_INVALID = 40208;

    private static final int TASK_RUNNING = 0;
    private static final int TASK_DONE = 2;
    /** 已取消：2026-11-14 起取消功能已移除，此状态不再产生，仅用于兼容存量数据 */
    private static final int TASK_CANCELLED = 3;
    private static final int TASK_VOIDED = 4;

    private static final Map<Integer, String> STATUS_NAMES = Map.of(
            TASK_RUNNING, "进行中", TASK_DONE, "已完成",
            TASK_CANCELLED, "已取消", TASK_VOIDED, "已撤回");

    private static final Map<String, String> RATER_TYPE_NAMES = Map.of(
            "SELF", "自评", "LEADER", "直接上级", "CLIENT", "客户");

    private final JdbcTemplate jdbc;
    private final AssessPushService pushService;

    // ==================== 撤回（§4.3） ====================

    /**
     * 撤回任务：整任务作废，回到「评价初」（Q8 已定：不区分谁已提交）。
     *
     * 执行顺序（§8.6.4 Q14 已定）：撤回事务 → COMMIT → 再插通知消息（独立事务）。
     * 通知失败不回滚撤回，但必须让调用方感知（noticeWritten=false），不能静默。
     */
    public AssessTaskVO.VoidResult voidTask(Long taskId, AssessTaskOpDTO.VoidRequest req) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);

        // 已完成需 force 二次确认（§4.3.3）
        if (head.status == TASK_DONE && !Boolean.TRUE.equals(req.getForce())) {
            throw new BusinessException(ERR_NEED_FORCE, "该任务已完成并已算出分数，确认撤回请传 force=true");
        }
        // 「已取消」状态自 2026-11-14 起不再产生（取消功能已移除），但存量数据仍可能存在。
        // 这类任务没有评级可清，撤回无意义，引导到删除即可。
        if (head.status == TASK_CANCELLED) {
            throw new BusinessException(ERR_STATE_INVALID, "该任务已取消，无需撤回，可直接删除");
        }

        // 期次关闭后数据已冻结，不允许撤回（§4.3.3）
        Integer periodStatus = jdbc.queryForObject(
                "SELECT status FROM uf_assess_period WHERE id = ?", Integer.class, head.periodId);
        if (periodStatus != null && periodStatus == 1) {
            throw new BusinessException(ERR_PERIOD_CLOSED, "年度已关闭，请先重新开启年度再撤回");
        }

        // 已归档则拒绝（§7.1 拒绝场景）。归档表可能尚未部署，先判存在性。
        if (isArchived(head.sheetId)) {
            throw new BusinessException(ERR_TASK_ARCHIVED, "该测评已归档，无法撤回，请走人工流程");
        }

        // 2026-11-18 起不再检测「非本任务评级」：usp_assess_void 会把该页
        // FLOW / EXTERNAL 评级连同 WEB 评级一起删掉（sql/49），撤回真正回到评价初。

        long operatorId = currentOperatorId();
        Map<String, Object> proc = callVoidProc(taskId, operatorId, req.getReason());

        AssessTaskVO.VoidResult result = new AssessTaskVO.VoidResult();
        result.setTaskId(taskId);
        result.setSheetId(num(proc.get("sheetId")));
        result.setFullRollback(bool(proc.get("fullRollback")));
        result.setVoidedRatingCount(intOf(proc.get("voidedRatingCount")));
        result.setVoidedRaterCount(intOf(proc.get("voidedRaterCount")));
        result.setVoidedCommentCount(intOf(proc.get("voidedCommentCount")));
        result.setVoidedExternalCount(intOf(proc.get("voidedExternalCount")));
        result.setVoidedFlowCount(intOf(proc.get("voidedFlowCount")));
        result.setSubmittedRaterCount(intOf(proc.get("submittedRaterCount")));
        result.setSnapshotDeleted(bool(proc.get("snapshotDeleted")));
        result.setAlreadyVoided(bool(proc.get("alreadyVoided")));
        result.setSheetAfter(querySheetAfter(head.sheetId));

        if (Boolean.TRUE.equals(result.getAlreadyVoided())) {
            result.setNoticeWritten(false);
            result.setNotifiedRaterCount(0);
            result.setNoticeError("任务此前已撤回，本次未执行任何操作");
            return result;
        }

        // 撤回已提交，此后插消息（独立事务）。失败只记录，不回滚撤回。
        writeVoidNotices(taskId, req.getReason(), result);
        return result;
    }

    /**
     * 撤回通知在撤回 COMMIT 之后单独写（Q14）。
     * 只通知已提交者：待评者本就未提交、无损失（§8.6.1）。
     */
    private void writeVoidNotices(Long taskId, String reason, AssessTaskVO.VoidResult result) {
        try {
            Map<String, Object> head = taskHeadRow(taskId);
            String targetName = str(head.get("target_name"));
            int year = intOf(head.get("year"));
            List<Map<String, Object>> submitted = pushService.submittedRaters(taskId);
            if (submitted.isEmpty()) {
                result.setNoticeWritten(true);
                result.setNotifiedRaterCount(0);
                return;
            }
            int written = pushService.writeVoids(taskId, targetName, year, reason, submitted);
            result.setNoticeWritten(true);
            result.setNotifiedRaterCount(written);
            // 通知内容快照留存，便于事后核对「当时到底通知了什么」（§8.6.4）
            try {
                jdbc.update("UPDATE uf_assess_task SET void_notice_json = ? WHERE id = ?",
                        toNoticeSnapshot(targetName, year, reason, written), taskId);
            } catch (Exception e) {
                log.warn("留存通知快照失败 taskId={}: {}", taskId, e.getMessage());
            }
        } catch (Exception e) {
            // 撤回是主操作，通知是附带动作：通知丢了可以补，数据错了不能补
            log.error("撤回通知写入失败 taskId={}", taskId, e);
            result.setNoticeWritten(false);
            result.setNotifiedRaterCount(0);
            result.setNoticeError("通知消息写入失败：" + e.getMessage() + "，可在任务详情页补发");
        }
    }

    private String toNoticeSnapshot(String targetName, int year, String reason, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("targetName", targetName);
        m.put("year", year);
        m.put("reason", reason);
        m.put("notifiedCount", count);
        m.put("writtenAt", LocalDateTime.now().toString());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** 补发撤回通知（§8.6.4 兜底第 3 层，判定靠 void_notice_json 是否已写，不靠日志） */
    public AssessTaskVO.VoidResult resendVoidNotice(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        if (head.status != TASK_VOIDED) {
            throw new BusinessException(ERR_STATE_INVALID, "只有已撤回的任务需要补发通知");
        }
        Map<String, Object> row = taskHeadRow(taskId);
        String reason = str(row.get("void_reason"));
        AssessTaskVO.VoidResult result = new AssessTaskVO.VoidResult();
        result.setTaskId(taskId);
        writeVoidNotices(taskId, reason, result);
        return result;
    }

    // ==================== 取消（§7.1.1） ====================

    // ==================== 催办（§8.5） ====================

    /** 催办：只推未提交的人。轮次记在 uf_assess_task_rater.remind_count，允许催多次（§8.2） */
    public AssessTaskVO.RemindResult remind(Long taskId, AssessTaskOpDTO.RemindRequest req) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        if (head.status != TASK_RUNNING) {
            throw new BusinessException(ERR_STATE_INVALID, "只有进行中的任务可以催办");
        }

        List<Long> only = req == null ? null : req.getRaterResourceIds();
        List<Map<String, Object>> pending = pushService.pendingRaters(taskId, only);

        AssessTaskVO.RemindResult result = new AssessTaskVO.RemindResult();
        result.setTaskId(taskId);
        result.setPendingRaterCount(pending.size());
        if (pending.isEmpty()) {
            result.setPushedCount(0);
            result.setMessage("没有待评价的人，无需催办");
            return result;
        }

        int round = pushService.remindRound(taskId) + 1;
        Map<String, Object> row = taskHeadRow(taskId);
        int written = pushService.writeReminds(taskId, round, str(row.get("target_name")), pending);
        // 只累加真正被催到的人：指定催办（only）时若按 status=0 全表更新，
        // 没被催的人 remind_count 也会涨，详情页显示的「已催 N 次」就失真了。
        Long[] reminded = pending.stream()
                .map(r -> num(r.get("rater_resource_id")))
                .toArray(Long[]::new);
        if (reminded.length > 0) {
            String in = String.join(",", java.util.Collections.nCopies(reminded.length, "?"));
            Object[] args = new Object[reminded.length + 1];
            args[0] = taskId;
            System.arraycopy(reminded, 0, args, 1, reminded.length);
            jdbc.update("UPDATE uf_assess_task_rater SET remind_count = remind_count + 1, "
                    + "last_remind_time = SYSDATETIME() WHERE task_id = ? AND rater_resource_id IN (" + in + ")",
                    args);
        }

        result.setRound(round);
        result.setPushedCount(written);
        result.setMessage("已向 " + written + " 位未提交的评价人发送催办提醒");
        return result;
    }

    // ==================== 重算（§7.1.2） ====================

    /**
     * 重新计算得分。
     *
     * 允许状态只有 {0, 2}：已撤回(4)/已取消(3) 必须拒绝 —— 此时评价人全是 status=3，
     * 闸门会数出 pending=0 而放行，但评级已被删除（或停止收集），
     * 重算只会把 sheet 写成空分数，搅乱「已撤回」语义。状态校验必须在闸门之前。
     */
    public AssessTaskVO.RetryResult retry(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);

        if (head.status == TASK_VOIDED || head.status == TASK_CANCELLED) {
            throw new BusinessException(ERR_STATE_INVALID,
                    "任务" + STATUS_NAMES.get(head.status) + "，不能重算");
        }

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = 0",
                Integer.class, taskId);
        if (pending != null && pending > 0) {
            throw new BusinessException(ERR_STATE_INVALID,
                    "仍有 " + pending + " 位评价人未提交，不能重算");
        }

        // recalc 的 THROW 50104 原样上抛，由全局异常处理转成「配置有问题」提示。
        // 必须用 update 而不是 queryForList：usp_assess_recalc 是 SET NOCOUNT ON 且不返回结果集，
        // queryForList 会抛「The statement did not return a result set」（2026-11-13 端到端暴露）。
        // 与 usp_assess_task_finish 不同，后者末尾有 SELECT 回传终态，才可用 queryForList。
        jdbc.update("{call usp_assess_recalc(?)}", head.sheetId);
        // 任务若仍在进行中，重算成功后同样置完成
        if (head.status == TASK_RUNNING) {
            jdbc.update("UPDATE uf_assess_task SET status = ?, update_time = SYSDATETIME() WHERE id = ?",
                    TASK_DONE, taskId);
        }

        Map<String, Object> sheet = jdbc.queryForMap(
                "SELECT actual_score, score_rate, grade_name FROM uf_assess_sheet WHERE id = ?", head.sheetId);
        AssessTaskVO.RetryResult result = new AssessTaskVO.RetryResult();
        result.setTaskId(taskId);
        result.setSheetId(head.sheetId);
        result.setTaskStatus(head.status == TASK_RUNNING ? TASK_DONE : head.status);
        result.setActualScore(dec(sheet.get("actual_score")));
        result.setScoreRate(dec(sheet.get("score_rate")));
        result.setGradeName(str(sheet.get("grade_name")));
        log.info("重算得分 taskId={}, sheetId={}, actualScore={}", taskId, head.sheetId, result.getActualScore());
        return result;
    }

    // ==================== 删除（2026-11-14） ====================

    /**
     * 删除已作废的任务记录。
     *
     * <p>只删两处：{@code uf_assess_task} 与 {@code uf_assess_task_rater}。
     * 绝不级联到 {@code uf_assess_sheet} 及其子表 —— 那张「人+年」的测评页可能正被
     * 另一个进行中的任务引用（生产实测：sheet 14 被 6 个任务共用），
     * 删任务时连带删页面会把别人的测评一起搞坏。</p>
     *
     * <p>允许删除的状态只有已取消（3）、已撤回（4）。进行中的人还在等评价，
     * 链接一删就失效；已完成的有分数、进过报告，删了数据链就断了。</p>
     *
     * <p>这是物理删除，撤回时留的 {@code void_reason} / {@code void_notice_json}
     * 会一并消失。用户点名要的就是「删掉」，不做软删标记。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessTaskVO.DeleteResult delete(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);

        if (head.status != TASK_CANCELLED && head.status != TASK_VOIDED) {
            throw new BusinessException(ERR_STATE_INVALID,
                    "只有已取消或已撤回的任务可以删除，当前状态：" + STATUS_NAMES.get(head.status));
        }

        // 评价人先删：token 随之失效，评价页再也打不开 —— 正是期望效果。
        int raters = jdbc.update("DELETE FROM uf_assess_task_rater WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM uf_assess_task WHERE id = ?", taskId);

        log.info("删除任务 taskId={}, 状态={}, 删除评价人 {} 条, sheet {} 保留",
                taskId, STATUS_NAMES.get(head.status), raters, head.sheetId);

        AssessTaskVO.DeleteResult result = new AssessTaskVO.DeleteResult();
        result.setTaskId(taskId);
        result.setDeletedRaterCount(raters);
        result.setSheetId(head.sheetId);
        result.setSheetKept(true);
        return result;
    }

    // ==================== 列表 / 详情 ====================

    /** 任务列表（分页）。租户隔离由 MyBatis 拦截器负责，这里用 JdbcTemplate 需手写 dimension_id */
    public Map<String, Object> list(AssessTaskOpDTO.TaskQuery q) {
        long dimensionId = DimensionContext.getOrDefault();
        StringBuilder where = new StringBuilder(" WHERE t.dimension_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(dimensionId);
        if (q.getYear() != null) {
            where.append(" AND p.year = ?");
            args.add(q.getYear());
        }
        if (q.getStatus() != null) {
            where.append(" AND t.status = ?");
            args.add(q.getStatus());
        } else {
            // 未指定状态时默认隐藏作废任务（已取消 3 / 已撤回 4），列表只留进行中与已完成。
            // 用户要看作废任务，就在筛选器里明确选「已取消」或「已撤回」——那时 status 非空，
            // 走上面那条分支，不做这个过滤。不是禁止查看，只是默认不上桌。
            where.append(" AND t.status IN (" + TASK_RUNNING + ", " + TASK_DONE + ")");
        }
        if (q.getDepartmentId() != null) {
            where.append(" AND t.department_id = ?");
            args.add(q.getDepartmentId());
        }
        if (q.getKeyword() != null && !q.getKeyword().isBlank()) {
            where.append(" AND (h.lastname LIKE ? OR h.workcode LIKE ?)");
            String like = "%" + q.getKeyword().trim() + "%";
            args.add(like);
            args.add(like);
        }

        String from = " FROM uf_assess_task t" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = t.department_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id";

        Integer total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Integer.class, args.toArray());

        int page = q.getPage() == null ? 1 : Math.max(1, q.getPage());
        int size = q.getSize() == null ? 20 : Math.max(1, q.getSize());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((page - 1) * size);
        pageArgs.add(size);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id AS task_id, t.sheet_id, t.resource_id, t.status, t.rating_count, t.client_count," +
                "       t.leader_resource_id, t.remark, t.void_reason, t.create_time," +
                "       p.year, d.dimension_name, h.lastname AS target_name," +
                "       dept.departmentname AS dept_name," +
                "       lh.lastname AS leader_name," +
                "       s.actual_score, s.grade_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id) AS rater_total," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id AND r.status = 1) AS submitted_count" +
                from +
                " LEFT JOIN HrmResource lh ON lh.id = t.leader_resource_id" +
                " LEFT JOIN uf_assess_sheet s ON s.id = t.sheet_id" +
                where + " ORDER BY t.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                pageArgs.toArray());

        List<AssessTaskVO.TaskItem> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            items.add(toTaskItem(r));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0 : total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** 任务详情：任务头 + 每个评价人的进度（不含 token 原文） */
    public AssessTaskVO.TaskDetail detail(Long taskId) {
        TaskHead head = loadTask(taskId);
        long dimensionId = DimensionContext.getOrDefault();
        if (head.dimensionId != dimensionId) {
            throw new BusinessException(ERR_TASK_NOT_FOUND, "任务不存在");
        }

        Map<String, Object> row = taskHeadRow(taskId);
        AssessTaskVO.TaskDetail detail = new AssessTaskVO.TaskDetail();
        AssessTaskVO.TaskItem item = new AssessTaskVO.TaskItem();
        item.setTaskId(taskId);
        item.setSheetId(head.sheetId);
        item.setYear(intOf(row.get("year")));
        item.setResourceId(head.resourceId);
        item.setTargetName(str(row.get("target_name")));
        item.setDeptName(str(row.get("dept_name")));
        item.setDimensionName(str(row.get("dimension_name")));
        item.setStatus(head.status);
        item.setStatusName(STATUS_NAMES.getOrDefault(head.status, "未知"));
        item.setRatingCount(head.ratingCount);
        item.setClientCount(head.clientCount);
        item.setLeaderResourceId(head.leaderResourceId);
        item.setLeaderName(str(row.get("leader_name")));
        item.setRemark(head.remark);
        item.setVoidReason(head.voidReason);
        item.setCreateTime(toTime(row.get("create_time")));
        item.setActualScore(dec(row.get("actual_score")));
        item.setGradeName(str(row.get("grade_name")));
        detail.setTask(item);

        List<Map<String, Object>> raters = jdbc.queryForList(
                "SELECT r.id, r.rater_type, r.rater_resource_id, r.status, r.submit_time, r.remind_count," +
                "       r.last_remind_time, r.draft_json, h.lastname AS rater_name" +
                " FROM uf_assess_task_rater r LEFT JOIN HrmResource h ON h.id = r.rater_resource_id" +
                " WHERE r.task_id = ? ORDER BY CASE r.rater_type WHEN 'SELF' THEN 1 WHEN 'LEADER' THEN 2 ELSE 3 END, r.id",
                taskId);
        List<AssessTaskVO.RaterProgress> list = new ArrayList<>();
        for (Map<String, Object> r : raters) {
            AssessTaskVO.RaterProgress p = new AssessTaskVO.RaterProgress();
            p.setRaterId(num(r.get("id")));
            p.setRaterType(str(r.get("rater_type")));
            p.setRaterTypeName(RATER_TYPE_NAMES.getOrDefault(str(r.get("rater_type")), ""));
            p.setRaterResourceId(num(r.get("rater_resource_id")));
            p.setRaterName(str(r.get("rater_name")));
            p.setStatus(intOf(r.get("status")));
            p.setStatusName(raterStatusName(intOf(r.get("status"))));
            p.setSubmitTime(toTime(r.get("submit_time")));
            p.setRemindCount(intOf(r.get("remind_count")));
            p.setLastRemindTime(toTime(r.get("last_remind_time")));
            p.setHasDraft(r.get("draft_json") != null);
            list.add(p);
        }
        detail.setRaters(list);
        int submitted = (int) list.stream().filter(p -> p.getStatus() != null && p.getStatus() == 1).count();
        item.setRaterTotal(list.size());
        item.setSubmittedCount(submitted);
        return detail;
    }

    /** 「我的评价」列表（§7.2.1）：只按登录身份过滤，不接受任何 resourceId 参数 */
    public List<AssessTaskVO.MyTaskItem> myTasks() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(ERR_FORBIDDEN, "请先登录");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.id AS rater_task_id, tr.access_token, tr.rater_type, tr.status AS rater_status," +
                "       t.id AS task_id, t.status AS task_status, p.year, t.resource_id AS target_resource_id," +
                "       h.lastname AS target_name, d.dimension_name, dept.departmentname AS dept_name" +
                " FROM uf_assess_task_rater tr" +
                " JOIN uf_assess_task t ON t.id = tr.task_id" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = h.departmentid" +
                " WHERE tr.rater_resource_id = ?" +
                "   AND tr.status IN (0, 1)" +   // 未作废（Q7：已撤回/已取消在 SQL 层就滤掉）
                "   AND t.status IN (0, 2)" +
                // 年度关闭后不再出现待办（2026-11-19）：关闭 = 该年度数据定稿，
                // 未提交的不该再被催着填 —— 填写页也会拒绝提交，留着列表项只会点进去吃闭门羹。
                // 已提交的（tr.status=1）保留：评价人可能想回头查自己当初怎么评的，
                // 且关闭后报告快照已冻结，那条记录就是唯一凭证，隐藏等于数据凭空消失。
                // tr.status IN (0,1) 已在上方限定，这里只需判定「进行中年度 或 本人已提交」。
                "   AND (p.status = 0 OR tr.status = 1)" +
                " ORDER BY t.status ASC, p.year DESC, t.id DESC",
                user.resourceId());

        List<AssessTaskVO.MyTaskItem> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            AssessTaskVO.MyTaskItem m = new AssessTaskVO.MyTaskItem();
            m.setRaterTaskId(num(r.get("rater_task_id")));
            m.setTaskId(num(r.get("task_id")));
            m.setTargetName(str(r.get("target_name")));
            m.setDeptName(str(r.get("dept_name")));
            m.setYear(intOf(r.get("year")));
            m.setDimensionName(str(r.get("dimension_name")));
            m.setRaterType(str(r.get("rater_type")));
            m.setRaterTypeName(RATER_TYPE_NAMES.getOrDefault(str(r.get("rater_type")), ""));
            m.setRaterStatus(intOf(r.get("rater_status")));
            m.setTaskStatus(intOf(r.get("task_status")));
            m.setAccessToken(str(r.get("access_token")));
            m.setPath("/assess/fill/" + str(r.get("access_token")));
            m.setStatusText(myStatusText(intOf(r.get("task_status")), intOf(r.get("rater_status"))));
            out.add(m);
        }
        return out;
    }

    /** §7.2.1 状态文案映射表，前端不再各自判断 */
    private static String myStatusText(int taskStatus, int raterStatus) {
        if (taskStatus == TASK_DONE) {
            return "已完成";
        }
        return raterStatus == 1 ? "已提交 · 等待其他评价人" : "待评价";
    }

    private static String raterStatusName(int status) {
        return switch (status) {
            case 0 -> "待评价";
            case 1 -> "已提交";
            case 3 -> "已作废";
            default -> "未知";
        };
    }

    // ==================== 私有 ====================

    /** 归档检查：归档表可能尚未部署，先判表存在性再查（生产当前无 uf_assess_subject） */
    private boolean isArchived(long sheetId) {
        Integer exists = jdbc.queryForObject(
                "SELECT CASE WHEN OBJECT_ID('dbo.uf_assess_subject', 'U') IS NULL THEN 0 ELSE 1 END",
                Integer.class);
        if (exists == null || exists == 0) {
            return false;
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_subject WHERE sheet_id = ?", Integer.class, sheetId);
        return n != null && n > 0;
    }

    private Map<String, Object> callVoidProc(Long taskId, long operatorId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "{call usp_assess_void(?,?,?)}", taskId, operatorId, reason);
        return rows.isEmpty() ? new HashMap<>() : rows.get(0);
    }

    private AssessTaskVO.SheetAfter querySheetAfter(long sheetId) {
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT id, status, standard_score, actual_score, score_rate, grade_name " +
                "FROM uf_assess_sheet WHERE id = ?", sheetId);
        AssessTaskVO.SheetAfter after = new AssessTaskVO.SheetAfter();
        after.setSheetId(num(s.get("id")));
        after.setStatus(intOf(s.get("status")));
        after.setStandardScore(dec(s.get("standard_score")));
        after.setActualScore(dec(s.get("actual_score")));
        after.setScoreRate(dec(s.get("score_rate")));
        after.setGradeName(str(s.get("grade_name")));
        return after;
    }

    /** 权限 + 维度归属校验：Q9 已定只有维度管理员/HR 能操作 */
    private void requireOperable(TaskHead head) {
        long currentDim = DimensionContext.getOrDefault();
        if (head.dimensionId != currentDim) {
            // 不暴露「任务存在但你没权限」，统一按不存在处理
            throw new BusinessException(ERR_TASK_NOT_FOUND, "任务不存在");
        }
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(ERR_FORBIDDEN, "请先登录");
        }
        // 维度授权判定由 DimensionContextFilter 在入口完成（管理员豁免 + 维度授权表），
        // 能进到这里的请求已通过该闸门；此处再兜一次身份非空即可。
    }

    private TaskHead loadTask(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.sheet_id, t.period_id, t.dimension_id, t.resource_id, t.status," +
                "       t.rating_count, t.client_count, t.leader_resource_id, t.remark, t.void_reason" +
                " FROM uf_assess_task t WHERE t.id = ?", taskId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_TASK_NOT_FOUND, "任务不存在");
        }
        Map<String, Object> r = rows.get(0);
        TaskHead h = new TaskHead();
        h.taskId = num(r.get("id"));
        h.sheetId = num(r.get("sheet_id"));
        h.periodId = num(r.get("period_id"));
        h.dimensionId = num(r.get("dimension_id"));
        h.resourceId = num(r.get("resource_id"));
        h.status = intOf(r.get("status"));
        h.ratingCount = intOf(r.get("rating_count"));
        h.clientCount = intOf(r.get("client_count"));
        h.leaderResourceId = r.get("leader_resource_id") == null ? null : num(r.get("leader_resource_id"));
        h.remark = str(r.get("remark"));
        h.voidReason = str(r.get("void_reason"));
        return h;
    }

    private Map<String, Object> taskHeadRow(Long taskId) {
        return jdbc.queryForMap(
                "SELECT t.id, t.status, t.void_reason, t.create_time, p.year," +
                "       h.lastname AS target_name, dept.departmentname AS dept_name," +
                "       d.dimension_name, lh.lastname AS leader_name," +
                "       s.actual_score, s.grade_name, s.standard_score" +
                " FROM uf_assess_task t" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = t.department_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN HrmResource lh ON lh.id = t.leader_resource_id" +
                " LEFT JOIN uf_assess_sheet s ON s.id = t.sheet_id" +
                " WHERE t.id = ?", taskId);
    }

    private AssessTaskVO.TaskItem toTaskItem(Map<String, Object> r) {
        AssessTaskVO.TaskItem item = new AssessTaskVO.TaskItem();
        item.setTaskId(num(r.get("task_id")));
        item.setSheetId(r.get("sheet_id") == null ? null : num(r.get("sheet_id")));
        item.setYear(intOf(r.get("year")));
        item.setResourceId(num(r.get("resource_id")));
        item.setTargetName(str(r.get("target_name")));
        item.setDeptName(str(r.get("dept_name")));
        item.setDimensionName(str(r.get("dimension_name")));
        item.setStatus(intOf(r.get("status")));
        item.setStatusName(STATUS_NAMES.getOrDefault(intOf(r.get("status")), "未知"));
        item.setRatingCount(intOf(r.get("rating_count")));
        item.setClientCount(intOf(r.get("client_count")));
        item.setRaterTotal(intOf(r.get("rater_total")));
        item.setSubmittedCount(intOf(r.get("submitted_count")));
        item.setLeaderResourceId(r.get("leader_resource_id") == null ? null : num(r.get("leader_resource_id")));
        item.setLeaderName(str(r.get("leader_name")));
        item.setRemark(str(r.get("remark")));
        item.setVoidReason(str(r.get("void_reason")));
        item.setCreateTime(toTime(r.get("create_time")));
        item.setActualScore(dec(r.get("actual_score")));
        item.setGradeName(str(r.get("grade_name")));
        return item;
    }

    private void writeLog(long sheetId, long dimensionId, String action, String detailJson) {
        try {
            jdbc.update("INSERT INTO uf_assess_sheet_log " +
                    "(sheet_id, action, operator_resource_id, detail_json, create_time, dimension_id) " +
                    "VALUES (?, ?, ?, ?, SYSDATETIME(), ?)",
                    sheetId, action, currentOperatorId(), detailJson, dimensionId);
        } catch (Exception e) {
            log.warn("写操作日志失败 sheetId={}, action={}: {}", sheetId, action, e.getMessage());
        }
    }

    private long currentOperatorId() {
        LoginUser user = UserContext.currentOrNull();
        return user == null || user.resourceId() == null ? 0L : user.resourceId();
    }

    private static LocalDateTime toTime(Object o) {
        if (o instanceof LocalDateTime t) {
            return t;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(o.toString());
    }

    /** 任务头字段（避免 detail/list 反复查同一批列） */
    private static class TaskHead {
        long taskId;
        long sheetId;
        long periodId;
        long dimensionId;
        long resourceId;
        int status;
        int ratingCount;
        int clientCount;
        Long leaderResourceId;
        String remark;
        String voidReason;
    }
}
