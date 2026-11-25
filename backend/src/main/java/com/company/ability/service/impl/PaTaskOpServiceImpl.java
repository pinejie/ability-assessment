package com.company.ability.service.impl;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.dto.PaTaskOpDTO;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaPost;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AssessPushService;
import com.company.ability.service.PaScoreService;
import com.company.ability.service.PaTaskOpService;
import com.company.ability.service.PaTaskService;
import com.company.ability.vo.PaTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    /** 重新发起要复用发起侧的全部校验（重复发起 / 人员可登录性 / 部门一致性），不另写一份 */
    private final PaTaskService taskService;
    /** 岗位是否还可用（重新发起预填时要判断原岗位有没有被停用） */
    private final PaPostMapper postMapper;
    private final PaItemMapper itemMapper;

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

    // ================================================================ 重新发起

    /**
     * 重新发起的预填数据（2026-11-25）。
     *
     * <p><b>为什么不复用 {@link #detail}</b>：详情是给「看进度」用的，它回的是评价人进度，
     * 没有「谁评哪几项」（在 uf_assess_task_rater_item 里），而且详情对已撤回任务返回的
     * 评价人全是「已作废」——直接拿它回填，页面会把作废名单当成有效名单。</p>
     *
     * <p><b>带不出来的一律进 warnings，不静默改</b>：岗位停用、年度关闭、能力项被删、
     * 人已经不在人员表 —— 这些都不该让「重新发起」变成一个悄悄丢数据的动作。
     * 页面把 warnings 原样列出来，缺的那一项留空由人补。</p>
     */
    @Override
    public PaTaskVO.RelaunchDraft relaunchDraft(Long taskId) {
        TaskHead head = loadTask(taskId);
        requireSameDimension(head);
        requireVoided(head);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT t.resource_id, t.replaced_by_task_id, p.year, p.status AS period_status," +
                "       s.post_id, s.post_name," +
                "       h.lastname AS target_name, h.departmentid AS current_dept_id" +
                " FROM uf_assess_task t" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " WHERE t.id = ?", taskId);
        List<String> warnings = new ArrayList<>();

        PaTaskVO.RelaunchDraft draft = new PaTaskVO.RelaunchDraft();
        draft.setTaskId(taskId);
        draft.setSheetId(head.sheetId);
        draft.setResourceId(num(row.get("resource_id")));
        draft.setTargetName(str(row.get("target_name")));
        draft.setDepartmentId(row.get("current_dept_id") == null ? null : num(row.get("current_dept_id")));
        draft.setPostName(str(row.get("post_name")));
        draft.setReplacedByTaskId(row.get("replaced_by_task_id") == null
                ? null : num(row.get("replaced_by_task_id")));

        // 年度：关闭了的年度不能再往里发新任务（create 也会拒），所以不预选、只说明
        int year = intOf(row.get("year"));
        int periodStatus = intOf(row.get("period_status"));
        if (periodStatus == 0) {
            draft.setYear(year);
        } else {
            warnings.add("原任务的 " + year + " 年度已关闭，请重新选择年度");
        }

        // 岗位：停用/删除的岗位不能用于新任务，预填它只会让人以为能直接发起
        Long postId = row.get("post_id") == null ? null : num(row.get("post_id"));
        if (postId == null) {
            warnings.add("原任务没有关联岗位，请重新选择岗位");
        } else {
            PaPost post = postMapper.selectById(postId);
            if (post == null) {
                warnings.add("原任务的岗位「" + nullToDash(draft.getPostName()) + "」已被删除，请重新选择岗位");
                postId = null;
            } else if (post.getStatus() == null || post.getStatus() != 1) {
                warnings.add("原任务的岗位「" + post.getPostName() + "」已停用，请重新选择岗位");
                postId = null;
            } else {
                draft.setPostName(post.getPostName());
            }
        }
        draft.setPostId(postId);

        // 360 名单：一人一条 rater 行，撤回只改状态不删行，所以全量读得到
        List<Map<String, Object>> raterRows = jdbc.queryForList(
                "SELECT id, rater_resource_id FROM uf_assess_task_rater" +
                " WHERE task_id = ? AND rater_type = 'RATER360' ORDER BY id", taskId);
        Map<Long, Long> raterIdOf = new LinkedHashMap<>();  // resourceId -> raterId
        for (Map<String, Object> r : raterRows) {
            if (r.get("rater_resource_id") != null) {
                raterIdOf.put(num(r.get("rater_resource_id")), num(r.get("id")));
            }
        }
        Map<Long, List<Long>> peopleOfItem = new LinkedHashMap<>();
        Map<Long, Long> resourceOfRater = new HashMap<>();
        raterIdOf.forEach((resId, rid) -> resourceOfRater.put(rid, resId));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT rater_id, item_id FROM uf_assess_task_rater_item WHERE task_id = ? ORDER BY id", taskId)) {
            Long resId = resourceOfRater.get(num(r.get("rater_id")));
            if (resId != null) {
                peopleOfItem.computeIfAbsent(num(r.get("item_id")), k -> new ArrayList<>()).add(resId);
            }
        }
        // 完全没有指派行 = 这张表上线（sql/60）之前发的任务：当时的口径是「这批人评全部 360 项」。
        // 注意不能拿「入参是旧的 rater360ResourceIds」来判断 —— 那条路径现在也会写指派行
        //（写的就是全部 360 项），从库里看与逐项选人完全一样，没有区分的必要。
        boolean legacy = peopleOfItem.isEmpty() && !raterIdOf.isEmpty();
        draft.setLegacyAllItems(legacy);

        // 人：姓名与组织信息按当前数据取（原任务只存了 ID，人改名/调岗后显示应该跟着变）
        Map<Long, PaTaskVO.RelaunchPerson> people = loadPeople(raterIdOf.keySet(), warnings);
        draft.setRater360People(new ArrayList<>(people.values()));
        // 被测人自己的姓名/部门也要带：页面要把它塞进人员下拉的候选里，否则标签显示成 ID
        draft.setTarget(loadPeople(List.of(draft.getResourceId()), new ArrayList<>())
                .get(draft.getResourceId()));
        if (draft.getDepartmentId() == null && draft.getTarget() != null) {
            draft.setDepartmentId(draft.getTarget().getDepartmentId());
        }

        // 按项：项的身份、名字、是否仍可指派都以当前岗位能力模型为准
        List<PaTaskVO.RelaunchRaterItem> items = new ArrayList<>();
        if (postId != null) {
            List<PaItem> postItems = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                    .eq(PaItem::getPostId, postId)
                    .eq(PaItem::getStatus, 1)
                    .orderByAsc(PaItem::getCategoryId).orderByAsc(PaItem::getSort).orderByAsc(PaItem::getId));
            Map<Long, String> categoryNames = categoryNameOf(postItems);
            List<String> lostItems = new ArrayList<>();
            for (PaItem item : postItems) {
                if (!Objects.equals(item.getRater360(), 1)) {
                    // 这一项现在不给 360 评了：原任务指派过就说明，不指派就不用提
                    if (peopleOfItem.containsKey(item.getId())) {
                        lostItems.add(item.getItemName() + "（已改为只由上级评）");
                    }
                    continue;
                }
                PaTaskVO.RelaunchRaterItem ri = new PaTaskVO.RelaunchRaterItem();
                ri.setItemId(item.getId());
                ri.setItemName(item.getItemName());
                ri.setCategoryName(categoryNames.get(item.getCategoryId()));
                ri.setRaterText(item.getRaterText());
                ri.setUsable(true);
                // 旧口径：所有人评全部 360 项
                List<Long> assigned = legacy
                        ? new ArrayList<>(raterIdOf.keySet())
                        : peopleOfItem.getOrDefault(item.getId(), List.of());
                ri.setPeople(assigned.stream().map(people::get).filter(Objects::nonNull).toList());
                items.add(ri);
            }
            // 原任务指派过、但现在的岗位里已经没有这一项（项被删，或已停用不再进这个列表）
            if (!legacy) {
                List<Long> goneIds = peopleOfItem.keySet().stream()
                        .filter(id -> items.stream().noneMatch(i -> i.getItemId().equals(id)))
                        .toList();
                if (!goneIds.isEmpty()) {
                    // 名字从项表直接取：能力项停用后 selectList 查不到它，
                    // 但警告里说「能力项 #37」没法让人对照，必须给原名
                    Map<Long, String> goneNames = itemNamesOf(goneIds);
                    for (Long id : goneIds) {
                        lostItems.add(goneNames.getOrDefault(id, "能力项 #" + id));
                    }
                }
            }
            if (!lostItems.isEmpty()) {
                warnings.add("以下项已经不在岗位「" + nullToDash(draft.getPostName())
                        + "」的 360 评价范围里，未带出：" + String.join("、", lostItems));
            }
        }

        List<Long> assignedPeople = new ArrayList<>();
        for (PaTaskVO.RelaunchRaterItem ri : items) {
            ri.getPeople().forEach(p -> {
                if (!assignedPeople.contains(p.getResourceId())) {
                    assignedPeople.add(p.getResourceId());
                }
            });
        }
        // 已经不在人员表的人，上面 loadPeople 已经单独说过一次，这里不重复算
        List<Long> droppedButAlive = raterIdOf.keySet().stream()
                .filter(people::containsKey)
                .filter(id -> !assignedPeople.contains(id))
                .toList();
        if (!droppedButAlive.isEmpty()) {
            warnings.add("原任务有 " + droppedButAlive.size()
                    + " 位 360 测评人带不出来（他们只被指派到已删除或已取消 360 的项），请重新确认名单");
        }
        if (!raterIdOf.isEmpty() && assignedPeople.isEmpty()) {
            warnings.add("原任务的 360 测评人一个都没带出来，请重新选择");
        }

        draft.setRater360Items(items);
        draft.setWarnings(warnings);
        log.debug("重新发起预填 taskId={}：年度={}, 被测人={}, 岗位={}, 360 {} 位, 项 {} 个{}",
                taskId, draft.getYear(), draft.getResourceId(), draft.getPostId(),
                assignedPeople.size(), items.size(), warnings.isEmpty() ? "" : "，提示 " + warnings.size() + " 条");
        return draft;
    }

    /**
     * 按原任务重新发起（2026-11-25）。
     *
     * <p><b>为什么不在这里直接拿旧参数调一次 create</b>：本接口收的是「页面确认后的参数」。
     * 页面会把预填值摆出来给人看、可能改过（换年度、换岗位、补人），
     * 真正要发的是人确认过的那一份，而不是库里旧的那一份。
     * 复用 {@code create} 意味着「重复发起保护 / 人员可登录性 / 部门一致性」一条不少地重跑一遍。</p>
     *
     * <p>成功之后回填旧任务的 {@code replaced_by_task_id}：撤回 + 重发之后列表上是两行，
     * 没有这条线索就查不出前后关系（旧行状态 4、新行状态 0，除了备注看不出是一回事）。
     * 这一步失败不影响重发本身 —— 任务已经发出去了，关系丢了可以事后补。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaTaskVO.CreateResult relaunch(Long taskId, PaTaskDTO.CreateRequest req) {
        TaskHead head = loadTask(taskId);
        requireOperable(head);
        requireVoided(head);

        PaTaskVO.CreateResult created = taskService.create(req);
        try {
            jdbc.update("UPDATE uf_assess_task SET replaced_by_task_id = ?, update_time = SYSDATETIME()" +
                    " WHERE id = ?", created.getTaskId(), taskId);
        } catch (Exception e) {
            log.warn("回填 replaced_by_task_id 失败 taskId={}: {}", taskId, e.getMessage());
        }
        log.info("按原任务重新发起 taskId={} → 新 taskId={}", taskId, created.getTaskId());
        return created;
    }

    /** 只允许对「已撤回」任务重新发起：进行中的再发一次会撞重复发起保护，已完成的不该重来 */
    private void requireVoided(TaskHead head) {
        if (head.status == TASK_VOIDED) {
            return;
        }
        throw new BusinessException(ERR_STATE_INVALID,
                "只有已撤回的任务可以重新发起，当前状态：" + STATUS_NAMES.getOrDefault(head.status, "未知"));
    }

    /** 一批人 → 姓名与组织信息；查不到的人写一条 warning，不静默消失 */
    private Map<Long, PaTaskVO.RelaunchPerson> loadPeople(java.util.Collection<Long> resourceIds,
                                                         List<String> warnings) {
        Map<Long, PaTaskVO.RelaunchPerson> out = new LinkedHashMap<>();
        if (resourceIds.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(resourceIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.lastname, r.departmentid, d.departmentmark AS dept_name," +
                "       s.subcompanyname AS company_name" +
                " FROM HrmResource r" +
                " LEFT JOIN HrmDepartment d ON d.id = r.departmentid" +
                " LEFT JOIN HrmSubCompany s ON s.id = d.subcompanyid1" +
                " WHERE r.id IN (" + in + ")", resourceIds.toArray());
        List<String> missing = new ArrayList<>();
        for (Long id : resourceIds) {
            Map<String, Object> hit = rows.stream()
                    .filter(r -> num(r.get("id")) == id).findFirst().orElse(null);
            if (hit == null) {
                missing.add("人员 #" + id);
                continue;
            }
            PaTaskVO.RelaunchPerson p = new PaTaskVO.RelaunchPerson();
            p.setResourceId(id);
            p.setName(str(hit.get("lastname")));
            p.setDepartmentId(hit.get("departmentid") == null ? null : num(hit.get("departmentid")));
            p.setDepartmentName(str(hit.get("dept_name")));
            p.setCompanyName(str(hit.get("company_name")));
            out.put(id, p);
        }
        if (!missing.isEmpty()) {
            warnings.add("以下 360 测评人已不在人员表：" + String.join("、", missing));
        }
        return out;
    }

    /** 类别 id → 类别名（发起页那张表要按类别分组显示） */
    private Map<Long, String> categoryNameOf(List<PaItem> items) {
        List<Long> ids = items.stream().map(PaItem::getCategoryId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, category_name FROM uf_pa_category WHERE id IN (" + in + ")", ids.toArray())) {
            out.put(num(r.get("id")), str(r.get("category_name")));
        }
        return out;
    }

    /**
     * 能力项 id → 名字，<b>不看启用状态</b>。
     *
     * <p>专供「原任务指派过、但现在已经不可用」的警告文案：那些项正是被停用/删掉的，
     * 按正常查询（status=1）根本查不到，警告里就只能写 ID —— 而 HR 手上没有项 ID，
     * 说了等于没说。</p>
     */
    private Map<Long, String> itemNamesOf(List<Long> itemIds) {
        Map<Long, String> out = new HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        String in = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, item_name FROM uf_pa_item WHERE id IN (" + in + ")", itemIds.toArray())) {
            out.put(num(r.get("id")), str(r.get("item_name")));
        }
        return out;
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
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
