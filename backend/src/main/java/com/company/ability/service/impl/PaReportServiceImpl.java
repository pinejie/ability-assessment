package com.company.ability.service.impl;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.PaBand;
import com.company.ability.exception.BusinessException;
import com.company.ability.security.LoginUser;
import com.company.ability.security.UserContext;
import com.company.ability.service.AdminService;
import com.company.ability.service.AssessDimensionService;
import com.company.ability.service.PaReportService;
import com.company.ability.vo.PaReportVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.company.ability.utils.PaBandScale;
import com.company.ability.utils.PaItemLevelScale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 个人测评报告实现（方案 17 §7.2）。
 *
 * <p>三条口径写死在这里，改之前先读：</p>
 * <ol>
 *   <li><b>只读，不算分</b>：分数是算分时落库的结果（{@code uf_pa_sheet} /
 *       {@code uf_pa_sheet_item}），报告里再算一遍必然与页面上的分数打架。</li>
 *   <li><b>360 人数取任务里的实际名单</b>，不用「评了几项」：发起时选了 3 个 360，
 *       其中 1 人没交，报告上仍应显示 3 人 —— 否则 HR 会以为名单被改过。
 *       取最近一次任务（一个页面可能被撤回后重发多次）。</li>
 *   <li><b>自评列只展示</b>（口径 #13）：{@code self_score} 已经落库，直接读；
 *       总分与项得分里绝不含它。</li>
 *   <li><b>360 姓名按身份收口</b>（2026-11-23）：只有本维度管理员与系统管理员拿到姓名，
 *       其余人 {@code raterName} 为 null。判断在服务层做，前端拿不到就是拿不到 ——
 *       用 CSS 藏等于没做，按 F12 就能读到。</li>
 *   <li><b>评语逐人实时读</b>（2026-11-23 修正口径 #14）：不再用 {@code comment_text}
 *       合并快照，谁交了显示谁；上级与 360 统一编号「测评人N」，上级为 1。</li>
 * </ol>
 */
@Slf4j
@Service
public class PaReportServiceImpl implements PaReportService {

    static final int ERR_REPORT_NOT_FOUND = 40701;

    /** 自助视角读到了未封存的报告（sql/64）：不是「不存在」，要让人知道是「等 HR 封存」 */
    static final int ERR_NOT_SEALED = 40702;

    /** 封存 / 解除封存的状态前置不满足（没出分、重复封存、未封存就解除） */
    static final int ERR_SEAL_STATE = 40703;

    /** 没有封存权限：与其它权限拒绝同一口径用 403，前端按「温和提示」处理 */
    static final int ERR_SEAL_FORBIDDEN = 403;

    /** uf_pa_sheet.seal_status */
    private static final int SEALED = 1;

    /** uf_assess_task_rater.status：0=待评 / 1=已提交 / 3=已作废（sql/38 只有这三个值） */
    private static final int RATER_PENDING = 0;

    /** 本人接口未登录（与其它服务同一口径：401） */
    static final int ERR_NOT_LOGGED_IN = 401;

    private static final Map<Integer, String> SHEET_STATUS_NAMES = Map.of(
            0, "待评价", 1, "评价中", 2, "已完成");

    private final JdbcTemplate jdbc;
    private final AdminService adminService;
    private final AssessDimensionService dimensionService;
    private final ObjectMapper objectMapper;

    public PaReportServiceImpl(JdbcTemplate jdbc, AdminService adminService,
                              AssessDimensionService dimensionService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.adminService = adminService;
        this.dimensionService = dimensionService;
        this.objectMapper = objectMapper;
    }

    // ================================================================ 列表

    @Override
    public PageResult<PaReportVO.ListItem> page(Integer year, Long resourceId, Long departmentId,
                                                Integer status, int pageNum, int pageSize) {
        long dimensionId = DimensionContext.getOrDefault();
        int page = Math.max(1, pageNum);
        int size = Math.max(1, pageSize);

        StringBuilder where = new StringBuilder(" WHERE s.dimension_id = ? AND p.year = ?");
        List<Object> args = new ArrayList<>();
        args.add(dimensionId);
        args.add(year);
        if (resourceId != null) {
            where.append(" AND s.resource_id = ?");
            args.add(resourceId);
        }
        if (departmentId != null) {
            where.append(" AND s.department_id = ?");
            args.add(departmentId);
        }
        if (status != null) {
            where.append(" AND s.status = ?");
            args.add(status);
        }

        String from = " FROM uf_pa_sheet s" +
                " JOIN uf_assess_period p ON p.id = s.period_id" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id";

        Integer total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((page - 1) * size);
        pageArgs.add(size);

        // 360 人数取该页最近一次任务的实际名单长度；页面可能没有任务（装配后被撤回删光），
        // 那时显示 0 —— 「没有任务」与「任务里一个 360 都没选」对报告是一回事。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS sheet_id, p.year, s.resource_id, h.lastname AS resource_name," +
                "       s.department_name, s.post_name, s.status, s.total_score, s.band_name," +
                "       s.seal_status, s.seal_time, s.seal_by_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater tr" +
                "          WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = s.id)" +
                "            AND tr.rater_type = 'RATER360') AS rater360_count" +
                from + where + " ORDER BY s.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                pageArgs.toArray());

        List<PaReportVO.ListItem> list = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            PaReportVO.ListItem item = new PaReportVO.ListItem();
            item.setSheetId(num(r.get("sheet_id")));
            item.setYear(intOf(r.get("year")));
            item.setResourceId(num(r.get("resource_id")));
            item.setResourceName(str(r.get("resource_name")));
            item.setDeptName(str(r.get("department_name")));
            item.setPostName(str(r.get("post_name")));
            item.setStatus(intOf(r.get("status")));
            item.setStatusName(SHEET_STATUS_NAMES.getOrDefault(item.getStatus(), "未知"));
            item.setRater360Count(intOf(r.get("rater360_count")));
            item.setTotalScore(dec(r.get("total_score")));
            item.setBandName(str(r.get("band_name")));
            item.setSealStatus(intOf(r.get("seal_status")));
            item.setSealTime(str(r.get("seal_time")));
            item.setSealByName(str(r.get("seal_by_name")));
            list.add(item);
        }

