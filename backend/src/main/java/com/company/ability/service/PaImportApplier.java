package com.company.ability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaImportDraft;
import com.company.ability.dto.PaImportPlan;
import com.company.ability.dto.PaImportPreview;
import com.company.ability.dto.PaTaskDTO;
import com.company.ability.vo.PaTaskVO;
import com.company.ability.entity.PaBand;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaItemLevel;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaRating;
import com.company.ability.entity.PaSheetItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaBandMapper;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemLevelMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.mapper.PaRatingMapper;
import com.company.ability.mapper.PaSheetItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 导入执行：把草稿写进库。
 *
 * <p><b>整批一个事务</b>：10 个岗位导入，第 7 个失败时前面 6 个已经落库 ——
 * 用户会看到「导了一半」，而且还不知道停在哪。所以本方法整体 {@code @Transactional}，
 * 任何一步抛错全部回滚，会话置 FAILED，用户可以改完再来。
 * （例外：撤回消息的写入失败不回滚 —— 见 {@link #voidRunningTasks}。）</p>
 *
 * <p>三类写分支：</p>
 * <ol>
 *   <li><b>CREATE</b>：库里没有这个部门+岗位</li>
 *   <li><b>OVERWRITE</b>：库里有，以文件为准。能力项按「编码优先、名称兜底」匹配：
 *       匹配上的原地更新（保住 id，历史评级不会失联），匹配不上的新增，
 *       文件里没有的<b>停用</b>而不是删除（历史报告还要读它）</li>
 *   <li><b>SKIP</b>：什么都不做</li>
 * </ol>
 *
 * <p>三类测评分支（只在 OVERWRITE 且有影响时触发）：</p>
 * <ol>
 *   <li>进行中 → 撤回（可选按原参数重发）</li>
 *   <li>已封存 + 用户勾选 → 重算（用当初的评级，不重发问卷）</li>
 *   <li>已封存 + 未勾选 → 原样保留</li>
 * </ol>
 */
@Slf4j
@Service
public class PaImportApplier {

    /** 撤回原因默认值：用户没填时用它，保证外发消息里不会出现空白原因 */
    private static final String DEFAULT_VOID_REASON = "能力模型已更新，本期测评需按新模型重新发起";
    /** uf_pa_rating 表没有 dimension 语义之外的删除保护，这里是唯一的删入口 */
    private static final int TASK_RUNNING = 0;
    private static final int TASK_VOIDED = 4;
    private static final int RATER_VOIDED = 3;
    private static final int SHEET_INIT = 0;

    private final JdbcTemplate jdbc;
    private final PaPostMapper postMapper;
    private final PaCategoryMapper categoryMapper;
    private final PaItemMapper itemMapper;
    private final PaItemLevelMapper levelMapper;
    private final PaBandMapper bandMapper;
    private final PaRatingMapper ratingMapper;
    private final PaSheetItemMapper sheetItemMapper;
    private final PaImportValidator validator;
    private final PaImportPlanner planner;
    private final PaScoreService scoreService;
    private final PaTaskService taskService;
    private final AssessPushService pushService;

    public PaImportApplier(JdbcTemplate jdbc, PaPostMapper postMapper, PaCategoryMapper categoryMapper,
                           PaItemMapper itemMapper, PaItemLevelMapper levelMapper, PaBandMapper bandMapper,
                           PaRatingMapper ratingMapper, PaSheetItemMapper sheetItemMapper,
                           PaImportValidator validator, PaImportPlanner planner, PaScoreService scoreService,
                           PaTaskService taskService, AssessPushService pushService) {
        this.jdbc = jdbc;
        this.postMapper = postMapper;
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
        this.levelMapper = levelMapper;
        this.bandMapper = bandMapper;
        this.ratingMapper = ratingMapper;
        this.sheetItemMapper = sheetItemMapper;
        this.validator = validator;
        this.planner = planner;
        this.scoreService = scoreService;
        this.taskService = taskService;
        this.pushService = pushService;
    }

    /**
     * 执行导入。
     *
     * @param draft   已校验的草稿
     * @param plan    用户的最终选择
     * @param preview 预览（执行前再算一遍，确保与用户看到的一致）
     * @return 执行结果（逐岗位决策 + 测评影响处理计数）
     */
    @Transactional(rollbackFor = Exception.class)
    public Result apply(PaImportDraft draft, PaImportPlan plan, PaImportPreview preview) {
        PaImportPlan effective = plan == null ? PaImportPlan.safeDefault() : plan;
        Result result = new Result();

        for (PaImportPreview.PostPreview pp : preview.getPosts()) {
            PaImportDraft.PostDraft postDraft = findDraft(draft, pp.getSheetName());
            if (postDraft == null) {
                continue;
            }
            switch (pp.getAction()) {
                case "SKIP" -> {
                    result.getSkipped().add(pp.getPostName());
                    log.info("导入跳过岗位「{}」（库里已存在）", pp.getPostName());
                }
                case "OVERWRITE" -> {
                    result.getOverwritten().add(pp.getPostName());
                    writeOverwrite(pp.getPostId(), postDraft, result);
                }
                default -> {
                    result.getCreated().add(pp.getPostName());
                    writeCreate(postDraft, result);
                }
            }
        }

        // 测评影响处理必须在模型写完以后：重算要按新模型，撤回则与模型版本无关但顺序上放后面更清楚
        handleImpact(preview, effective, result);

        result.setFinishedAt(LocalDateTime.now());
        log.info("导入执行完成：新建 {} / 覆盖 {} / 跳过 {}，撤回 {}，重算 {}",
                result.getCreated().size(), result.getOverwritten().size(), result.getSkipped().size(),
                result.getVoidedTaskIds().size(), result.getRecalculatedSheetIds().size());
        return result;
    }

    // ================================================================ 岗位写入

    private void writeCreate(PaImportDraft.PostDraft draft, Result result) {
        long dim = DimensionContext.getOrDefault();
        String deptName = resolveDepartmentName(draft.getDepartmentId());

        PaPost post = new PaPost();
        post.setDimensionId(dim);
        post.setDepartmentId(draft.getDepartmentId());
        post.setDepartmentName(deptName);
        post.setPostName(draft.getPostName().trim());
        post.setStatus(1);
        post.setSort(0);
        postMapper.insert(post);
        result.addPostId(post.getId());

        writeContent(post.getId(), draft, List.of(), result);
        log.info("导入新建岗位 postId={}, 部门={}, 岗位={}, 能力项={}",
                post.getId(), deptName == null ? "全公司通用" : deptName,
                post.getPostName(), draft.getItems().size());
    }

    private void writeOverwrite(Long postId, PaImportDraft.PostDraft draft, Result result) {
        PaPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException("岗位已不存在（可能被同时删除）：" + draft.getPostName() + "，请重新上传");
        }
        // 部门名可能改了：覆盖时一并刷新快照。
        // 通用岗位（部门为空）在这里不会被走到 —— 覆盖只匹配同部门的同名岗位，
        // 部门本身就是唯一键的一部分（见 PaImportValidator.findExistingPost）
        post.setDepartmentName(resolveDepartmentName(draft.getDepartmentId()));
        post.setPostName(draft.getPostName().trim());
        postMapper.updateById(post);
        result.addPostId(postId);

        List<PaItem> existing = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId).eq(PaItem::getDeleted, 0));
        writeContent(postId, draft, existing, result);
        log.info("导入覆盖岗位 postId={}, 岗位={}, 原有能力项={}, 新={}",
                postId, post.getPostName(), existing.size(), draft.getItems().size());
    }

    /**
     * 写类别 / 能力项 / 等级 / 区间。
     *
     * <p>能力项匹配走 {@link PaImportItemMatcher}（编码+名称 → 编码 → 名称 三级）。
     * 不能只按编码：编码允许重复（sql/65），同一岗位里 E01 可能既是「服务意识」又是「抗压能力」，
     * 只按编码建 Map 会让后一项覆盖前一项，两个草稿项都更新同一行，
     * 另一行被当成「文件里没有」删掉 —— 库里的能力项凭空少了一半。</p>
     */
    private void writeContent(Long postId, PaImportDraft.PostDraft draft,
                              List<PaItem> existingItems, Result result) {
        long dim = DimensionContext.getOrDefault();

        // ① 类别：按名称匹配，缺的补，顺序按文件
        Map<String, Long> categoryIds = new LinkedHashMap<>();
        List<PaCategory> oldCategories = categoryMapper.selectList(new LambdaQueryWrapper<PaCategory>()
                .eq(PaCategory::getPostId, postId).eq(PaCategory::getDeleted, 0));
        Map<String, PaCategory> oldByName = new LinkedHashMap<>();
        for (PaCategory c : oldCategories) {
            oldByName.put(nz(c.getCategoryName()), c);
        }
        int sort = 0;
        for (PaImportDraft.CategoryDraft cd : draft.getCategories()) {
            String name = nz(cd.getCategoryName());
            PaCategory old = oldByName.remove(name);
            if (old != null) {
                old.setSort(sort++);
                old.setStatus(1);
                categoryMapper.updateById(old);
                categoryIds.put(name, old.getId());
                result.incrCategoryUpdated();
            } else {
                PaCategory c = new PaCategory();
                c.setDimensionId(dim);
                c.setPostId(postId);
                c.setCategoryName(name);
                c.setSort(sort++);
                c.setStatus(1);
                categoryMapper.insert(c);
                categoryIds.put(name, c.getId());
                result.incrCategoryCreated();
            }
        }
        // 文件里没有的类别：软删（历史能力项还挂在它下面，物理删会让报告丢类别名）
        for (PaCategory gone : oldByName.values()) {
            gone.setStatus(0);
            categoryMapper.updateById(gone);
        }

        // ② 能力项：三级匹配（编码+名称 → 编码 → 名称），命中即消耗
        PaImportItemMatcher matcher = new PaImportItemMatcher(existingItems);
        int itemSort = 0;
        for (PaImportDraft.ItemDraft id : draft.getItems()) {
            Long categoryId = categoryIds.get(nz(id.getCategoryName()));
            if (categoryId == null) {
                // 装配阶段保证了每个能力项都属于某个类别；走到这里说明类别被合并掉了
                throw new BusinessException("能力项「" + id.getItemName() + "」的类别「"
                        + id.getCategoryName() + "」没有落库，请重新上传");
            }
            PaItem match = matcher.match(id);
            if (match != null) {
                updateItem(match, categoryId, id, itemSort++, dim);
                result.incrItemUpdated();
                writeLevels(match.getId(), id.getLevels(), dim, result);
            } else {
                PaItem created = createItem(postId, categoryId, id, itemSort++, dim);
                result.incrItemCreated();
                writeLevels(created.getId(), id.getLevels(), dim, result);
            }
        }

        // ③ 没被任何草稿项匹配上的：有引用 → 停用；无引用 → 逻辑删除
        for (PaItem old : matcher.remaining()) {
            if (isReferenced(old.getId())) {
                old.setStatus(0);
                itemMapper.updateById(old);
                result.incrItemDisabled();
            } else {
                itemMapper.deleteById(old.getId());
                levelMapper.delete(new LambdaQueryWrapper<PaItemLevel>()
                        .eq(PaItemLevel::getItemId, old.getId()));
                result.incrItemRemoved();
            }
        }

        // ④ 分值区间：整批替换（区间行不被任何业务表外键引用，物理删安全）
        bandMapper.delete(new LambdaQueryWrapper<PaBand>().eq(PaBand::getPostId, postId));
        for (PaImportDraft.BandDraft bd : draft.getBands()) {
            PaBand band = new PaBand();
            band.setDimensionId(dim);
            band.setPostId(postId);
            band.setBandName(bd.getBandName());
            band.setLowerBound(bd.getLowerBound());
            band.setUpperBound(bd.getUpperBound());
            band.setSort(bd.getSort());
            bandMapper.insert(band);
            result.incrBandCreated();
        }
    }

    private PaItem createItem(Long postId, Long categoryId, PaImportDraft.ItemDraft d, int sort, long dim) {
        PaItem item = new PaItem();
        item.setDimensionId(dim);
        item.setPostId(postId);
        item.setCategoryId(categoryId);
        item.setItemCode(blankToNull(d.getItemCode()));
        item.setItemName(nz(d.getItemName()));
        item.setDefinition(blankToNull(d.getDefinition()));
        item.setWeight(d.getWeight());
        item.setRaterLeader(d.getRaterLeader());
        item.setRater360(d.getRater360());
        item.setRaterText(blankToNull(d.getRaterText()));
        item.setSort(sort);
        item.setStatus(1);
        itemMapper.insert(item);
        return item;
    }

    private void updateItem(PaItem item, Long categoryId, PaImportDraft.ItemDraft d, int sort, long dim) {
        // 用 UpdateWrapper 显式 set：definition 可能从有值变空，updateById 的 NOT_NULL 策略会跳过
        itemMapper.update(null, new UpdateWrapper<PaItem>()
                .eq("id", item.getId())
                .set("category_id", categoryId)
                .set("item_code", blankToNull(d.getItemCode()))
                .set("item_name", nz(d.getItemName()))
                .set("definition", blankToNull(d.getDefinition()))
                .set("weight", d.getWeight())
                .set("rater_leader", d.getRaterLeader())
                .set("rater_360", d.getRater360())
                // 原文写空也要覆盖：徽标跟着两个布尔走，描述留着上一次的导入结果会自相矛盾
                .set("rater_text", blankToNull(d.getRaterText()))
                .set("sort", sort)
                .set("status", 1)
                .set("update_time", LocalDateTime.now()));
    }

    /**
     * 整批覆盖能力项等级。
     *
     * <p>匹配规则是<b>按分值</b>而不是按顺序：业务改等级时通常是「把 3 分的描述改了」，
     * 分值是用来算分的刻度，档名与描述是文字。按分值匹配能保住 {@code level_id} ——
     * 而 {@code level_id} 被 {@code uf_pa_rating} 引用，换 id 会让历史评级失联。</p>
     */
    private void writeLevels(Long itemId, List<PaImportDraft.LevelDraft> levels, long dim, Result result) {
        List<PaItemLevel> existing = levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                .eq(PaItemLevel::getItemId, itemId));
        // 键必须 stripTrailingZeros：库里是 DECIMAL(5,2)，读出来是 1.00，
        // 而文件里解析出来是 1 —— BigDecimal.equals 在乎 scale，直接当 key 会匹配不上，
        // 结果是「分值和档名都没变，却每次导入都把等级删了重建」，历史评级随之失联（实测踩过）。
        Map<BigDecimal, PaItemLevel> byScore = new LinkedHashMap<>();
        for (PaItemLevel lv : existing) {
            if (lv.getScore() != null) {
                byScore.putIfAbsent(scoreKey(lv.getScore()), lv);
            }
        }
        Set<Long> touched = new LinkedHashSet<>();
        int sort = 0;
        for (PaImportDraft.LevelDraft d : levels) {
            PaItemLevel match = d.getScore() == null ? null : byScore.get(scoreKey(d.getScore()));
            if (match != null) {
                touched.add(match.getId());
                levelMapper.update(null, new UpdateWrapper<PaItemLevel>()
                        .eq("id", match.getId())
                        .set("level_name", nz(d.getLevelName()))
                        .set("level_desc", blankToNull(d.getLevelDesc()))
                        .set("sort", sort++)
                        .set("update_time", LocalDateTime.now()));
                result.incrLevelUpdated();
            } else {
                PaItemLevel lv = new PaItemLevel();
                lv.setDimensionId(dim);
                lv.setItemId(itemId);
                lv.setLevelName(nz(d.getLevelName()));
                lv.setScore(d.getScore());
                lv.setLevelDesc(blankToNull(d.getLevelDesc()));
                lv.setSort(sort++);
                levelMapper.insert(lv);
                touched.add(lv.getId());
                result.incrLevelCreated();
            }
        }
        for (PaItemLevel gone : existing) {
            if (touched.contains(gone.getId())) {
                continue;
            }
            long rated = ratingMapper.selectCount(new LambdaQueryWrapper<PaRating>()
                    .eq(PaRating::getLevelId, gone.getId()));
            if (rated > 0) {
                // 有评级引用：不删，留着让历史报告还能解释「当时选的是这一档」
                log.info("等级 levelId={} 有 {} 条评级引用，保留不删（新模型已不含此档）", gone.getId(), rated);
                result.incrLevelKept();
            } else {
                levelMapper.deleteById(gone.getId());
                result.incrLevelRemoved();
            }
        }
    }

    private boolean isReferenced(Long itemId) {
        long ratings = ratingMapper.selectCount(new LambdaQueryWrapper<PaRating>()
                .eq(PaRating::getItemId, itemId));
        if (ratings > 0) {
            return true;
        }
        return sheetItemMapper.selectCount(new LambdaQueryWrapper<PaSheetItem>()
                .eq(PaSheetItem::getItemId, itemId)) > 0;
    }

    // ================================================================ 测评影响

    /**
     * 处理测评影响。
     *
     * <p><b>重算封存报告不能整体回滚</b>：重算失败说明数据本身有矛盾（比如新模型的区间
     * 命中不到总分）。这时把已经重算成功的报告一起回滚，用户会看到「导入失败但库里有改动」
     * 更混乱的结果 —— 所以导入本身（模型部分）提交，重算失败逐份记 warning，
     * 让用户去报告页单独点重算就能看到具体错误。</p>
     */
    private void handleImpact(PaImportPreview preview, PaImportPlan plan, Result result) {
        List<PaImportPreview.RunningTask> running = preview.getImpact().getRunning();
        if (!running.isEmpty() && plan.isVoidRunning()) {
            voidRunningTasks(running, plan, result);
        }

        for (PaImportPreview.FrozenReport fr : preview.getImpact().getFrozen()) {
            if (!plan.shouldRegenerate(fr.getYear())) {
                continue;
            }
            // 已封存的报告不重算（sql/64）：封存快照里是封存那一刻的分数，
            // 按新模型重算一遍会让「已封存」的那份与库里的数据分叉，
            // 而本人看到的是快照 —— 等于悄悄改了对外承诺的数字。
            if (fr.isSealed()) {
                result.getWarnings().add("「" + fr.getResourceName() + "」的 " + fr.getYear()
                        + " 年度报告已封存，本次未重算。如确需按新模型重算，请先在测评报告页解除封存。");
                continue;
            }
            try {
                scoreService.recalculate(fr.getSheetId());
                result.getRecalculatedSheetIds().add(fr.getSheetId());
            } catch (Exception e) {
                log.warn("导入后重算报告失败 sheetId={}: {}", fr.getSheetId(), e.getMessage());
                result.getWarnings().add("「" + fr.getResourceName() + "」的 " + fr.getYear()
                        + " 年度报告重算失败：" + e.getMessage() + "。可在报告页单独重算。");
            }
        }
    }

    /**
     * 撤回进行中的任务并（按选择）重新发起。
     *
     * <p>与「只刷新问卷」的区别：撤回会作废评价人已提交的内容（删评级、页面归零、token 失效）。
     * 这是业务确认的口径 —— 旧问卷评出来的分数在新模型下没有解释力，留着才是错。</p>
     *
     * <p>重新发起直接走 {@link PaTaskService#create}：发起闸门（年度未关闭、人员可登录、
     * 上级仍在职）一次都不能跳过，否则会造出一批打不开的任务链接。任一任务重发失败
     * 只记 warning 不抛：撤回这件事本身已经正确完成，重发是第二步，可以人工补。</p>
     *
     * <p><b>转发撤回通知失败不回滚</b>：通知写不出去（泛微消息表异常）不代表撤回错了。
     * 撤回已经让 token 失效，评价人点开就是撤回提示页，仍然能自行核实。</p>
     */
    private void voidRunningTasks(List<PaImportPreview.RunningTask> running, PaImportPlan plan, Result result) {
        String reason = plan.getVoidReason() == null || plan.getVoidReason().isBlank()
                ? DEFAULT_VOID_REASON : plan.getVoidReason().trim();

        for (PaImportPreview.RunningTask rt : running) {
            Map<String, Object> head = loadTaskHead(rt.getTaskId());
            if (head == null) {
                continue;
            }

            // ① 撤回通知必须在改状态之前取（判据是 submit_time IS NOT NULL）
            List<Map<String, Object>> submitted = List.of();
            try {
                submitted = pushService.submittedRaters(rt.getTaskId());
            } catch (Exception e) {
                log.warn("取已提交评价人失败 taskId={}: {}", rt.getTaskId(), e.getMessage());
            }

            // ② 数据回滚：评级删除 → 页面归零 → 评价人作废（token 随之失效）
            jdbc.update("DELETE FROM uf_pa_rating WHERE sheet_id = ?", rt.getSheetId());
            jdbc.update("UPDATE uf_pa_sheet_item SET leader_score = NULL, rater360_score = NULL, " +
                    "self_score = NULL, item_score = NULL, item_level_name = NULL, rater360_count = 0" +
                    " WHERE sheet_id = ?", rt.getSheetId());
            // 封存字段一并清掉（sql/64）：撤回后页面回到「没评过」，
            // 留着旧的 seal_status=1 会让它挂着一份已作废的快照继续对外可见
            jdbc.update("UPDATE uf_pa_sheet SET status = ?, leader_score = NULL, rater360_score = NULL, " +
                            "self_score = NULL, total_score = NULL, band_name = NULL, comment_text = NULL, " +
                            "snapshot_json = NULL, seal_status = 0, seal_time = NULL, seal_by_loginid = NULL, " +
                            "seal_by_name = NULL, sealed_json = NULL, update_time = SYSDATETIME() WHERE id = ?",
                    SHEET_INIT, rt.getSheetId());
            jdbc.update("UPDATE uf_assess_task_rater SET status = ?, draft_json = NULL WHERE task_id = ? AND status <> ?",
                    RATER_VOIDED, rt.getTaskId(), RATER_VOIDED);
            jdbc.update("UPDATE uf_assess_task SET status = ?, void_by = 0, void_time = SYSDATETIME(), " +
                            "void_reason = ?, update_time = SYSDATETIME() WHERE id = ?",
                    TASK_VOIDED, truncate(reason, 400), rt.getTaskId());
            result.getVoidedTaskIds().add(rt.getTaskId());

            // ③ 通知已提交过的评价人（待评者不受影响：他们会收到新邀请）
            if (!submitted.isEmpty()) {
                try {
                    int written = pushService.writeVoids(rt.getTaskId(), rt.getResourceName(),
                            rt.getYear(), reason, submitted);
                    result.setVoidNoticeWritten(result.getVoidNoticeWritten() + written);
                } catch (Exception e) {
                    log.warn("撤回通知写入失败 taskId={}: {}", rt.getTaskId(), e.getMessage());
                    result.getWarnings().add("「" + rt.getResourceName() + "」的撤回通知未发出（"
                            + e.getMessage() + "），可到任务详情页补发。");
                }
            }
            log.info("导入撤回进行中任务 taskId={}, sheetId={}, 作废评价人 {} 位, 通知 {} 位",
                    rt.getTaskId(), rt.getSheetId(), rt.getTotalRaters(), submitted.size());

            // ④ 按原参数重新发起
            if (plan.isRelaunchRunning()) {
                relaunch(rt, head, result);
            }
        }
    }

    /**
     * 按原参数重发。
     *
     * <p>360 名单从旧任务的 {@code uf_assess_task_rater} 里读回来（去掉 SELF / LEADER 两种固定角色）。
     * 直接读任务行不行 —— 任务表只存了 360 的<b>人数</b>快照，没存名单。</p>
     */
    private void relaunch(PaImportPreview.RunningTask rt, Map<String, Object> head, Result result) {
        try {
            List<Long> rater360 = new ArrayList<>();
            for (Long id : jdbc.queryForList(
                    "SELECT rater_resource_id FROM uf_assess_task_rater WHERE task_id = ? AND rater_type = 'RATER360'",
                    Long.class, rt.getTaskId())) {
                if (id != null) {
                    rater360.add(id);
                }
            }
            PaTaskDTO.CreateRequest req = new PaTaskDTO.CreateRequest();
            req.setYear(rt.getYear());
            req.setResourceId(num(head.get("resource_id")));
            req.setPostId(num(head.get("post_id")));
            req.setRater360ResourceIds(rater360);
            req.setRemark("能力模型导入后自动重新发起（原任务 #" + rt.getTaskId() + "）");

            PaTaskVO.CreateResult created = taskService.create(req);
            result.getRelaunchedTaskIds().add(created.getTaskId());

            // 回填「被谁替代」：撤回 + 重发之后，旧任务上没有这条线索就查不出前后关系
            // （旧任务状态 4、新任务状态 0，两条行除了备注看不出是一回事）。
            // 这一步失败不影响重发本身，记日志即可。
            try {
                jdbc.update("UPDATE uf_assess_task SET replaced_by_task_id = ? WHERE id = ?",
                        created.getTaskId(), rt.getTaskId());
            } catch (Exception e) {
                log.warn("回填 replaced_by_task_id 失败 taskId={}: {}", rt.getTaskId(), e.getMessage());
            }
            log.info("撤回后重新发起 taskId={} → 新 taskId={}", rt.getTaskId(), created.getTaskId());
        } catch (Exception e) {
            log.warn("重新发起失败 taskId={}: {}", rt.getTaskId(), e.getMessage());
            result.getWarnings().add("「" + rt.getResourceName() + "」的测评已撤回，但重新发起失败："
                    + e.getMessage() + "。请到「测评任务」页手工重新发起。");
        }
    }

    /** 任务头：被测评人、岗位、年度（重发用） */
    private Map<String, Object> loadTaskHead(Long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.resource_id, s.post_id, p.year FROM uf_assess_task t" +
                        " JOIN uf_pa_sheet s ON s.id = t.sheet_id" +
                        " JOIN uf_assess_period p ON p.id = t.period_id" +
                        " WHERE t.id = ?", taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ================================================================ 工具

    /**
     * 部门 id → 部门名快照。
     *
     * <p><b>null 是合法输入</b>，代表全公司通用岗位（sql/63）：返回 null，
     * 岗位的 department_id / department_name 都写 NULL。早先这里对 null 直接抛
     * 「部门不能为空」，等于把通用岗位这条路径堵死。</p>
     */
    private String resolveDepartmentName(Long departmentId) {
        if (departmentId == null) {
            return null;
        }
        List<String> names = jdbc.queryForList(
                "SELECT departmentname FROM HrmDepartment WHERE id = ?", String.class, departmentId);
        if (names.isEmpty()) {
            throw new BusinessException("部门不存在（id=" + departmentId + "），可能在泛微里已被删除");
        }
        return names.get(0);
    }

    private PaImportDraft.PostDraft findDraft(PaImportDraft draft, String sheetName) {
        for (PaImportDraft.PostDraft p : draft.getPosts()) {
            if (Objects.equals(p.getSheetName(), sheetName)) {
                return p;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 分值匹配键：去掉末尾零避免 1 与 1.00 因 scale 不同匹配失败。
     *  setScale(0) 是为了让 1E+1 这类负 scale 变回 10，否则它也当 key 用不相等。 */
    private static BigDecimal scoreKey(BigDecimal score) {
        BigDecimal stripped = score.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ================================================================ 结果

    /** 执行结果：逐岗位决策 + 测评影响处理计数 */
    @lombok.Data
    public static class Result {
        private List<String> created = new ArrayList<>();
        private List<String> overwritten = new ArrayList<>();
        private List<String> skipped = new ArrayList<>();
        private List<Long> postIds = new ArrayList<>();

        private int categoryCreated;
        private int categoryUpdated;
        private int itemCreated;
        private int itemUpdated;
        private int itemDisabled;
        private int itemRemoved;
        private int levelCreated;
        private int levelUpdated;
        private int levelKept;
        private int levelRemoved;
        private int bandCreated;

        private List<Long> voidedTaskIds = new ArrayList<>();
        /** 撤回后按原参数重新发起成功的新任务 id */
        private List<Long> relaunchedTaskIds = new ArrayList<>();
        /** 撤回通知实际写出的条数 */
        private int voidNoticeWritten;
        private List<Long> recalculatedSheetIds = new ArrayList<>();

        private List<String> warnings = new ArrayList<>();
        private LocalDateTime finishedAt;

        void addPostId(Long id) {
            if (id != null) {
                postIds.add(id);
            }
        }

        void incrCategoryCreated() {
            categoryCreated++;
        }

        void incrCategoryUpdated() {
            categoryUpdated++;
        }

        void incrItemCreated() {
            itemCreated++;
        }

        void incrItemUpdated() {
            itemUpdated++;
        }

        void incrItemDisabled() {
            itemDisabled++;
        }

        void incrItemRemoved() {
            itemRemoved++;
        }

        void incrLevelCreated() {
            levelCreated++;
        }

        void incrLevelUpdated() {
            levelUpdated++;
        }

        void incrLevelKept() {
            levelKept++;
        }

        void incrLevelRemoved() {
            levelRemoved++;
        }

        void incrBandCreated() {
            bandCreated++;
        }
    }
}
