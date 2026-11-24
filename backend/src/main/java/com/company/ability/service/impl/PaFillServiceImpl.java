package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.dto.PaFillDTO;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaItemLevelMapper;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.PaFillService;
import com.company.ability.service.PaScoreService;
import com.company.ability.vo.PaFillItemVO;
import com.company.ability.vo.PaFillPageVO;
import com.company.ability.vo.PaItemLevelVO;
import com.company.ability.vo.PaTaskVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评价填写实现（方案 17 §7.2）。
 *
 * <p>与旧 {@code AssessFillService} 的差别，改之前先读：</p>
 * <ul>
 *   <li>评的是<b>等级 ID</b>而不是等级号 —— 等级是每个能力项自己的一套，可增删、无 level_no。</li>
 *   <li>评级直接写 {@code uf_pa_rating}（不再走 {@code usp_assess_rate} 存储过程）。</li>
 *   <li>算分走 {@link PaScoreService}，单人提交<b>不出分</b>，全员齐备才收敛终态。</li>
 *   <li>评语：直接上级与 360 必填、自评不要求（口径 #14）。</li>
 * </ul>
 */
@Slf4j
@Service
public class PaFillServiceImpl implements PaFillService {

    static final int ERR_TOKEN_INVALID = 40401;
    static final int ERR_FORBIDDEN = 40402;
    static final int ERR_TASK_NOT_RUNNING = 40403;
    static final int ERR_RATINGS_INCOMPLETE = 40404;
    static final int ERR_LEVEL_INVALID = 40405;
    static final int ERR_ALREADY_SUBMITTED = 40407;
    static final int ERR_RESUBMIT_FORBIDDEN = 40408;
    static final int ERR_COMMENT_REQUIRED = 40409;
    static final int ERR_COMMENT_TOO_LONG = 40410;
    static final int ERR_PERIOD_CLOSED = 40411;

    /** uf_assess_task.status */
    private static final int TASK_RUNNING = 0;
    private static final int TASK_DONE = 2;
    private static final int TASK_CANCELLED = 3;
    private static final int TASK_VOIDED = 4;

    /** uf_assess_task_rater.status */
    private static final int RATER_PENDING = 0;
    private static final int RATER_SUBMITTED = 1;
    private static final int RATER_VOIDED = 3;

    /** uf_assess_period.status */
    private static final int PERIOD_CLOSED = 1;

    private static final Map<String, String> RATER_TYPE_NAMES = Map.of(
            "SELF", "自评", "LEADER", "直接上级", "RATER360", "360 测评人");

    /** 需要写评价意见的身份：自评不写，上级 / 360 必填（口径 #14） */
    private static final Set<String> COMMENT_RATER_TYPES = Set.of("LEADER", "RATER360");

    /** 评价意见最大长度，与 uf_assess_task_rater.comment NVARCHAR(1000) 一致 */
    private static final int COMMENT_MAX_LEN = 1000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PaSheetItemMapper sheetItemMapper;
    private final PaItemLevelMapper levelMapper;
    private final PaRatingMapper ratingMapper;
    private final PaScoreService scoreService;

    public PaFillServiceImpl(JdbcTemplate jdbc, ObjectMapper objectMapper,
                             PaSheetItemMapper sheetItemMapper, PaItemLevelMapper levelMapper,
                             PaRatingMapper ratingMapper, PaScoreService scoreService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sheetItemMapper = sheetItemMapper;
        this.levelMapper = levelMapper;
        this.ratingMapper = ratingMapper;
        this.scoreService = scoreService;
    }

    // ================================================================ 填写页

