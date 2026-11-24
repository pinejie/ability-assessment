package com.company.ability.service.impl;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AssessPushService;
import com.company.ability.service.PaScoreService;
import com.company.ability.service.PaTaskOpService;
import com.company.ability.vo.PaTaskVO;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 测评任务运营实现（方案 17 §7.2）。
 *
 * <p>与旧 {@code AssessTaskOpService} 的差别：撤回不再走 {@code usp_assess_void} 存储过程，
 * 而是在这里显式做三件事 —— 删评级、重置页面、作废评价人。过程里那些
 * 「外部导入评级 / 流程钩子评级 / 年度分」的特例随旧表一起在 P3 删除，新模型只有一处评级来源。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaTaskOpServiceImpl implements PaTaskOpService {

    static final int ERR_TASK_NOT_FOUND = 40201;
    static final int ERR_PERIOD_CLOSED = 40203;
    static final int ERR_NEED_FORCE = 40206;
    static final int ERR_FORBIDDEN = 40207;
    static final int ERR_STATE_INVALID = 40208;
    /** 页面已封存（sql/64）：撤回 / 重算会改写分数，与快照分叉，必须先解除封存 */
    static final int ERR_SHEET_SEALED = 40209;

    /** uf_assess_task.status */
    private static final int TASK_RUNNING = 0;
    private static final int TASK_DONE = 2;
    /** 已取消：2026-11-14 起取消功能已移除，此状态不再产生，仅用于兼容存量数据 */
    private static final int TASK_CANCELLED = 3;
    private static final int TASK_VOIDED = 4;

    /** uf_assess_task_rater.status */
    private static final int RATER_SUBMITTED = 1;
    private static final int RATER_VOIDED = 3;

    /** uf_pa_sheet.status（无评价） */
    private static final int SHEET_INIT = 0;

    private static final Map<Integer, String> STATUS_NAMES = Map.of(
            TASK_RUNNING, "进行中", TASK_DONE, "已完成",
            TASK_CANCELLED, "已取消", TASK_VOIDED, "已撤回");

    private static final Map<String, String> RATER_TYPE_NAMES = Map.of(
            "SELF", "自评", "LEADER", "直接上级", "RATER360", "360 测评人");

    private final JdbcTemplate jdbc;
    private final AssessPushService pushService;
    private final PaScoreService scoreService;
    private final ObjectMapper objectMapper;

    // ================================================================ 撤回

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaTaskVO.VoidResult voidTask(Long taskId, PaTaskOpDTO.VoidRequest req) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        requireNotSealed(head.sheetId);

        PaTaskVO.VoidResult result = new PaTaskVO.VoidResult();
        result.setTaskId(taskId);
        result.setSheetId(head.sheetId);

        // 幂等：已撤回的直接返回，不重复删评级
        if (head.status == TASK_VOIDED) {
            result.setAlreadyVoided(true);
            result.setFullRollback(false);
            result.setNoticeWritten(false);
            result.setNotifiedRaterCount(0);
            result.setNoticeError("任务此前已撤回，本次未执行任何操作");
            result.setSheetAfter(sheetAfter(head.sheetId));
            return result;
        }
        if (head.status == TASK_CANCELLED) {
            throw new BusinessException(ERR_STATE_INVALID, "该任务已取消，无需撤回，可直接删除");
        }
        if (head.status == TASK_DONE && !Boolean.TRUE.equals(req.getForce())) {
            throw new BusinessException(ERR_NEED_FORCE, "该任务已完成并已算出分数，确认撤回请传 force=true");
        }
        // 年度关闭后数据已冻结，不允许撤回
        Integer periodStatus = jdbc.queryForObject(
                "SELECT status FROM uf_assess_period WHERE id = ?", Integer.class, head.periodId);
        if (periodStatus != null && periodStatus == 1) {
            throw new BusinessException(ERR_PERIOD_CLOSED, "年度已关闭，请先重新开启年度再撤回");
        }

        // ① 删评级：该页上所有评价人的评级都不再有效（撤回 = 回到评价初）
        int ratings = jdbc.update("DELETE FROM uf_pa_rating WHERE sheet_id = ?", head.sheetId);
        // ② 页面行得分清空、页面回到「无评价」
        int comments = jdbc.update(
                "UPDATE uf_pa_sheet_item SET leader_score = NULL, rater360_score = NULL, self_score = NULL, " +
                "item_score = NULL, item_level_name = NULL, rater360_count = 0 WHERE sheet_id = ?", head.sheetId)
                + jdbc.update("UPDATE uf_pa_sheet SET status = ?, leader_score = NULL, rater360_score = NULL, " +
                        "self_score = NULL, total_score = NULL, band_name = NULL, comment_text = NULL, " +
                        "snapshot_json = NULL, update_time = SYSDATETIME() WHERE id = ?", SHEET_INIT, head.sheetId);
        // ③ 评价人作废（token 随之失效）
        int raters = jdbc.update("UPDATE uf_assess_task_rater SET status = ? WHERE task_id = ? AND status <> ?",
                RATER_VOIDED, taskId, RATER_VOIDED);

        int submitted = submittedCount(taskId);
        // ④ 任务置已撤回
        jdbc.update("UPDATE uf_assess_task SET status = ?, void_by = ?, void_time = SYSDATETIME(), " +
                        "void_reason = ?, update_time = SYSDATETIME() WHERE id = ?",
                TASK_VOIDED, currentOperatorId(), req.getReason(), taskId);

        result.setFullRollback(true);
        result.setVoidedRatingCount(ratings);
        result.setVoidedRaterCount(raters);
        result.setVoidedCommentCount(comments);
        result.setSubmittedRaterCount(submitted);
        result.setAlreadyVoided(false);
        result.setSheetAfter(sheetAfter(head.sheetId));

        writeVoidNotices(taskId, req.getReason(), result);
        log.info("撤回任务 taskId={}, sheetId={}, 删评级 {} 条, 作废评价人 {} 位, 通知 {} 人",
                taskId, head.sheetId, ratings, raters, result.getNotifiedRaterCount());
        return result;
    }

    @Override
    public PaTaskVO.VoidResult resendVoidNotice(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        if (head.status != TASK_VOIDED) {
            throw new BusinessException(ERR_STATE_INVALID, "只有已撤回的任务需要补发通知");
        }
        Map<String, Object> row = taskHeadRow(taskId);
        PaTaskVO.VoidResult result = new PaTaskVO.VoidResult();
        result.setTaskId(taskId);
        result.setSheetId(head.sheetId);
        writeVoidNotices(taskId, str(row.get("void_reason")), result);
        return result;
    }

    /**
     * 撤回通知：只发给已提交者（待评者本就未提交、无损失；重新发起时会收到新邀请）。
     * 撤回已提交，此后插消息失败不回滚撤回 —— 通知丢了可以补，数据错了不能补。
     */
    private void writeVoidNotices(Long taskId, String reason, PaTaskVO.VoidResult result) {
        try {
            Map<String, Object> row = taskHeadRow(taskId);
            List<Map<String, Object>> submitted = pushService.submittedRaters(taskId);
            if (submitted.isEmpty()) {
                result.setNoticeWritten(true);
                result.setNotifiedRaterCount(0);
                return;
            }
            int written = pushService.writeVoids(taskId, str(row.get("target_name")),
                    intOf(row.get("year")), reason, submitted);
            result.setNoticeWritten(true);
            result.setNotifiedRaterCount(written);
            try {
                jdbc.update("UPDATE uf_assess_task SET void_notice_json = ? WHERE id = ?",
                        noticeSnapshot(str(row.get("target_name")), intOf(row.get("year")), reason, written), taskId);
            } catch (Exception e) {
                log.warn("留存通知快照失败 taskId={}: {}", taskId, e.getMessage());
            }
        } catch (Exception e) {
            log.error("撤回通知写入失败 taskId={}", taskId, e);
            result.setNoticeWritten(false);
            result.setNotifiedRaterCount(0);
            result.setNoticeError("通知消息写入失败：" + e.getMessage() + "，可在任务详情页补发");
        }
    }

    private String noticeSnapshot(String targetName, int year, String reason, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put("targetName", targetName);
        m.put("year", year);
        m.put("reason", reason);
        m.put("notifiedCount", count);
        m.put("writtenAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ 催办

    @Override
    public PaTaskVO.RemindResult remind(Long taskId, PaTaskOpDTO.RemindRequest req) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        if (head.status != TASK_RUNNING) {
            throw new BusinessException(ERR_STATE_INVALID, "只有进行中的任务可以催办");
        }

        List<Long> only = req == null ? null : req.getRaterResourceIds();
        List<Map<String, Object>> pending = pushService.pendingRaters(taskId, only);

        PaTaskVO.RemindResult result = new PaTaskVO.RemindResult();
        result.setTaskId(taskId);
        result.setPendingRaterCount(pending.size());
        if (pending.isEmpty()) {
            result.setPushedCount(0);
            result.setRound(pushService.remindRound(taskId));
            result.setMessage("没有待评价的人，无需催办");
            return result;
        }

        int round = pushService.remindRound(taskId) + 1;
        Map<String, Object> row = taskHeadRow(taskId);
        int written = pushService.writeReminds(taskId, round, intOf(row.get("year")),
                str(row.get("target_name")), pending);

        // 只累加真正被催到的人：指定催办时若按 status=0 全表更新，
        // 没被催的人 remind_count 也会涨，详情页的「已催 N 次」就失真了。
        Long[] reminded = pending.stream().map(r -> num(r.get("rater_resource_id"))).toArray(Long[]::new);
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

    // ================================================================ 重算

    /**
     * 重新计算得分。
     *
     * <p>允许状态只有 {0, 2}：已撤回(4) 必须拒绝 —— 此时评价人全是 status=3，
     * 闸门会数出 pending=0 而放行，但评级已被删除，重算只会把页面写成空分数，
     * 搅乱「已撤回」语义。状态校验必须在闸门之前。</p>
     */
    @Override
    public PaTaskVO.RetryResult retry(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        requireNotSealed(head.sheetId);
        if (head.status == TASK_VOIDED || head.status == TASK_CANCELLED) {
            throw new BusinessException(ERR_STATE_INVALID,
                    "任务" + STATUS_NAMES.get(head.status) + "，不能重算");
        }

        // 不含自评（口径 #13）：自评不参与计算，没交自评不能卡住重算。
        // 这里必须与提交侧 pendingCount 用同一口径，否则会出现
        //「自动算分认为齐了、手工重算说没齐」的分裂。
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = 0" +
                        " AND rater_type <> 'SELF'",
                Integer.class, taskId);
        if (pending != null && pending > 0) {
            throw new BusinessException(ERR_STATE_INVALID, "仍有 " + pending + " 位评价人未提交，不能重算");
        }

        PaTaskVO.RetryResult result = new PaTaskVO.RetryResult();
        result.setTaskId(taskId);
        result.setSheetId(head.sheetId);
        if (head.status == TASK_RUNNING) {
            // 全员齐备却仍是进行中（历史数据/手工改过状态）：这次重算同时收口
            result.setTaskStatus(TASK_DONE);
            result.setSheetStatus(scoreService.finish(taskId).getStatus());
        } else {
            result.setTaskStatus(head.status);
            result.setSheetStatus(scoreService.recalculate(head.sheetId).getStatus());
        }

        Map<String, Object> sheet = jdbc.queryForMap(
                "SELECT total_score, band_name FROM uf_pa_sheet WHERE id = ?", head.sheetId);
        result.setTotalScore(dec(sheet.get("total_score")));
        result.setBandName(str(sheet.get("band_name")));
        log.info("重算得分 taskId={}, sheetId={}, totalScore={}", taskId, head.sheetId, result.getTotalScore());
        return result;
    }

    // ================================================================ 删除

    /**
     * 删除已作废的任务记录。
     *
     * <p>只删 {@code uf_assess_task} 与 {@code uf_assess_task_rater}。
     * 绝不级联到 {@code uf_pa_sheet}：那张「人+年」的测评页可能正被另一个进行中的任务引用，
     * 删任务时连带删页面会把别人的测评一起搞坏。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaTaskVO.DeleteResult delete(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        if (head.status != TASK_CANCELLED && head.status != TASK_VOIDED) {
            throw new BusinessException(ERR_STATE_INVALID,
                    "只有已撤回的任务可以删除，当前状态：" + STATUS_NAMES.get(head.status));
        }

        // 指派关系跟着 rater 行走：rater 删了，指向它的行就是孤儿。
        // 生产上的外键没建级联（跨两套表结构，级联删除会让撤回路径不可控），所以在这里显式清。
        int assignRows = jdbc.update("DELETE FROM uf_assess_task_rater_item WHERE task_id = ?", taskId);
        int raters = jdbc.update("DELETE FROM uf_assess_task_rater WHERE task_id = ?", taskId);
        jdbc.update("DELETE FROM uf_assess_task WHERE id = ?", taskId);
        log.info("删除任务 taskId={}, 状态={}, 删除评价人 {} 条、按项指派 {} 条, sheet {} 保留",
                taskId, STATUS_NAMES.get(head.status), raters, assignRows, head.sheetId);

        PaTaskVO.DeleteResult result = new PaTaskVO.DeleteResult();
        result.setTaskId(taskId);
        result.setDeletedRaterCount(raters);
        result.setSheetId(head.sheetId);
        result.setSheetKept(true);
        return result;
    }

    // ================================================================ 列表 / 详情

    @Override
    public Map<String, Object> list(PaTaskOpDTO.TaskQuery q) {
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
            // 未指定状态时默认隐藏作废任务，列表只留进行中与已完成。
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
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN HrmResource lh ON lh.id = t.leader_resource_id" +
                " LEFT JOIN uf_pa_sheet s ON s.id = t.sheet_id";

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
                "       dept.departmentname AS dept_name, lh.lastname AS leader_name," +
                "       s.post_name, s.total_score, s.band_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id) AS rater_total," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id AND r.status = 1) AS submitted_count," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id" +
                "          AND r.rater_type = 'SELF' AND r.status = 0) AS self_pending" +
                from + where + " ORDER BY t.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                pageArgs.toArray());

        List<PaTaskVO.TaskItem> items = new ArrayList<>();
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

    @Override
    public PaTaskVO.TaskDetail detail(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireSameDimension(head);

        Map<String, Object> row = taskHeadRow(taskId);
        PaTaskVO.TaskDetail detail = new PaTaskVO.TaskDetail();
        detail.setTask(toTaskItem(taskRow(taskId)));

        List<Map<String, Object>> raters = jdbc.queryForList(
                "SELECT r.id, r.rater_type, r.rater_resource_id, r.status, r.submit_time, r.remind_count," +
                "       r.last_remind_time, r.draft_json, r.comment, h.lastname AS rater_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater_item ri WHERE ri.rater_id = r.id) AS item_count" +
                " FROM uf_assess_task_rater r LEFT JOIN HrmResource h ON h.id = r.rater_resource_id" +
                " WHERE r.task_id = ? ORDER BY CASE r.rater_type WHEN 'SELF' THEN 1 WHEN 'LEADER' THEN 2 ELSE 3 END, r.id",
                taskId);
        List<PaTaskVO.RaterProgress> list = new ArrayList<>();
        for (Map<String, Object> r : raters) {
            PaTaskVO.RaterProgress p = new PaTaskVO.RaterProgress();
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
            // 只有 360 有「按项指派」的概念；自评/上级评的是身份对应的全部项，留 null 让前端不显示项数
            int assigned = intOf(r.get("item_count"));
            p.setItemCount("RATER360".equals(p.getRaterType()) && assigned > 0 ? assigned : null);
            list.add(p);
        }
        detail.setRaters(list);
        detail.getTask().setRaterTotal(list.size());
        detail.getTask().setSubmittedCount((int) list.stream()
                .filter(p -> p.getStatus() != null && p.getStatus() == RATER_SUBMITTED).count());
        log.debug("任务详情 taskId={}, year={}, 评价人 {}{}", taskId, row.get("year"), list.size(),
                row.get("post_name") == null ? "" : "，岗位 " + row.get("post_name"));
        return detail;
    }

    @Override
    public List<PaTaskVO.MyTaskItem> myTasks(Integer year) {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(ERR_FORBIDDEN, "请先登录");
        }
        List<Object> args = new ArrayList<>();
        args.add(user.resourceId());
        String yearFilter = "";
        if (year != null) {
            yearFilter = "   AND p.year = ?";
            args.add(year);
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
                "   AND tr.status IN (0, 1)" +          // 未作废（已撤回的在 SQL 层就滤掉）
                "   AND t.status IN (0, 2)" +
                // 年度关闭后不再出现待办：关闭 = 该年度数据定稿，未提交的不该再被催着填。
                // 已提交的（tr.status=1）保留 —— 评价人可能想回头查自己当初怎么评的。
                "   AND (p.status = 0 OR tr.status = 1)" +
                yearFilter +
                " ORDER BY t.status ASC, p.year DESC, t.id DESC",
                args.toArray());

        List<PaTaskVO.MyTaskItem> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            PaTaskVO.MyTaskItem m = new PaTaskVO.MyTaskItem();
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
            m.setPath("/pa/assess-fill/" + str(r.get("access_token")));
            m.setStatusText(myStatusText(intOf(r.get("task_status")), intOf(r.get("rater_status"))));
            out.add(m);
        }
        return out;
    }

    private static String myStatusText(int taskStatus, int raterStatus) {
        if (taskStatus == TASK_DONE) {
            return "已完成";
        }
        return raterStatus == RATER_SUBMITTED ? "已提交 · 等待其他评价人" : "待评价";
    }

    // ================================================================ 私有

    private Map<String, Object> taskRow(Long taskId) {
        return jdbc.queryForMap(
                "SELECT t.id AS task_id, t.sheet_id, t.resource_id, t.status, t.rating_count, t.client_count," +
                "       t.leader_resource_id, t.remark, t.void_reason, t.create_time," +
                "       p.year, d.dimension_name, h.lastname AS target_name," +
                "       dept.departmentname AS dept_name, lh.lastname AS leader_name," +
                "       s.post_name, s.total_score, s.band_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id) AS rater_total," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id AND r.status = 1) AS submitted_count," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater r WHERE r.task_id = t.id" +
                "          AND r.rater_type = 'SELF' AND r.status = 0) AS self_pending" +
                " FROM uf_assess_task t" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = t.department_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN HrmResource lh ON lh.id = t.leader_resource_id" +
                " LEFT JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                " WHERE t.id = ?", taskId);
    }

    private Map<String, Object> taskHeadRow(Long taskId) {
        return jdbc.queryForMap(
                "SELECT t.id, t.status, t.void_reason, t.create_time, p.year," +
                "       h.lastname AS target_name, s.post_name, s.total_score, s.band_name" +
                " FROM uf_assess_task t" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                " WHERE t.id = ?", taskId);
    }

    private PaTaskVO.TaskItem toTaskItem(Map<String, Object> r) {
        PaTaskVO.TaskItem item = new PaTaskVO.TaskItem();
        item.setTaskId(num(r.get("task_id")));
        item.setSheetId(r.get("sheet_id") == null ? null : num(r.get("sheet_id")));
        item.setYear(intOf(r.get("year")));
        item.setResourceId(num(r.get("resource_id")));
        item.setTargetName(str(r.get("target_name")));
        item.setDeptName(str(r.get("dept_name")));
        item.setPostName(str(r.get("post_name")));
        item.setDimensionName(str(r.get("dimension_name")));
        item.setStatus(intOf(r.get("status")));
        item.setStatusName(STATUS_NAMES.getOrDefault(intOf(r.get("status")), "未知"));
        item.setSelfPending(intOf(r.get("self_pending")) > 0);
        item.setItemCount(intOf(r.get("rating_count")));
        item.setRater360Count(intOf(r.get("client_count")));
        item.setRaterTotal(intOf(r.get("rater_total")));
        item.setSubmittedCount(intOf(r.get("submitted_count")));
        item.setLeaderResourceId(r.get("leader_resource_id") == null ? null : num(r.get("leader_resource_id")));
        item.setLeaderName(str(r.get("leader_name")));
        item.setRemark(str(r.get("remark")));
        item.setVoidReason(str(r.get("void_reason")));
        item.setCreateTime(toTime(r.get("create_time")));
        item.setTotalScore(dec(r.get("total_score")));
        item.setBandName(str(r.get("band_name")));
        return item;
    }

    private PaTaskVO.SheetAfter sheetAfter(long sheetId) {
        Map<String, Object> s = jdbc.queryForMap(
                "SELECT id, status, total_score, band_name FROM uf_pa_sheet WHERE id = ?", sheetId);
        PaTaskVO.SheetAfter after = new PaTaskVO.SheetAfter();
        after.setSheetId(num(s.get("id")));
        after.setStatus(intOf(s.get("status")));
        after.setTotalScore(dec(s.get("total_score")));
        after.setBandName(str(s.get("band_name")));
        return after;
    }

    private int submittedCount(long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND submit_time IS NOT NULL",
                Integer.class, taskId);
        return n == null ? 0 : n;
    }

    /** 权限 + 维度归属校验：维度授权由 DimensionContextFilter 在入口完成 */
    private void requireOperable(TaskHead head) {
        requireSameDimension(head);
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(ERR_FORBIDDEN, "请先登录");
        }
    }

    private void requireSameDimension(TaskHead head) {
        if (head.dimensionId != DimensionContext.getOrDefault()) {
            // 不暴露「任务存在但你没权限」，统一按不存在处理
            throw new BusinessException(ERR_TASK_NOT_FOUND, "任务不存在");
        }
    }

    /**
     * 已封存的页面不允许撤回 / 重算（sql/64）。
     *
     * <p>这两个动作都会改写分数或把页面清空，而封存快照里存的是封存那一刻的分数 ——
     * 放行就等于让「已封存」那一份与库里真实数据悄悄分叉，而本人看到的永远是旧的那份。
     * 要求先解除封存，是把「要不要改动已确认的报告」变成一次明确的决定。</p>
     */
    private void requireNotSealed(long sheetId) {
        Integer seal = jdbc.queryForObject(
                "SELECT seal_status FROM uf_pa_sheet WHERE id = ?", Integer.class, sheetId);
        if (seal != null && seal == 1) {
            throw new BusinessException(ERR_SHEET_SEALED, "该报告已封存，请先在测评报告页解除封存再操作");
        }
    }

    private TaskHead loadTask(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.sheet_id, t.period_id, t.dimension_id, t.status" +
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
        h.status = intOf(r.get("status"));
        return h;
    }

    private static String raterStatusName(int status) {
        return switch (status) {
            case 0 -> "待评价";
            case 1 -> "已提交";
            case 3 -> "已作废";
            default -> "未知";
        };
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

    /** 任务头字段（避免 detail/list 反复查同一批列） */
    private static class TaskHead {
        long taskId;
        long sheetId;
        long periodId;
        long dimensionId;
        int status;
    }
}