        log.debug("个人测评报告列表 year={}, dimension={}, 命中 {} 条，本页 {}", year, dimensionId,
                total, list.size());
        return new PageResult<>(list, total == null ? 0L : total.longValue(), page, size);
    }

    // ================================================================ 详情

    /**
     * 管理端报告详情。
     *
     * <p>封存的两条作用在这里分开（sql/64）：</p>
     * <ul>
     *   <li><b>可见性</b>：封存只卡自助视角（本人 / 下属），管理端任何时候都看得到 ——
     *       HR 要看的正是「还没封存的那一份」。</li>
     *   <li><b>内容冻结</b>：已封存的页面读封存那一刻的快照，与本人看到的是同一份。
     *       管理端如果继续读实时数据，「已封存」的那份就没人核对过了 ——
     *       封存的意义是「我确认的就是这一份」。</li>
     * </ul>
     */
    @Override
    public PaReportVO.Detail detail(Long sheetId) {
        long dimensionId = DimensionContext.getOrDefault();
        boolean canSeeName = canSeeRaterName(dimensionId);
        PaReportVO.Detail d = readDetail(sheetId, dimensionId, canSeeName);
        // 按钮可见性：能看见姓名的人就是能封存的人（同一口径，见 canSeeRaterName）
        d.setCanSeal(canSeeName);
        // 按钮的可点条件（2026-11-24）：前端据此把「确认封存」置灰并说明还差几个人。
        // 真正的拦截在 seal() 里，这里只是把「点了会失败」提前变成「点不了」。
        int pending = pendingRaterCount(sheetId);
        d.setPendingRaterCount(pending);
        d.setSealReady(pending == 0 && d.getStatus() != null && d.getStatus() == 2);
        return d;
    }

    // ==================== 下属的报告（2026-11-24） ====================

    /**
     * 直接下属 id 列表。
     *
     * <p>口径是 {@code HrmResource.managerid}（口径 #11，与「直接上级」同一字段）。
     * 只取一层：managerid 指过来的人才算，孙辈不递归。</p>
     *
     * <p><b>不过滤在职状态</b>：离职下属在测评期内可能已有页面，
     * 上级仍该能回看；把离职的人藏掉会让「去年的报告怎么少了一个人」无从解释。
     * 页面上的人名照旧来自 uf_pa_sheet 的快照。</p>
     */
    private List<Long> subordinateIds(long leaderResourceId) {
        return jdbc.queryForList(
                "SELECT id FROM HrmResource WHERE managerid = ? ORDER BY id",
                Long.class, leaderResourceId);
    }

    @Override
    public boolean hasSubordinates() {
        LoginUser user = requireLogin();
        return !subordinateIds(user.resourceId()).isEmpty();
    }

    /**
     * 直接下属的报告列表。
     *
     * <p>SQL 与 {@link #myReports} 同构，只把「{@code s.resource_id = 我}」
     * 换成「{@code s.resource_id in 我的直接下属}」。<b>刻意不复用同一个私有方法</b>：
     * 两边的越权风险不对称 —— 本人那条最坏也就是看自己，这条一旦漏判就是看别人，
     * 所以它单独成段、单独测。共用的只有拼 VO 的那几行（见 {@code toListItem}）。</p>
     *
     * <p>不带维度过滤，与 myReports 一致：下属可能在多个维度下各有页面，
     * 列表里用 dimensionName 区分。年度是必填语义（为空时退到进行中年度）。</p>
     */
    @Override
    public List<PaReportVO.ListItem> subordinateReports(Integer year) {
        LoginUser user = requireLogin();
        List<Long> ids = subordinateIds(user.resourceId());
        if (ids.isEmpty()) {
            return List.of();
        }
        Integer effectiveYear = year != null ? year : activeYear(user.resourceId());
        if (effectiveYear == null) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(effectiveYear);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS sheet_id, p.year, s.resource_id, h.lastname AS resource_name," +
                "       s.department_name, s.post_name, s.status, s.total_score, s.band_name," +
                "       d.dimension_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater tr" +
                "          WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = s.id)" +
                "            AND tr.rater_type = 'RATER360') AS rater360_count" +
                " FROM uf_pa_sheet s" +
                " JOIN uf_assess_period p ON p.id = s.period_id" +
                " LEFT JOIN uf_assess_dimension d ON d.id = s.dimension_id" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                // 封存闸门（sql/64）：自助视角只看已封存的。未封存的对上级来说等于还不存在
                " WHERE s.resource_id IN (" + in + ") AND p.year = ? AND s.seal_status = " + SEALED +
                " ORDER BY s.resource_id, d.dimension_name, s.id DESC",
                args.toArray());
        List<PaReportVO.ListItem> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(toListItem(r));
        }
        return out;
    }

    /**
     * 下属某张页面的报告。
     *
     * <p>归属校验：这张页面的被测评人必须是<b>我的直接下属</b>。查一次
     * {@code managerid} 而不是拿 subordinateIds 做集合判断 —— 后者要多一次全量查询，
     * 而这里只需要回答一个人。</p>
     */
    @Override
    public PaReportVO.Detail subordinateDetail(Long sheetId) {
        LoginUser user = requireLogin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.resource_id, s.dimension_id, h.managerid" +
                " FROM uf_pa_sheet s LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                " WHERE s.id = ?", sheetId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_REPORT_NOT_FOUND, "测评报告不存在：" + sheetId);
        }
        Map<String, Object> row = rows.get(0);
        Long managerId = row.get("managerid") == null ? null : num(row.get("managerid"));
        if (managerId == null || !managerId.equals(user.resourceId())) {
            // 与「不存在」同一个错误码与文案：不能让人靠错误信息试出「这个人存在但不是我的下属」
            throw new BusinessException(ERR_REPORT_NOT_FOUND, "测评报告不存在：" + sheetId);
        }
        long sheetDimension = num(row.get("dimension_id"));
        // 封存闸门（sql/64）：上级与本人一样，只看得到已封存的报告
        requireSealed(sheetId);
        // 姓名恒不返回：下属的 360 名单不是上级该从这里知道的事
        return readDetail(sheetId, sheetDimension, false);
    }

    // ==================== 封存 / 解除封存（2026-11-24，sql/64） ====================

    /**
     * 该页「最近一次任务」里还没提交的评价人数量（封存门槛，2026-11-24）。
     *
     * <p><b>含自评</b>（用户确认的口径 B）：虽然是「自评不参与算分」（口径 #13），
     * 但封存是定稿动作，交卷状态本身是内容完整性的一部分 —— 一份自己都没评的报告
     * 封出去，读者不会知道「自评那列空着是因为他还没交」。</p>
     *
     * <p>只数 {@code status = 0}（待评）：已作废(3) 不该再算进「还差几个人」——
     * 撤回过的任务会把人置成 3，把作废的人也数上，这份报告就永远封不了了。
     * 页面从没发起过任务时返回 0，那种页面本来也过不了 {@code status = 2} 那道闸。</p>
     */
    private int pendingRaterCount(Long sheetId) {
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_task_rater tr" +
                " WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = ?)" +
                "   AND tr.status = ?",
                Integer.class, sheetId, RATER_PENDING);
        return pending == null ? 0 : pending;
    }

    /**
     * 未封存就拒绝（自助视角的唯一闸门）。
     *
     * <p>错误码与「不存在」分开（{@code 40702} vs {@code 40701}）：归属校验已经过了，
     * 这时候再含糊成「不存在」只会让人反复重进页面找 bug。文案指向下一步动作
     * （联系 HR 确认封存），而不是描述系统状态。</p>
     */
    private void requireSealed(Long sheetId) {
        Integer status = jdbc.queryForObject(
                "SELECT seal_status FROM uf_pa_sheet WHERE id = ?", Integer.class, sheetId);
        if (status == null || status != SEALED) {
            throw new BusinessException(ERR_NOT_SEALED, "该测评报告尚未封存，请联系人力资源部确认后查看");
        }
    }

    /**
     * 确认封存。
     *
     * <p>三件事必须一起做（缺一条封存就是假的）：</p>
     * <ol>
     *   <li>校验权限：系统管理员或该维度的维度管理员，与「能看测评人姓名」同一口径；</li>
     *   <li>校验状态：页面必须已算出分（{@code status = 2}），封一份没分数的报告没有意义；</li>
     *   <li>落快照：把<b>管理员视角</b>的整份报告存进 {@code sealed_json}，
     *       之后所有人（含管理端）读到的都是这一份。</li>
     * </ol>
     *
     * <p>为什么快照存管理员视角（带姓名）而不存脱敏版：脱敏只需要在读取时抹掉姓名
     * （{@link #maskRaterNames}），反过来「先把姓名抠掉再想恢复」做不到。
     * 一份快照服务所有视角，才不会出现「本人和管理员看到的分数不一样」。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaReportVO.Detail seal(Long sheetId) {
        LoginUser user = requireLogin();
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT s.dimension_id, s.status, s.seal_status FROM uf_pa_sheet s WHERE s.id = ?", sheetId);
        long dimensionId = num(head.get("dimension_id"));
        requireSealPermission(dimensionId);

        if (intOf(head.get("seal_status")) == SEALED) {
            throw new BusinessException(ERR_SEAL_STATE, "该报告已封存，无需重复操作");
        }
        // 全员齐备是封存的硬门槛（2026-11-24 需求变更）。
        //
        // 为什么不能只靠 status = 2：页面状态只表示「出分了」，而口径 #13 里自评不阻塞算分，
        // 所以「已出分」时本人有可能还没交自评，360 里也可能有人没交（部分项没评也能出分）。
        // 封存是「这份内容对外定稿」，缺人的报告封出去，缺的那一格就再也补不回来了 ——
        // 「还没评完」和「已定稿」在报告上是长得一模一样的。
        //
        // 顺序：先查「还差几个人」再查「有没有出分」。两个条件都不满足时（比如评价中、
        // 差 1 人），用户要的是「还差谁」这个可执行的信息，而不是「还没出分」这种只能干等的话。
        int pending = pendingRaterCount(sheetId);
        if (pending > 0) {
            throw new BusinessException(ERR_SEAL_STATE,
                    "还有 " + pending + " 位评价人未提交，全部评价完成后才能封存");
        }
        if (intOf(head.get("status")) != 2) {
            throw new BusinessException(ERR_SEAL_STATE,
                    "该报告还没有算出分数，不能封存。请先让评价人完成评价");
        }

        // 快照必须按「带姓名」的视角拼：读者是谁在读取时再决定
        PaReportVO.Detail content = buildDetail(sheetId, dimensionId, true);
        content.setSealStatus(SEALED);
        content.setSealTime(LocalDateTime.now().toString());
        content.setSealByName(user.name());
        content.setCanSeal(true);
        // 快照里也把「可以封存」写实：封存的页面本来就该是全员齐备的，
        // 留成 null 会让后来读快照的人以为状态未知
        content.setPendingRaterCount(0);
        content.setSealReady(true);
        String json;
        try {
            json = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ERR_SEAL_STATE, "封存失败：报告内容无法序列化，" + e.getMessage());
        }

        // 封存人用 sessionId 而不是 loginid：这是操作留痕，按工号登录的人没有登录名，
        // 留空等于丢失「谁封存的」这条信息。该列不参与权限判定，写工号不会造成越权。
        jdbc.update("UPDATE uf_pa_sheet SET seal_status = ?, seal_time = SYSDATETIME()," +
                        " seal_by_loginid = ?, seal_by_name = ?, sealed_json = ?, update_time = SYSDATETIME()" +
                        " WHERE id = ?",
                SEALED, user.sessionId(), user.name(), json, sheetId);
        log.info("报告封存 sheetId={}, 操作人={}, 总得分={}", sheetId, user.sessionId(), content.getTotalScore());
        return content;
    }

    /**
     * 解除封存。
     *
     * <p>快照一并清掉：留着的话，下次封存会先看到上一次的旧快照在起作用与不在起作用之间
     * 无法分辨（防的是「以为解除了其实没清」这类静默失败）。
     * 解除之后自助视角立刻看不到报告 —— 页面上的二次确认里已经写明了。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unseal(Long sheetId) {
        LoginUser user = requireLogin();
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT s.dimension_id, s.seal_status FROM uf_pa_sheet s WHERE s.id = ?", sheetId);
        long dimensionId = num(head.get("dimension_id"));
        requireSealPermission(dimensionId);

        if (intOf(head.get("seal_status")) != SEALED) {
            throw new BusinessException(ERR_SEAL_STATE, "该报告尚未封存，无需解除");
        }
        jdbc.update("UPDATE uf_pa_sheet SET seal_status = 0, seal_time = NULL, seal_by_loginid = NULL," +
                        " seal_by_name = NULL, sealed_json = NULL, update_time = SYSDATETIME()" +
                        " WHERE id = ?",
                sheetId);
        log.info("报告解除封存 sheetId={}, 操作人={}", sheetId, user.sessionId());
    }

    /** 封存权限：系统管理员，或本维度的维度管理员（与「能看测评人姓名」同一口径） */
    private void requireSealPermission(long dimensionId) {
        if (!canSeeRaterName(dimensionId)) {
            throw new BusinessException(ERR_SEAL_FORBIDDEN, "只有系统管理员或本维度管理员可以封存测评报告");
        }
    }

    /**
     * 报告详情主体。
     *
     * @param dimensionId 期望的维度：页面不属于该维度就当作不存在（跨维度不可读）
     * @param showRaterName 是否填真实姓名
     */
    /**
     * 读详情：已封存的页面返回封存快照，未封存的实时拼。
     *
     * <p>为什么要分成「读快照」和「实时拼」两层：封存后的报告必须与封存那一刻完全一致，
     * 实时拼出来的会带上后来重算 / 重新导入的结果（见 sql/64 的口径说明）。
     * 快照里已经存了姓名可见性的结果，读的时候按当前调用人重新决定要不要抹掉姓名 ——
     * 快照是在「能看见姓名」的管理员视角下存的，直接给本人就成了泄露。</p>
     */
    private PaReportVO.Detail readDetail(Long sheetId, long dimensionId, boolean showRaterName) {
        Map<String, Object> seal = sealOf(sheetId, dimensionId);
        if (intOf(seal.get("seal_status")) != SEALED) {
            return buildDetail(sheetId, dimensionId, showRaterName);
        }
        PaReportVO.Detail snapshot = snapshotOf(sheetId, seal);
        if (snapshot == null) {
            // 快照坏了 / 缺了：退回实时数据，总比让 HR 面对一份空报告强
            return buildDetail(sheetId, dimensionId, showRaterName);
        }
        backfillItemBand(snapshot, sheetId);
        if (!showRaterName) {
            maskRaterNames(snapshot);
        }
        return snapshot;
    }

    /**
     * 给「封存时还没有 {@code itemBandName} 这一列」的旧快照补上档位名（2026-11-24）。
     *
     * <p>只补展示用的描述，<b>冻结的分数一个字节都不动</b>：{@code itemScore} 仍是快照里那一刻的值，
     * 拿它按当前区间表重新切一刀，得到的就是「这份分数在档位表上叫什么」。
     * 这与 {@link #itemLevelName} 对老数据的兜底是同一个思路。</p>
     *
     * <p>为什么不改快照本身：封存快照是审计凭据，读取时改写它等于让「封存那一刻的内容」
     * 变成一句无法验证的话。新封存的报告在 {@code buildDetail} 里就已经带上这个字段，
     * 这里只对存量那一份（sheet 6，2026-11-24 前封的）生效。</p>
     */
    private void backfillItemBand(PaReportVO.Detail d, Long sheetId) {
        if (d.getRows() == null || d.getRows().isEmpty()) {
            return;
        }
        boolean missing = d.getRows().stream()
                .anyMatch(r -> r.getItemScore() != null && r.getItemBandName() == null);
        if (!missing) {
            return;
        }
        Long postId = jdbc.queryForObject(
                "SELECT post_id FROM uf_pa_sheet WHERE id = ?", Long.class, sheetId);
        List<PaBand> bands = bands(postId == null ? 0L : postId);
        if (bands.isEmpty()) {
            return;
        }
        for (PaReportVO.Row r : d.getRows()) {
            if (r.getItemBandName() == null) {
                r.setItemBandName(PaBandScale.resolve(bands, r.getItemScore()));
            }
        }
    }

    /**
     * 封存信息（不含快照本体）：状态、时间、操作人。
     *
     * <p>维度不在入参里就当作不存在 —— 与 {@link #buildDetail} 同口径，
     * 否则会先答「这张页面有/没有封存」再答「不存在」，等于泄露 sheetId 的有效性。</p>
     */
    private Map<String, Object> sealOf(Long sheetId, long dimensionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.seal_status, s.seal_time, s.seal_by_name, s.sealed_json" +
                " FROM uf_pa_sheet s WHERE s.id = ? AND s.dimension_id = ?",
                sheetId, dimensionId);
        if (rows.isEmpty()) {
            throw new BusinessException(ERR_REPORT_NOT_FOUND, "测评报告不存在：" + sheetId);
        }
        return rows.get(0);
    }

    /** 反序列化封存快照；快照坏掉时报「报告不存在」而不是 500 —— 不让脏数据变成白屏 */
    private PaReportVO.Detail snapshotOf(Long sheetId, Map<String, Object> seal) {
        String json = str(seal.get("sealed_json"));
        if (json == null || json.isBlank()) {
            log.warn("页面标记为已封存但没有快照 sheetId={}，回退实时数据", sheetId);
            return null;
        }
        try {
            PaReportVO.Detail d = objectMapper.readValue(json, PaReportVO.Detail.class);
            applySealInfo(d, seal);
            return d;
        } catch (JsonProcessingException e) {
            log.warn("封存快照解析失败 sheetId={}: {}", sheetId, e.getMessage());
            return null;
        }
    }

    /** 把封存状态三件套写到 VO 上（快照与实时两条路共用，避免一处漏填） */
    private void applySealInfo(PaReportVO.Detail d, Map<String, Object> seal) {
        d.setSealStatus(intOf(seal.get("seal_status")));
        d.setSealTime(str(seal.get("seal_time")));
        d.setSealByName(str(seal.get("seal_by_name")));
    }

    /**
     * 抹掉快照里的测评人姓名。
     *
     * <p>快照是在管理员视角下存的（带姓名）。本人 / 下属读同一份快照时必须把姓名清掉，
     * 顺带把 {@code showRaterName} 置 false —— 前端据此决定列头写不写姓名。</p>
     */
    private static void maskRaterNames(PaReportVO.Detail d) {
        d.setShowRaterName(false);
        if (d.getComments() != null) {
            for (PaReportVO.CommentEntry c : d.getComments()) {
                c.setRaterName(null);
            }
        }
        if (d.getRows() != null) {
            for (PaReportVO.Row r : d.getRows()) {
                if (r.getRater360s() == null) {
                    continue;
                }
                for (PaReportVO.RaterEntry e : r.getRater360s()) {
                    e.setRaterName(null);
                }
            }
        }
    }

    private PaReportVO.Detail buildDetail(Long sheetId, long dimensionId, boolean showRaterName) {
        List<Map<String, Object>> heads = jdbc.queryForList(
                "SELECT s.id, p.year, s.resource_id, h.lastname AS resource_name," +
                "       s.department_name, s.post_name, s.post_id, s.status, s.item_count," +
                "       s.self_score, s.leader_score, s.rater360_score, s.total_score, s.band_name," +
                "       s.comment_text, lh.lastname AS leader_name," +
                "       s.seal_status, s.seal_time, s.seal_by_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater tr" +
                "          WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = s.id)" +
                "            AND tr.rater_type = 'RATER360') AS rater360_count" +
                " FROM uf_pa_sheet s" +
                " JOIN uf_assess_period p ON p.id = s.period_id" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                " LEFT JOIN HrmResource lh ON lh.id = s.leader_resource_id" +
                " WHERE s.id = ? AND s.dimension_id = ?",
                sheetId, dimensionId);
        if (heads.isEmpty()) {
            throw new BusinessException(ERR_REPORT_NOT_FOUND, "测评报告不存在：" + sheetId);
        }
        Map<String, Object> r = heads.get(0);

        PaReportVO.Detail d = new PaReportVO.Detail();
        d.setSheetId(num(r.get("id")));
        d.setYear(intOf(r.get("year")));
        d.setResourceId(num(r.get("resource_id")));
        d.setResourceName(str(r.get("resource_name")));
        d.setDeptName(str(r.get("department_name")));
        d.setPostName(str(r.get("post_name")));
        d.setLeaderName(str(r.get("leader_name")));
        d.setShowRaterName(showRaterName);
        d.setStatus(intOf(r.get("status")));
        d.setStatusName(SHEET_STATUS_NAMES.getOrDefault(d.getStatus(), "未知"));
        d.setRater360Count(intOf(r.get("rater360_count")));
        d.setItemCount(intOf(r.get("item_count")));
        d.setSelfScore(dec(r.get("self_score")));
        d.setLeaderScore(dec(r.get("leader_score")));
        d.setRater360Score(dec(r.get("rater360_score")));
        d.setTotalScore(dec(r.get("total_score")));
        d.setBandName(str(r.get("band_name")));
        // comment_text 快照仍回填（接口契约不破），但页面改为展示 comments 分段：
        // 快照只在全员齐备时写，中途提交的人在里面是看不到的。
        d.setCommentText(str(r.get("comment_text")));
        // 封存信息随实时详情一起回（前端靠它显示「未封存」和封存时间）
        applySealInfo(d, r);
        if (d.getSealStatus() == null) {
            d.setSealStatus(0);
        }

        // 明细：一个能力项一行。等级名取评级行的快照（level_name），
        // 不按当前配置反查 —— 等级被改名/改分值后，历史报告仍按当时的刻度解释。
        // 同理，项得分的刻度名读 item_level_name 快照（算分时写入），
        // 老数据（本列上线前算的分）才回退到按当前配置切区间。
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT i.item_id, i.category_name, i.item_name, i.definition, i.weight, i.rater_360," +
                "       i.leader_score, i.rater360_score, i.rater360_count, i.self_score, i.item_score," +
                "       i.item_level_name," +
                "       (SELECT MAX(r.level_name) FROM uf_pa_rating r WHERE r.sheet_id = i.sheet_id" +
                "          AND r.item_id = i.item_id AND r.rater_type = 'LEADER') AS leader_level_name," +
                "       (SELECT MAX(r.level_name) FROM uf_pa_rating r WHERE r.sheet_id = i.sheet_id" +
                "          AND r.item_id = i.item_id AND r.rater_type = 'SELF') AS self_level_name" +
                " FROM uf_pa_sheet_item i" +
                " WHERE i.sheet_id = ? ORDER BY i.sort_no, i.id",
                sheetId);

        // 360 逐人评级 + 列顺序。这两件事必须用同一条名单定序，否则「测评人1」
        // 在不同能力项下会指向不同的人，逐人展开就变成了逐人错位。
        List<Long> rater360Order = rater360Order(sheetId);
        Map<Long, Map<Long, PaReportVO.RaterEntry>> ratingByItem = rater360Ratings(sheetId);
        // 按项指派（sql/60）：null 表示老任务，没有指派概念，所有人都算「被指派」，只是没评
        Map<Long, Set<Long>> assignByItem = assignByItem(sheetId);
        // 姓名可见性由调用方算好传进来（管理员页走权限判定，本人的报告恒为 false）。
        // 姓名要覆盖「名单外但有评级的人」—— 只按名单查会让追加到末尾那一列没有名字，
        // 而它恰恰是最需要人认得的（那是异常数据，HR 得知道是谁）
        Map<Long, String> raterNames = showRaterName
                ? resourceNames(allRaterIds(rater360Order, ratingByItem)) : Map.of();

        // 刻度名快照缺失时的兜底（2026-11-24）：本列上线前算过的分没有快照，
        // 只能按当前配置现切一次。有新快照的行走快照，不受等级改名影响。
        // 一次查回该页所有能力项的等级配置，不逐行查库：16 项 × 5 档 = 80 行，
        // 逐项查会变成 16 次往返，与 rater360Ratings 同一个理由。
        Map<Long, List<PaItemLevelScale.Point>> itemLevels =
                hasMissingItemLevel(items) ? itemLevels(items) : Map.of();

        // 岗位分值区间（2026-11-24，P12-d）：能力项的综合得分也从这套档位取描述，
        // 与总得分、合计行同一把尺子。一次查回整份区间表（一个岗位 5 档），
        // 逐行查库会变成十几趟往返。
        List<PaBand> bands = bands(num(r.get("post_id")));

        List<PaReportVO.Row> rows = new ArrayList<>(items.size());
        for (Map<String, Object> it : items) {
            PaReportVO.Row row = new PaReportVO.Row();
            row.setItemId(num(it.get("item_id")));
            row.setCategoryName(str(it.get("category_name")));
            row.setItemName(str(it.get("item_name")));
            row.setDefinition(str(it.get("definition")));
            row.setWeight(dec(it.get("weight")));
            row.setLeaderLevelName(str(it.get("leader_level_name")));
            row.setLeaderScore(dec(it.get("leader_score")));
            row.setRater360Score(dec(it.get("rater360_score")));
            row.setRater360Count(intOf(it.get("rater360_count")));
            row.setSelfLevelName(str(it.get("self_level_name")));
            row.setSelfScore(dec(it.get("self_score")));
            row.setItemScore(dec(it.get("item_score")));
            row.setItemLevelName(itemLevelName(it, itemLevels));
            row.setItemBandName(PaBandScale.resolve(bands, row.getItemScore()));
            // assignByItem 为 null = 存量任务（没按项指派过），所有 360 都算「评全部项」；
            // 非 null 时：
            //   · 该项根本不由 360 评（页面快照 rater_360=0，如只由上级评的项）→ 传「全部未分配」，
            //     否则一列「未评」会把「不用他评」说成「他还没交」；
            //   · 该项由 360 评但查不到指派行 → 空集合，同样是全部未分配。
            row.setRater360s(buildRater360Entries(rater360Order, ratingByItem.get(row.getItemId()),
                    raterNames, showRaterName, assignedOf(assignByItem, row.getItemId(), it)));
            rows.add(row);
        }
        d.setRows(rows);
        d.setComments(comments(sheetId, showRaterName));
        return d;
    }

    // ================================================================ 我的测评（本人）

    @Override
    public List<PaReportVO.MyYear> myYears() {
        return myYearList(requireLogin().resourceId());
    }

    /**
     * 本人相关年度：自己的测评页 ∪ 要我参与的评价任务。
     *
     * <p>年度下拉要同时喂两块内容，所以不能只看自己的页面 —— 纯粹的评价人（今年没有自己的测评）
     * 会拿到空年度列表，「我的评价」那块跟着一起空，页面一个年度都没得选。
     * 任务侧的过滤条件与 {@code myTasks} 保持一致（剔除已撤回的、年度关闭且未提交的），
     * 免得下拉里出现一个点进去两块都空的年度。</p>
     *
     * <p>跨维度取：一个人可能在中达集团和国际贸易各有一张页面，本人的视角不该被顶栏选的维度卡住。
     * 同一年度可能有多条记录，用 GROUP BY 收成一年一行，active = 任一条来源里进行中。</p>
     */
    private List<PaReportVO.MyYear> myYearList(long resourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT y.year, MAX(y.active) AS active FROM (" +
                "  SELECT p.year, CASE WHEN p.status = 0 THEN 1 ELSE 0 END AS active" +
                "    FROM uf_pa_sheet s JOIN uf_assess_period p ON p.id = s.period_id" +
                "   WHERE s.resource_id = ?" +
                "  UNION ALL" +
                "  SELECT p.year, CASE WHEN p.status = 0 THEN 1 ELSE 0 END" +
                "    FROM uf_assess_task_rater tr" +
                "    JOIN uf_assess_task t ON t.id = tr.task_id" +
                "    JOIN uf_assess_period p ON p.id = t.period_id" +
                "   WHERE tr.rater_resource_id = ?" +
                "     AND tr.status IN (0, 1)" +
                "     AND t.status IN (0, 2)" +
                "     AND (p.status = 0 OR tr.status = 1)" +
                ") y GROUP BY y.year ORDER BY y.year DESC",
                resourceId, resourceId);
        List<PaReportVO.MyYear> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            PaReportVO.MyYear y = new PaReportVO.MyYear();
            y.setYear(intOf(r.get("year")));
            y.setActive(intOf(r.get("active")) == 1);
            out.add(y);
        }
        return out;
    }

    @Override
    public List<PaReportVO.ListItem> myReports(Integer year) {
        LoginUser user = requireLogin();
        Integer effectiveYear = year != null ? year : activeYear(user.resourceId());
        if (effectiveYear == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id AS sheet_id, p.year, s.resource_id, h.lastname AS resource_name," +
                "       s.department_name, s.post_name, s.status, s.total_score, s.band_name," +
                "       d.dimension_name," +
                "       (SELECT COUNT(*) FROM uf_assess_task_rater tr" +
                "          WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = s.id)" +
                "            AND tr.rater_type = 'RATER360') AS rater360_count" +
                " FROM uf_pa_sheet s" +
                " JOIN uf_assess_period p ON p.id = s.period_id" +
                // 维度名必须带出来：本人视角跨维度取数，同一年度可能有多行不同维度的报告
                " LEFT JOIN uf_assess_dimension d ON d.id = s.dimension_id" +
                " LEFT JOIN HrmResource h ON h.id = s.resource_id" +
                // 封存闸门（sql/64）：本人只看已封存的报告。
                // 没了这一条，「算完分立刻可见」照旧成立，封存就只是管理端的一个标签
                " WHERE s.resource_id = ? AND p.year = ? AND s.seal_status = " + SEALED +
                " ORDER BY d.dimension_name, s.id DESC",
                user.resourceId(), effectiveYear);
        List<PaReportVO.ListItem> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(toListItem(r));
        }
        return out;
    }

    /**
     * 一条报告列表行。
     *
     * <p>抽出来是因为「本人」与「下属」两张列表要的字段完全一样，只有 WHERE 不同。
     * 越权风险在 WHERE 上、不在这一段 —— 所以这段可以共用，那两段 SQL 各自独立。</p>
     */
    private PaReportVO.ListItem toListItem(Map<String, Object> r) {
        PaReportVO.ListItem item = new PaReportVO.ListItem();
        item.setSheetId(num(r.get("sheet_id")));
        item.setYear(intOf(r.get("year")));
        item.setResourceId(num(r.get("resource_id")));
        item.setResourceName(str(r.get("resource_name")));
        item.setDeptName(str(r.get("department_name")));
        item.setPostName(str(r.get("post_name")));
        item.setDimensionName(str(r.get("dimension_name")));
        item.setStatus(intOf(r.get("status")));
        item.setStatusName(SHEET_STATUS_NAMES.getOrDefault(item.getStatus(), "未知"));
        item.setRater360Count(intOf(r.get("rater360_count")));
        item.setTotalScore(dec(r.get("total_score")));
        item.setBandName(str(r.get("band_name")));
        return item;
    }

    @Override
    public PaReportVO.Detail myDetail(Long sheetId) {
        LoginUser user = requireLogin();
        // 归属校验必须在这里做：本人接口走的是「登录人 == 被测评人」，不是维度授权。
        // 少了这一条，任何登录用户换个 sheetId 就能读别人的报告。
        List<Long> owners = jdbc.queryForList(
                "SELECT resource_id FROM uf_pa_sheet WHERE id = ?", Long.class, sheetId);
        if (owners.isEmpty() || !user.resourceId().equals(owners.get(0))) {
            throw new BusinessException(ERR_REPORT_NOT_FOUND, "测评报告不存在：" + sheetId);
        }
        // 维度取这张页面自己的维度，而不是上下文：本人没做维度切换，
        // 上下文里的默认维度与他这张页面多半不是同一个。
        long sheetDimension = jdbc.queryForObject(
                "SELECT dimension_id FROM uf_pa_sheet WHERE id = ?", Long.class, sheetId);
        // 封存闸门（sql/64）：没封存的报告本人看不到。
        // 归属校验已在上面过了，所以这里可以直说「还没封存」而不是含糊成「不存在」——
        // 对本人来说，知道「等 HR 确认」比「报告凭空消失」有用。
        requireSealed(sheetId);
        // 姓名恒不返回：本人不需要知道「测评人2」是谁。
        return readDetail(sheetId, sheetDimension, false);
    }

    /** 当前年度：本人相关年度里「进行中」的那年；没有进行中的就取最近一年 */
    private Integer activeYear(long resourceId) {
        List<PaReportVO.MyYear> years = myYearList(resourceId);
        for (PaReportVO.MyYear y : years) {
            if (Boolean.TRUE.equals(y.getActive())) {
                return y.getYear();
            }
        }
        return years.isEmpty() ? null : years.get(0).getYear();
    }

    private static LoginUser requireLogin() {
        LoginUser user = UserContext.currentOrNull();
        if (user == null || user.resourceId() == null) {
            throw new BusinessException(ERR_NOT_LOGGED_IN, "请先登录");
        }
        return user;
    }

    // ================================================================ 评语

    /**
     * 逐人评语（2026-11-23，口径 #14 修正）：实时读该页最近一次任务的已提交评语。
     *
     * <p>为什么不再读 {@code uf_pa_sheet.comment_text} 快照：那个字段只在
     * {@code finish()} 里写，而 finish 要等「除自评外全员齐备」。一个 360 拖着不交，
     * 已交者的评语在报告上就一个字都看不到 —— 王国栋那张页面（2026-11-22）丢了
     * 王建龙的「优秀」正是这个原因。评价提交是一次事实，不该被别人的进度扣着。</p>
     *
     * <p>编号口径（2026-11-23 业务确认，方案 A）：上级与 360 统一按「测评人N」编号，
     * <b>上级是测评人1</b>，360 从 2 开始，顺序与该页最近一次任务的名单
     * （{@code ORDER BY tr.id}，即发起时的选择顺序）一致。评论区与明细列头
     * 必须用同一套编号，否则「测评人2 说…」指的是谁就成了猜谜。</p>
     *
     * <p>撤回（status=3）与未提交（status=0）都不显示：撤回的评语随评级一起失效，
     * 未提交的人根本没有评语可显示。</p>
     */
    private List<PaReportVO.CommentEntry> comments(long sheetId, boolean showName) {
        Long taskId = jdbc.queryForObject(
                "SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = ?", Long.class, sheetId);
        if (taskId == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tr.rater_type, tr.rater_resource_id, tr.comment, tr.submit_time" +
                " FROM uf_assess_task_rater tr" +
                " WHERE tr.task_id = ? AND tr.status = 1" +
                "   AND tr.rater_type IN ('LEADER', 'RATER360')" +
                "   AND tr.comment IS NOT NULL AND LTRIM(RTRIM(tr.comment)) <> ''" +
                // 上级在前，360 按名单顺序 —— 与测评人编号同序
                " ORDER BY CASE tr.rater_type WHEN 'LEADER' THEN 0 ELSE 1 END, tr.id",
                taskId);
        if (rows.isEmpty()) {
            return List.of();
        }

        // 编号与明细列头同源：360 的顺序必须取 rater360Order（名单顺序），
        // 不能在这里另用一套「按提交时间/按 id」，两处一旦分叉就全错位。
        List<Long> order360 = rater360Order(sheetId);
        Map<Long, Integer> number360 = new HashMap<>();
        for (int i = 0; i < order360.size(); i++) {
            number360.put(order360.get(i), i + 2);  // 上级占 1
        }

        // 姓名一次查回：不按人逐个查，报告页每次打开会变成 N 次往返
        Map<Long, String> names = showName
                ? resourceNames(rows.stream().map(m -> num(m.get("rater_resource_id")))
                        .distinct().collect(Collectors.toList()))
                : Map.of();

        List<PaReportVO.CommentEntry> out = new ArrayList<>(rows.size());
        for (Map<String, Object> m : rows) {
            long raterId = num(m.get("rater_resource_id"));
            boolean leader = "LEADER".equals(str(m.get("rater_type")));
            // 名单外的人（换过任务 / 手工改库）给不到编号：退回「测评人」不带号，
            // 这比硬造一个与列头冲突的号更不容易误导。
            Integer no = leader ? 1 : number360.get(raterId);

            PaReportVO.CommentEntry e = new PaReportVO.CommentEntry();
            e.setSourceType(str(m.get("rater_type")));
            e.setDisplayName(no == null ? "测评人" : "测评人" + no);
            e.setRaterName(showName ? names.get(raterId) : null);
            e.setResourceId(raterId);
            e.setComment(str(m.get("comment")));
            e.setSubmitTime(m.get("submit_time") == null ? null : m.get("submit_time").toString());
            out.add(e);
        }
        return out;
    }

    // ================================================================ 360 逐人

    /**
     * 该页 360 测评人的展示顺序：<b>取最近一次任务的名单</b>，按发起时的选择顺序。
     *
     * <p>不用「有哪些评级」来定序：名单里有、没评的人会被漏掉，报告上看不出「张三没交」；
     * 也不用 resource_id 排序：那个顺序与谁先被选中无关，人事核对时对不上号。
     * 任务表按 rater_type + id 排序即可 —— createRater 是按选择顺序插入的。</p>
     */
    /**
     * 该页最近一次任务的「按项指派」关系：itemId → 被指派的 360 人员 ID。
     *
     * <p><b>返回 null 表示老任务</b>（没有指派行）—— 与「有指派但某一项为空」区分开，
     * 后者 Map 里那个 itemId 对应空集合或干脆不出现，页面显示「未分配」。</p>
     *
     * <p>为什么以「整页有没有任何指派行」为判据，而不是逐项判断：
     * 一次任务要么走新口径（每项都可能有指派），要么走旧口径（全都没有）；
     * 逐项判断会把「新任务里某一项没填人」误认成老任务，那一格就变成
     * 「未评」而非「未分配」，HR 会去催一个本来就没被安排的人。
     * 判据取「任务级」是唯一能区分这两者的口径。</p>
     */
    private Map<Long, Set<Long>> assignByItem(long sheetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ri.item_id, tr.rater_resource_id" +
                " FROM uf_assess_task_rater_item ri" +
                " JOIN uf_assess_task_rater tr ON tr.id = ri.rater_id" +
                " WHERE ri.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = ?)" +
                " ORDER BY ri.id", sheetId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<Long, Set<Long>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.computeIfAbsent(num(r.get("item_id")), k -> new LinkedHashSet<>())
                    .add(num(r.get("rater_resource_id")));
        }
        return out;
    }

    private List<Long> rater360Order(long sheetId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT tr.rater_resource_id FROM uf_assess_task_rater tr" +
                " WHERE tr.task_id = (SELECT MAX(t.id) FROM uf_assess_task t WHERE t.sheet_id = ?)" +
                "   AND tr.rater_type = 'RATER360' ORDER BY tr.id",
                Long.class, sheetId);
        return new ArrayList<>(ids);
    }

    /**
     * 全部 360 评级：itemId → (raterResourceId → 评级)。
     *
     * <p>一次查回而不是每项一个子查询：16 个能力项 × N 个 360，子查询写法在报告页
     * 打开时会变成几十次往返。表上有 {@code IX_pa_rating_sheet}，按 sheet 取全量很快。</p>
     */
    private Map<Long, Map<Long, PaReportVO.RaterEntry>> rater360Ratings(long sheetId) {
        Map<Long, Map<Long, PaReportVO.RaterEntry>> byItem = new LinkedHashMap<>();
        jdbc.query("SELECT item_id, rater_resource_id, level_name, score FROM uf_pa_rating" +
                        " WHERE sheet_id = ? AND rater_type = 'RATER360' ORDER BY id",
                rs -> {
                    PaReportVO.RaterEntry entry = new PaReportVO.RaterEntry();
                    long raterId = rs.getLong("rater_resource_id");
                    entry.setResourceId(raterId);
                    entry.setLevelName(rs.getString("level_name"));
                    entry.setScore(dec(rs.getObject("score")));
                    byItem.computeIfAbsent(rs.getLong("item_id"), k -> new LinkedHashMap<>())
                            .put(raterId, entry);
                }, sheetId);
        return byItem;
    }

    /**
     * 按名单顺序铺出每一项的 360 单元格。
     *
     * <p>名单里没有、但有评级的人追加到末尾：这只会出现在「换过任务 / 手工改库」的场景，
     * 直接丢弃等于把已经发生的评价悄悄抹掉，那比多一列难看得多。</p>
     *
     * @param assigned 这一项指派了给谁（sql/60）。<b>null 表示老任务</b>——
     *                 没有按项指派的概念，名单里的人一律算「该评」；
     *                 非 null 时，不在这集合里的人标 {@code assigned=false}，
     *                 页面显示「未分配」而不是「未评」。这两个词对 HR 的意义完全不同：
     *                 「未评」要去催，「未分配」说明当初没人被指派，催也没用。
     */
    /**
     * 某一项上「谁被指派了」。
     *
     * <p>返回 null 表示「不作判断，所有人都算指派过（只是没评）」—— 只有存量任务是这样：
     * 那时没有指派概念，360 全员评全部 360 项，写「未分配」会篡改历史口径。</p>
     */
    private Set<Long> assignedOf(Map<Long, Set<Long>> assignByItem, Long itemId, Map<String, Object> itemRow) {
        if (assignByItem == null) {
            return null;
        }
        if (!Objects.equals(intOf(itemRow.get("rater_360")), 1)) {
            return Set.of();   // 这一项本来就不由 360 评
        }
        return assignByItem.getOrDefault(itemId, Set.of());
    }

    private List<PaReportVO.RaterEntry> buildRater360Entries(List<Long> order,
                                                             Map<Long, PaReportVO.RaterEntry> ratings,
                                                             Map<Long, String> names,
                                                             boolean showName,
                                                             Set<Long> assigned) {
        List<PaReportVO.RaterEntry> out = new ArrayList<>(order.size());
        Set<Long> placed = new LinkedHashSet<>();
        for (Long raterId : order) {
            PaReportVO.RaterEntry found = ratings == null ? null : ratings.get(raterId);
            PaReportVO.RaterEntry e = entry(raterId, found == null ? null : found.getLevelName(),
                    found == null ? null : found.getScore(), names.get(raterId), showName);
            e.setAssigned(assigned == null || assigned.contains(raterId));
            out.add(e);
            placed.add(raterId);
        }
        if (ratings != null) {
            for (Map.Entry<Long, PaReportVO.RaterEntry> e : ratings.entrySet()) {
                if (placed.contains(e.getKey())) {
                    continue;
                }
                PaReportVO.RaterEntry extra = entry(e.getKey(), e.getValue().getLevelName(),
                        e.getValue().getScore(), names.get(e.getKey()), showName);
                extra.setAssigned(true);
                out.add(extra);
            }
        }
        return out;
    }

    private static PaReportVO.RaterEntry entry(Long raterId, String levelName, BigDecimal score,
                                               String name, boolean showName) {
        PaReportVO.RaterEntry entry = new PaReportVO.RaterEntry();
        entry.setResourceId(raterId);
        entry.setLevelName(levelName);
        entry.setScore(score);
        entry.setRaterName(showName ? name : null);
        return entry;
    }

    /**
     * 调用人能不能看到 360 测评人的姓名：系统管理员，或本维度的维度管理员。
     *
     * <p>为什么系统管理员也放行：他要核对报告，不该为了看姓名先把自己加成维度管理员 ——
     * 那会让「管理员名单」这个业务配置混进运维身份。反过来若只放行维度管理员，
     * 新维度还没配管理员时管理员自己也看不到，排查「姓名怎么不显示」会绕一圈。</p>
     *
     * <p>匿名调用（泛微钩子等）一律不给姓名 —— 与授权判断的宽松口径相反，
     * 后者是为了不打断既有集成，这里泄露的是真人身份，宁可保守。</p>
     */
    private boolean canSeeRaterName(long dimensionId) {
        LoginUser user = UserContext.currentOrNull();
        if (user == null) {
            return false;
        }
        if (adminService.isAdmin(user.loginid())) {
            return true;
        }
        return dimensionService.isDimensionAdmin(dimensionId, user.resourceId());
    }

    /**
     * 名单顺序的人 + 名单外有评级的人，去重后作为查姓名的 ID 集合。
     * 顺序不重要（结果进 Map），但去重必须做 —— IN 里有重复值没有错，只是白传。
     */
    private static List<Long> allRaterIds(List<Long> order,
                                          Map<Long, Map<Long, PaReportVO.RaterEntry>> ratingByItem) {
        Set<Long> ids = new LinkedHashSet<>(order);
        for (Map<Long, PaReportVO.RaterEntry> perItem : ratingByItem.values()) {
            ids.addAll(perItem.keySet());
        }
        return new ArrayList<>(ids);
    }

    /** 人员 ID → 姓名（仅权限通过时才查，没权限连查都不查） */
    private Map<Long, String> resourceNames(List<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = resourceIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT id, lastname AS name FROM HrmResource WHERE id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, resourceIds.toArray());
        return names;
    }

    // ================================================================ 项得分刻度

    /**
     * 该页所有能力项的等级配置：itemId → 按分值升序的档位。
     *
     * <p>只在「有行缺刻度名快照」时才调用（老数据兜底），正常路径读
     * {@code uf_pa_sheet_item.item_level_name}，不再查这张配置表。</p>
     *
     * <p>按 {@code item_id IN (...)} 一次查回。IN 里最多十几项，走
     * {@code IX_pa_item_level_item} 很快；逐项查则是十几趟往返。</p>
     */
    private Map<Long, List<PaItemLevelScale.Point>> itemLevels(List<Map<String, Object>> items) {
        List<Long> itemIds = items.stream()
                .map(m -> num(m.get("item_id")))
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = itemIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, List<PaItemLevelScale.Point>> out = new HashMap<>();
        jdbc.query("SELECT item_id, level_name, score FROM uf_pa_item_level" +
                        " WHERE item_id IN (" + placeholders + ") ORDER BY item_id, score",
                rs -> {
                    out.computeIfAbsent(rs.getLong("item_id"), k -> new ArrayList<>())
                            .add(new PaItemLevelScale.Point(dec(rs.getObject("score")),
                                    PaItemLevelScale.stripPrefix(rs.getString("level_name"))));
                }, itemIds.toArray());
        return out;
    }

    /**
     * 该页岗位的分值区间表（按 {@code sort} 升序，{@link PaBandScale#resolve} 依赖这个顺序）。
     *
     * <p>岗位为空（页面快照里没有岗位）或该岗位没配档位时返回空表 ——
     * 报告上就只显示分数、不显示描述，而不是报错：一份没配档位的岗位
     * 不该让整份报告打不开。</p>
     */
    private List<PaBand> bands(long postId) {
        if (postId <= 0) {
            return List.of();
        }
        return jdbc.query("SELECT band_name, lower_bound, upper_bound FROM uf_pa_band" +
                        " WHERE post_id = ? ORDER BY sort, id",
                (rs, i) -> {
                    PaBand b = new PaBand();
                    b.setBandName(rs.getString("band_name"));
                    b.setLowerBound(dec(rs.getObject("lower_bound")));
                    b.setUpperBound(dec(rs.getObject("upper_bound")));
                    return b;
                }, postId);
    }

    /** 有没有行「算过分但没有刻度名快照」——只有这种行才需要回退去查等级配置 */
    private static boolean hasMissingItemLevel(List<Map<String, Object>> items) {
        for (Map<String, Object> it : items) {
            if (it.get("item_score") != null && str(it.get("item_level_name")) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一行的刻度名：<b>优先读快照</b>（{@code uf_pa_sheet_item.item_level_name}，
     * 算分时写入），没有快照才按当前配置现切一次。
     *
     * <p>快照优先的意义与 {@code leaderLevelName} 一致（口径 #15）：等级被改名后，
     * 历史报告仍按当时的刻度解释。只有本列上线前算的分没有快照，那部分退化为现切 ——
     * 它们本来也没有别的来源可用。</p>
     */
    private static String itemLevelName(Map<String, Object> it,
                                        Map<Long, List<PaItemLevelScale.Point>> fallbackLevels) {
        String snapshot = str(it.get("item_level_name"));
        if (snapshot != null) {
            return snapshot;
        }
        Long itemId = num(it.get("item_id"));
        if (itemId <= 0) {
            return null;
        }
        return PaItemLevelScale.resolve(fallbackLevels.get(itemId), dec(it.get("item_score")));
    }

    // ================================================================ 工具

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
}