    @Override
    public PaFillPageVO page(String token) {
        RaterContext ctx = loadByToken(token);

        if (ctx.taskStatus == TASK_VOIDED || ctx.raterStatus == RATER_VOIDED) {
            return statusOnly("VOIDED", "该测评已被管理员撤回。如需继续参与，请联系人力资源部重新发起测评。");
        }
        if (ctx.taskStatus == TASK_CANCELLED) {
            return statusOnly("CANCELLED", "该测评已被管理员取消。如有疑问，请联系人力资源部。");
        }
        // 年度已关闭：不再接受新的评价。已提交的豁免 ——
        // 关闭年度冻结的是报告，不该连「我当初怎么评的」入口一起砍掉。
        if (ctx.periodStatus == PERIOD_CLOSED && ctx.raterStatus != RATER_SUBMITTED) {
            return statusOnly("PERIOD_CLOSED",
                    "该年度已关闭，不能再提交评价。如需补评，请联系人力资源部重新开启年度。");
        }
        assertCurrentUser(ctx);

        PaFillPageVO page = new PaFillPageVO();
        page.setStatus(ctx.raterStatus == RATER_SUBMITTED ? "SUBMITTED" : "FILLING");
        page.setTaskId(ctx.taskId);
        page.setRaterId(ctx.raterId);
        page.setRaterType(ctx.raterType);
        page.setRaterTypeName(RATER_TYPE_NAMES.getOrDefault(ctx.raterType, ctx.raterType));
        page.setYear(ctx.year);
        page.setDimensionName(ctx.dimensionName);
        page.setTargetName(ctx.targetName);
        page.setDeptName(ctx.deptName);
        page.setPostName(ctx.postName);
        page.setDraftVersion(ctx.draftVersion);
        page.setComment(ctx.comment);
        page.setCommentRequired(COMMENT_RATER_TYPES.contains(ctx.raterType));
        page.setItems(items(ctx.sheetId, ctx.raterType, ctx.raterId));
        page.setWeightTotal(weightTotal(ctx.sheetId));
        page.setDraft(parseDraft(ctx.draftJson));

        int[] progress = progress(ctx.taskId);
        page.setSubmittedCount(progress[0]);
        page.setRaterTotal(progress[1]);
        if (ctx.raterStatus == RATER_SUBMITTED) {
            page.setSubmitTime(ctx.submitTime);
            page.setMessage(ctx.taskStatus == TASK_DONE
                    ? "本测评已由全部评价人提交完毕并完成算分，您的评价已锁定。"
                    : "您已提交，评价已锁定、不可再修改，等待其他评价人完成后系统自动计算得分。");
        }
        return page;
    }

    // ================================================================ 暂存

    @Override
    public PaTaskVO.DraftResult saveDraft(String token, PaFillDTO.DraftRequest req) {
        RaterContext ctx = loadByToken(token);
        assertSubmittable(ctx);
        assertNotSubmitted(ctx);

        if (!Integer.valueOf(ctx.draftVersion).equals(req.getVersion())) {
            PaTaskVO.DraftResult conflict = new PaTaskVO.DraftResult();
            conflict.setConflict(true);
            conflict.setDraftVersion(ctx.draftVersion);
            conflict.setLatestDraft(parseDraft(ctx.draftJson));
            conflict.setRatedCount(conflict.getLatestDraft().size());
            return conflict;
        }

        Map<Long, Long> merged = new LinkedHashMap<>(parseDraft(ctx.draftJson));
        if (req.getRatings() != null) {
            for (PaFillDTO.Rating r : req.getRatings()) {
                merged.put(r.getItemId(), r.getLevelId());
            }
        }
        int nextVersion = ctx.draftVersion + 1;
        // 评语：只有上级/360 写。req 没带 comment（null）时不覆盖已存评语，
        // 否则「只改了评级」的自动暂存会把刚写的评语清空。
        String comment = COMMENT_RATER_TYPES.contains(ctx.raterType) ? normalizeComment(req.getComment()) : null;
        if (comment != null) {
            jdbc.update("UPDATE uf_assess_task_rater SET draft_json = ?, draft_time = SYSDATETIME(), " +
                    "draft_version = ?, comment = ? WHERE id = ?", toJson(merged), nextVersion, comment, ctx.raterId);
        } else {
            jdbc.update("UPDATE uf_assess_task_rater SET draft_json = ?, draft_time = SYSDATETIME(), " +
                    "draft_version = ? WHERE id = ?", toJson(merged), nextVersion, ctx.raterId);
        }

        PaTaskVO.DraftResult result = new PaTaskVO.DraftResult();
        result.setConflict(false);
        result.setDraftVersion(nextVersion);
        result.setRatedCount(merged.size());
        return result;
    }

    // ================================================================ 提交

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaTaskVO.SubmitResult submit(String token, PaFillDTO.SubmitRequest req) {
        RaterContext ctx = loadByToken(token);
        assertSubmittable(ctx);
        assertNotSubmitted(ctx);

        // 1. 评价意见：上级 / 360 必填，自评不写。
        //    评语和评级一样是报告的组成部分，缺了报告「评价意见」就是空栏，
        //    所以要挡在写入前，而不是提交成功后再让 HR 去催。
        String comment = null;
        if (COMMENT_RATER_TYPES.contains(ctx.raterType)) {
            comment = normalizeComment(req.getComment());
            if (comment == null) {
                throw new BusinessException(ERR_COMMENT_REQUIRED, "请填写评价意见后再提交");
            }
        }

        // 2. 能力项集合必须与「该身份看得见的」集合完全一致。
        //    少一个 → 该项拿不到分，报告上留空且难定位；多一个 → 会写进不属于该身份的项。
        List<PaFillItemVO> visible = items(ctx.sheetId, ctx.raterType, ctx.raterId);
        Set<Long> expected = visible.stream().map(PaFillItemVO::getItemId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Long> submitted = new LinkedHashMap<>();
        for (PaFillDTO.Rating r : req.getRatings()) {
            if (submitted.put(r.getItemId(), r.getLevelId()) != null) {
                throw new BusinessException(ERR_RATINGS_INCOMPLETE, "能力项重复提交：" + r.getItemId());
            }
        }
        if (!expected.equals(submitted.keySet())) {
            Set<Long> missing = new HashSet<>(expected);
            missing.removeAll(submitted.keySet());
            Set<Long> extra = new HashSet<>(submitted.keySet());
            extra.removeAll(expected);
            throw new BusinessException(ERR_RATINGS_INCOMPLETE,
                    "评价不完整：还差 " + missing.size() + " 个能力项"
                            + (extra.isEmpty() ? "" : "，多出 " + extra.size() + " 个"));
        }

        // 3. 等级必须属于**该能力项自己**配置的档位。
        //    只校验「是个存在的等级 ID」会放行别项的档位，等于允许按业务上不存在的档位打分。
        Map<Long, Map<Long, PaItemLevel>> allowed = levelsByItem(visible);
        for (Map.Entry<Long, Long> e : submitted.entrySet()) {
            Map<Long, PaItemLevel> ok = allowed.getOrDefault(e.getKey(), Map.of());
            if (e.getValue() == null || !ok.containsKey(e.getValue())) {
                throw new BusinessException(ERR_LEVEL_INVALID,
                        "能力项 " + itemName(visible, e.getKey()) + " 的评级不是该项已配置的档位");
            }
        }

        // 4. 写评级（同一人同一项覆盖式写入：撤回后重发时唯一索引会挡住纯 INSERT）
        for (Map.Entry<Long, Long> e : submitted.entrySet()) {
            PaItemLevel level = allowed.get(e.getKey()).get(e.getValue());
            PaRating existing = ratingMapper.selectOne(new LambdaQueryWrapper<PaRating>()
                    .eq(PaRating::getSheetId, ctx.sheetId)
                    .eq(PaRating::getItemId, e.getKey())
                    .eq(PaRating::getRaterType, ctx.raterType)
                    .eq(PaRating::getRaterResourceId, ctx.raterResourceId));
            PaRating rating = existing == null ? new PaRating() : existing;
            rating.setDimensionId(ctx.dimensionId);
            rating.setSheetId(ctx.sheetId);
            rating.setItemId(e.getKey());
            rating.setRaterType(ctx.raterType);
            rating.setRaterResourceId(ctx.raterResourceId);
            rating.setLevelId(level.getId());
            rating.setLevelName(level.getLevelName());
            rating.setScore(level.getScore());
            if (existing == null) {
                ratingMapper.insert(rating);
            } else {
                ratingMapper.updateById(rating);
            }
        }

        jdbc.update("UPDATE uf_assess_task_rater SET status = ?, submit_time = SYSDATETIME(), " +
                "draft_json = ?, draft_time = SYSDATETIME(), draft_version = draft_version + 1, comment = ? " +
                "WHERE id = ?", RATER_SUBMITTED, toJson(submitted), comment, ctx.raterId);

        // 5. 全员齐备才出分（口径 #5）。自评不在这个判定里（口径 #13）。
        int pending = pendingCount(ctx.taskId);
        int total = progress(ctx.taskId)[1];
        PaTaskVO.SubmitResult result = new PaTaskVO.SubmitResult();
        result.setSubmitted(true);
        // 进度条按「含自评」的人数展示（HR 要知道自评到底交了几个），
        // 但「算不算齐」只按 pending（不含自评）判定。
        PaTaskVO.Progress p = new PaTaskVO.Progress();
        p.setTotal(total);
        p.setSubmitted(progress(ctx.taskId)[0]);
        result.setTaskProgress(p);

        if (pending > 0) {
            scoreService.markPartial(ctx.sheetId);
            result.setCalculated(false);
            result.setMessage("您已提交，还有 " + pending + " 位评价人未提交，全部提交后系统将自动计算得分");
        } else {
            scoreService.finish(ctx.taskId);
            result.setCalculated(true);
            result.setMessage("您已提交，全部评价人已完成，系统已计算得分");
        }
        log.info("评价提交 taskId={}, raterId={}, type={}, 已算分={}",
                ctx.taskId, ctx.raterId, ctx.raterType, result.getCalculated());
        return result;
    }

    // ================================================================ 私有

    /** token → 评价人 + 任务 + 页面上下文 */
    private RaterContext loadByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ERR_TOKEN_INVALID, "链接无效，请从消息或「我的评价」重新进入");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.id AS rater_id, tr.task_id, tr.rater_type, tr.rater_resource_id, tr.status AS rater_status," +
                "       tr.draft_json, tr.draft_version, tr.comment, tr.submit_time," +
                "       t.status AS task_status, t.sheet_id, t.resource_id, t.dimension_id," +
                "       p.status AS period_status, p.year, d.dimension_name, s.post_name," +
                "       h.lastname AS target_name, dept.departmentname AS dept_name" +
                " FROM uf_assess_task_rater tr" +
                " JOIN uf_assess_task t ON t.id = tr.task_id" +
                " JOIN uf_assess_period p ON p.id = t.period_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = t.dimension_id" +
                " LEFT JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                " LEFT JOIN HrmResource h ON h.id = t.resource_id" +
                " LEFT JOIN HrmDepartment dept ON dept.id = h.departmentid" +
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
        ctx.submitTime = m.get("submit_time") == null
                ? null : ((java.sql.Timestamp) m.get("submit_time")).toLocalDateTime();
        ctx.taskStatus = (int) num(m.get("task_status"));
        ctx.periodStatus = (int) num(m.get("period_status"));
        ctx.sheetId = num(m.get("sheet_id"));
        ctx.targetResourceId = num(m.get("resource_id"));
        ctx.dimensionId = num(m.get("dimension_id"));
        ctx.year = (int) num(m.get("year"));
        ctx.dimensionName = (String) m.get("dimension_name");
        ctx.postName = (String) m.get("post_name");
        ctx.targetName = (String) m.get("target_name");
        ctx.deptName = (String) m.get("dept_name");
        return ctx;
    }

    /** 只返回状态页所需的两个字段，其余一律为空（撤回后不泄漏任何内容） */
    private PaFillPageVO statusOnly(String status, String message) {
        PaFillPageVO page = new PaFillPageVO();
        page.setStatus(status);
        page.setMessage(message);
        return page;
    }

    /**
     * 该身份看得见的能力项（按类别 + 排序号）。
     *
     * <p>过滤口径来自<b>页面行快照</b>（{@code uf_pa_sheet_item.rater_leader / rater_360}），
     * 不读岗位配置 —— 岗位改了评级主体，本页已经发出去的链接不该随之改变，
     * 否则「昨天能评的项今天不见了」，评价人只会以为系统坏了（口径 #15）。</p>
     *
     * <p>360 多一层<b>按项指派</b>（2026-11-23，sql/60）：发起时逐项选人，
     * 一个人可能只被指派到其中几项。指派行存在就按它过滤；
     * <b>一行都没有时回退到快照</b> —— 那是本次改造之前发出的任务，
     * 当时就是「这批人评全部 360 项」，回填会篡改历史口径。</p>
     */
    private List<PaFillItemVO> items(long sheetId, String raterType, Long raterId) {
        List<PaSheetItem> rows = sheetItemMapper.selectList(new LambdaQueryWrapper<PaSheetItem>()
                .eq(PaSheetItem::getSheetId, sheetId)
                .orderByAsc(PaSheetItem::getSortNo).orderByAsc(PaSheetItem::getId));
        Set<Long> assigned = "RATER360".equals(raterType) ? assignedItems(raterId) : Set.of();
        List<PaSheetItem> mine = new ArrayList<>();
        for (PaSheetItem row : rows) {
            boolean visible = switch (raterType == null ? "" : raterType) {
                case "LEADER" -> isOn(row.getRaterLeader());
                case "RATER360" -> assigned.isEmpty()
                        ? isOn(row.getRater360())          // 存量任务：快照口径
                        : assigned.contains(row.getItemId());   // 按项指派
                // 自评评所有能力项（口径 #13）：自评是「你怎么看自己」，不是「按主体分配」
                default -> true;
            };
            if (visible) {
                mine.add(row);
            }
        }
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PaItemLevel>> levels = levelsOf(mine.stream().map(PaSheetItem::getItemId).toList());

        List<PaFillItemVO> out = new ArrayList<>(mine.size());
        for (PaSheetItem row : mine) {
            PaFillItemVO vo = new PaFillItemVO();
            vo.setItemId(row.getItemId());
            vo.setCategoryId(row.getCategoryId());
            vo.setCategoryName(row.getCategoryName());
            vo.setItemCode(row.getItemCode());
            vo.setItemName(row.getItemName());
            vo.setDefinition(row.getDefinition());
            vo.setSortNo(row.getSortNo());
            vo.setWeight(row.getWeight());
            // 等级不落快照：等级是「打分用的尺子」，改分值后新评级要按新刻度走；
            // 已有评级里的 level_name / score 才是快照（见 PaRating）。
            vo.setLevels(levels.getOrDefault(row.getItemId(), List.of()).stream().map(l -> {
                PaItemLevelVO lv = new PaItemLevelVO();
                lv.setId(l.getId());
                lv.setLevelName(l.getLevelName());
                lv.setScore(l.getScore());
                lv.setLevelDesc(l.getLevelDesc());
                lv.setSort(l.getSort());
                return lv;
            }).collect(Collectors.toList()));
            out.add(vo);
        }
        return out;
    }

    private Map<Long, List<PaItemLevel>> levelsOf(List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                        .in(PaItemLevel::getItemId, itemIds)
                        .orderByAsc(PaItemLevel::getSort).orderByAsc(PaItemLevel::getId))
                .stream().collect(Collectors.groupingBy(PaItemLevel::getItemId));
    }

    /** itemId → (levelId → level)：提交校验用，避免逐项查库 */
    private Map<Long, Map<Long, PaItemLevel>> levelsByItem(List<PaFillItemVO> items) {
        Map<Long, Map<Long, PaItemLevel>> out = new LinkedHashMap<>();
        for (PaFillItemVO item : items) {
            Map<Long, PaItemLevel> byId = new LinkedHashMap<>();
            for (PaItemLevelVO lv : item.getLevels()) {
                PaItemLevel level = new PaItemLevel();
                level.setId(lv.getId());
                level.setLevelName(lv.getLevelName());
                level.setScore(lv.getScore());
                byId.put(lv.getId(), level);
            }
            out.put(item.getItemId(), byId);
        }
        return out;
    }

    /**
     * 权重合计（百分数）：页面顶部徽标，≠100% 时前端给黄色提醒（口径 #2）。
     *
     * <p><b>按整份页面的全部能力项算</b>，不按「本人看得见的那几项」算（2026-11-23 改）。
     * 这个徽标说的是「岗位模型配得对不对」—— 按项指派后，一个 360 可能只分到 1 项（6%），
     * 按可见项求和会让每个 360 的页面都弹「权重合计不是 100%」，纯属误报。
     * 同理，上级只评其中几项时也不该报警。</p>
     */
    private BigDecimal weightTotal(long sheetId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PaSheetItem row : sheetItemMapper.selectList(new LambdaQueryWrapper<PaSheetItem>()
                .eq(PaSheetItem::getSheetId, sheetId))) {
            if (row.getWeight() != null) {
                sum = sum.add(row.getWeight());
            }
        }
        return sum;
    }

    private static boolean isOn(Integer flag) {
        return flag != null && flag == 1;
    }

    /**
     * 这个 360 被指派到的能力项 ID（sql/60）。
     *
     * <p>返回空集合有两种含义，调用方必须自己区分，这里只负责给数据：</p>
     * <ul>
     *   <li><b>老任务</b>：压根没有指派行 —— 调用方回退到「快照 rater_360=1 的全部项」；</li>
     *   <li><b>新任务但一项都没分到</b>：发起时那一项没填人（前端会拦，后端不硬拦）。
     *       这种情况<b>也不该让人看到全部项</b>，但也不能让他无从下手 ——
     *       与老任务表现一致是最小惊讶的选择，且这类任务本来就不该被发出来。</li>
     * </ul>
     * <p>两种含义合并成「空就是回退」是刻意的：分开处理需要再查一次任务创建时间，
     * 而结论对用户是同一个（看见哪些项），多一次查询只换来一条更精确的日志。</p>
     */
    private Set<Long> assignedItems(Long raterId) {
        if (raterId == null) {
            return Set.of();
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT item_id FROM uf_assess_task_rater_item WHERE rater_id = ?", Long.class, raterId);
        return ids.isEmpty() ? Set.of() : new LinkedHashSet<>(ids);
    }

    private static String itemName(List<PaFillItemVO> items, Long itemId) {
        return items.stream().filter(i -> i.getItemId().equals(itemId))
                .map(PaFillItemVO::getItemName).findFirst().orElse("ID " + itemId);
    }

    /**
     * 评语归一化。只在「上级 / 360」身份调用；自评的评语一律丢弃。
     * 空白视为未填 —— 返回 null 让调用方决定是「不覆盖」还是「拒绝提交」。
     * 超长直接拒绝而不是截断：截断会静默丢内容，用户以为写全了。
     */
    private String normalizeComment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > COMMENT_MAX_LEN) {
            throw new BusinessException(ERR_COMMENT_TOO_LONG, "评价意见不能超过 " + COMMENT_MAX_LEN + " 字");
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

    /** 写操作（暂存/提交）的公共前置：身份 + 任务状态 + 年度状态 */
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
        // 年度已关闭：暂存与提交一律拒绝。放在任务状态之后，
        // 让「已撤回/已取消/已完成」优先报出更具体的原因。
        if (ctx.periodStatus == PERIOD_CLOSED) {
            throw new BusinessException(ERR_PERIOD_CLOSED,
                    "该年度已关闭，不能再提交评价。如需补评，请联系人力资源部重新开启年度");
        }
        assertCurrentUser(ctx);
    }

    /**
     * 提交是单向的：本人已提交后，暂存与提交都拒绝。
     *
     * <p>与 {@link #assertSubmittable} 的分工：那个管「任务/评价人是否还活着」，
     * 这个管「本人是否已经交过卷」。任务完成（status=2）时本人必然已提交，
     * 但两者错误码不同 —— 任务完成是整单结束，这里是本卷已锁。</p>
     */
    private void assertNotSubmitted(RaterContext ctx) {
        if (ctx.raterStatus == RATER_SUBMITTED) {
            throw new BusinessException(ERR_RESUBMIT_FORBIDDEN,
                    "该评价已提交，不能重复提交或修改。如需更正，请联系人力资源部撤回后重新发起");
        }
    }

    /**
     * 还差几人交卷。<b>不含自评</b>（口径 #13）：自评不参与计算，
     * 一个没人点过的自评链接不该卡住整份报告 —— 否则「全员都交了但永远没分」，
     * 而外观上看不出原因（进度条 3/3，分数空着）。
     */
    private int pendingCount(long taskId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater WHERE task_id = ? AND status = ?" +
                " AND rater_type <> 'SELF'",
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

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BusinessException("评级序列化失败");
        }
    }

    /** 草稿 {"59":12,"60":15} → {59:12, 60:15}；损坏的草稿按空处理，不阻断填写 */
    private Map<Long, Long> parseDraft(String draftJson) {
        Map<Long, Long> result = new LinkedHashMap<>();
        if (draftJson == null || draftJson.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = objectMapper.readValue(draftJson, Map.class);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Number n) {
                    result.put(Long.valueOf(e.getKey().toString()), n.longValue());
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
        /** 已写的评价意见（上级 / 360 才有） */
        String comment;
        /** 提交时间；未提交为 null */
        java.time.LocalDateTime submitTime;
        int taskStatus;
        /** 该任务所属年度的状态：0 进行中 1 已关闭 */
        int periodStatus;
        long sheetId;
        long targetResourceId;
        long dimensionId;
        int year;
        String dimensionName;
        String postName;
        String targetName;
        String deptName;
    }
}
