package com.company.ability.service.impl;

import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AbilityStructureDTO;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.entity.PositionAbilityReq;
import com.company.ability.entity.PositionAbilityReqItem;
import com.company.ability.entity.UserAbilityReqItem;
import com.company.ability.entity.UserAbilityReqNew;
import com.company.ability.exception.BusinessException;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.service.AbilityElementLevelService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.AbilityStructureImportService;
import com.company.ability.service.AssessSheetSyncService;
import com.company.ability.service.PositionAbilityReqItemService;
import com.company.ability.service.PositionAbilityReqService;
import com.company.ability.service.UserAbilityReqItemService;
import com.company.ability.service.UserAbilityReqNewService;
import com.company.ability.vo.AbilityStructureVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力结构导入实现（方案 14 §四~§六）。
 *
 * <p>三条硬规则（来自两次实盘事故）：
 * ① 同名类别/要素一律跳过，不覆盖；
 * ② 等级分值只从 uf_assess_level_score 取，绝不接受载荷里的分值列；
 * ③ 名称不做模糊硬配：精确匹配、别名表命中，其余转 PENDING，绝不猜。
 */
@Slf4j
@Service
public class AbilityStructureImportServiceImpl implements AbilityStructureImportService {

    private static final String S_CREATE = "CREATE";
    private static final String S_SKIP = "SKIP";
    private static final String S_PENDING = "PENDING";
    private static final String S_BLOCK = "BLOCK";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcTemplate jdbc;
    private final AbilityCategoryService categoryService;
    private final AbilityElementService elementService;
    private final AbilityElementLevelService levelService;
    private final PositionAbilityReqService positionService;
    private final PositionAbilityReqItemService positionItemService;
    private final UserAbilityReqNewService userReqService;
    private final UserAbilityReqItemService userItemService;
    private final AssessSheetSyncService sheetSyncService;
    private final org.springframework.context.ApplicationContext applicationContext;

    private final long companyId;

    public AbilityStructureImportServiceImpl(JdbcTemplate jdbc,
                                             AbilityCategoryService categoryService,
                                             AbilityElementService elementService,
                                             AbilityElementLevelService levelService,
                                             PositionAbilityReqService positionService,
                                             PositionAbilityReqItemService positionItemService,
                                             UserAbilityReqNewService userReqService,
                                             UserAbilityReqItemService userItemService,
                                             AssessSheetSyncService sheetSyncService,
                                             org.springframework.context.ApplicationContext applicationContext,
                                             @Value("${assess.company-id:1}") long companyId) {
        this.jdbc = jdbc;
        this.categoryService = categoryService;
        this.elementService = elementService;
        this.levelService = levelService;
        this.positionService = positionService;
        this.positionItemService = positionItemService;
        this.userReqService = userReqService;
        this.userItemService = userItemService;
        this.sheetSyncService = sheetSyncService;
        this.applicationContext = applicationContext;
        this.companyId = companyId;
    }

    // ==================== public ====================

    @Override
    public void requireSingleDimension(AbilityStructureDTO dto) {
        Long current = DimensionContext.getOrDefault();
        if (dto.getDimensionId() != null && !dto.getDimensionId().equals(current)) {
            throw new BusinessException(409, "交付维度（" + dto.getDimensionId() + "）与当前维度（" + current
                    + "）不一致——导入必须在操作人当前维度内进行");
        }
    }

    @Override
    public AbilityStructureVO.PreviewResult preview(AbilityStructureDTO dto, String sessionId) {
        requireSingleDimension(dto);
        return analyzeAs(dto, DimensionContext.getOrDefault());
    }

    @Override
    public AbilityStructureVO.CommitResult commit(AbilityStructureDTO dto, String sessionId, Long batchId) {
        requireSingleDimension(dto);
        long dimensionId = DimensionContext.getOrDefault();
        AbilityStructureVO.PreviewResult preview = analyzeAs(dto, dimensionId);
        if (!preview.isCommitable()) {
            throw new BusinessException(4000, "存在阻断项，不能写入：" + firstBlockMessage(preview));
        }
        Plan plan = analyzePlan(dto, dimensionId);

        // 1) 结构数据一个事务（经代理调用，保证 @Transactional 生效）
        AbilityStructureImportServiceImpl self =
                applicationContext.getBean(AbilityStructureImportServiceImpl.class);
        List<AbilityStructureImportServiceImpl.ResolvedUserRef> users = self.commitStructure(plan, dimensionId, batchId);

        // 2) 汇总同步：事务外逐人，失败点名不阻断他人
        List<AbilityStructureVO.SyncFailure> failures = plan.year == null
                ? List.of() : syncSheets(users, plan.year);

        AbilityStructureVO.CommitResult result = new AbilityStructureVO.CommitResult();
        result.setCounts(preview.getCounts());
        result.setBatchId(batchId);
        result.setSkipped(preview.getLines().stream()
                .filter(l -> S_SKIP.equals(l.getStatus()) || S_PENDING.equals(l.getStatus()))
                .toList());
        result.setFailed(failures);
        return result;
    }

    /** 结构写入事务体（由 commit 调用，单事务） */
    @Transactional(rollbackFor = Exception.class)
    public List<AbilityStructureImportServiceImpl.ResolvedUserRef> commitStructure(
            Plan plan, long dimensionId, Long batchId) {
        Map<String, Long> categoryIds = writeCategories(plan, dimensionId, batchId);
        Map<String, Long> elementIds = writeElements(plan, categoryIds, dimensionId, batchId);
        writePositionReqs(plan, categoryIds, elementIds, dimensionId, batchId);
        return writeUserReqs(plan, elementIds, dimensionId, batchId);
    }

    // ==================== 分析（preview / commit 同一套规则） ====================

    /** 中间计划：preview 与 commit 共用，避免两次走不同口径 */
    private static class Plan {
        List<AbilityStructureDTO.CategoryRow> newCategories = new ArrayList<>();
        Map<String, Long> existingCategories = new LinkedHashMap<>();
        List<AbilityStructureDTO.ElementRow> newElements = new ArrayList<>();
        Map<String, Long> existingElements = new LinkedHashMap<>();
        List<ResolvedPosition> positions = new ArrayList<>();
        List<ResolvedUser> users = new ArrayList<>();
        Set<String> systemElementNames = new HashSet<>();
        List<AbilityStructureVO.Line> lines = new ArrayList<>();
        AbilityStructureVO.Counts counts = new AbilityStructureVO.Counts();
        Integer year;
        Long periodId;
        boolean blocked;
    }

    private record ResolvedPosition(AbilityStructureDTO.PositionReqRow row, long departmentId, long jobTitleId,
                                    Long existingReqId, boolean overwrite) {
    }

    private record ResolvedUser(AbilityStructureDTO.UserReqRow row, Long resourceId, String resourceName,
                                String message) {
    }

    private Plan analyzePlan(AbilityStructureDTO dto, long dimensionId) {
        // 岗位/人员要求可能引用系统已有要素，必须先把全部要素名装进计划
        Plan plan = new Plan();

        analyzeCategories(dto, plan, dimensionId);
        analyzeElements(dto, plan, dimensionId);
        analyzePositions(dto, plan, dimensionId);
        analyzeUsers(dto, plan, dimensionId);

        resolutionYear(dto, plan, dimensionId);
        return plan;
    }

    private void analyzeCategories(AbilityStructureDTO dto, Plan plan, long dimensionId) {
        Set<String> declared = new HashSet<>();
        for (AbilityStructureDTO.CategoryRow row : safe(dto.getCategories())) {
            String name = trim(row.getName());
            if (name.isEmpty()) {
                block(plan, "CATEGORY", "", "类别名称为空");
                continue;
            }
            if (!declared.add(name)) {
                block(plan, "CATEGORY", name, "同一稿内类别重复");
                continue;
            }
            if (plan.existingCategories.containsKey(name)) {
                continue;
            }
            Long existing = findCategoryId(name, dimensionId);
            if (existing != null) {
                plan.existingCategories.put(name, existing);
                line(plan, "CATEGORY", name, S_SKIP, "已存在，将跳过");
                plan.counts.setCategorySkip(plan.counts.getCategorySkip() + 1);
            } else {
                plan.newCategories.add(row);
                line(plan, "CATEGORY", name, S_CREATE, "新增类别");
                plan.counts.setCategoryCreate(plan.counts.getCategoryCreate() + 1);
            }
        }
    }

    private void analyzeElements(AbilityStructureDTO dto, Plan plan, long dimensionId) {
        Set<String> existingNames = loadElementNames(dimensionId);
        plan.systemElementNames.addAll(existingNames);
        Set<Integer> configuredLevels = loadConfiguredLevels(dimensionId);
        Map<String, Integer> levelScore = loadLevelScore(dimensionId);
        Set<String> declared = new HashSet<>();

        for (AbilityStructureDTO.ElementRow row : safe(dto.getElements())) {
            String name = trim(row.getName());
            String categoryName = trim(row.getCategoryName());
            if (name.isEmpty()) {
                block(plan, "ELEMENT", "", "要素名称为空");
                continue;
            }
            if (!declared.add(name)) {
                block(plan, "ELEMENT", name, "同一稿内要素重复");
                continue;
            }
            boolean categoryKnown = plan.existingCategories.containsKey(categoryName)
                    || plan.newCategories.stream().anyMatch(c -> trim(c.getName()).equals(categoryName));
            if (!categoryKnown) {
                block(plan, "ELEMENT", name, "所属类别未在稿件中声明：" + categoryName);
                continue;
            }
            if (existingNames.contains(name)) {
                Long existingId = findElementId(name, dimensionId);
                if (existingId != null) {
                    plan.existingElements.put(name, existingId);
                }
                line(plan, "ELEMENT", name, S_SKIP, "已存在，将跳过（不覆盖等级描述）");
                plan.counts.setElementSkip(plan.counts.getElementSkip() + 1);
                continue;
            }
            if (!validateLevels(row, plan, configuredLevels, levelScore)) {
                continue;
            }
            plan.newElements.add(row);
            line(plan, "ELEMENT", name, S_CREATE, "新增要素，" + safe(row.getLevels()).size() + " 个等级");
            plan.counts.setElementCreate(plan.counts.getElementCreate() + 1);
            plan.counts.setLevelCreate(plan.counts.getLevelCreate() + safe(row.getLevels()).size());
        }
    }

    /** 等级描述写入 level_requirement，分值来自等级分值配置；任何一条不合格则整个要素阻断 */
    private boolean validateLevels(AbilityStructureDTO.ElementRow row, Plan plan,
                                   Set<Integer> configuredLevels, Map<String, Integer> levelScore) {
        List<AbilityStructureDTO.LevelRow> levels = safe(row.getLevels());
        if (levels.isEmpty()) {
            block(plan, "ELEMENT", trim(row.getName()), "缺少等级描述（至少 1 条）");
            return false;
        }
        if (configuredLevels.isEmpty()) {
            block(plan, "ELEMENT", trim(row.getName()), "当前维度未配置等级分值（能力测评配置-等级分值）");
            return false;
        }
        Set<Integer> seen = new HashSet<>();
        for (AbilityStructureDTO.LevelRow lv : levels) {
            Integer no = lv.getLevelNo();
            if (no == null || !seen.add(no)) {
                block(plan, "LEVEL", trim(row.getName()), "等级号缺失或重复：" + no);
                return false;
            }
            if (!configuredLevels.contains(no)) {
                block(plan, "LEVEL", trim(row.getName()), "等级号 " + no + " 不在当前维度等级分值配置中");
                return false;
            }
            if (trim(lv.getDesc()).isEmpty()) {
                block(plan, "LEVEL", trim(row.getName()), "等级 " + no + " 描述为空");
                return false;
            }
            Integer expect = levelScore.get(String.valueOf(no));
            if (expect != null && trim(lv.getDesc()).equals(String.valueOf(expect))) {
                // 描述写成等级号/分值（第一次导入列偏移事故）→ 明确挡回，避免脏数据
                block(plan, "LEVEL", trim(row.getName()), "等级 " + no + " 描述疑似取到了分值列：" + expect);
                return false;
            }
        }
        return true;
    }

    private void analyzePositions(AbilityStructureDTO dto, Plan plan, long dimensionId) {
        Set<String> knownElements = knownElementNames(plan);
        for (AbilityStructureDTO.PositionReqRow row : safe(dto.getPositionReqs())) {
            String deptName = trim(row.getDepartmentName());
            String jobName = trim(row.getJobTitleName());
            String label = deptName + " · " + jobName;
            if (deptName.isEmpty() || jobName.isEmpty()) {
                block(plan, "POSITION", label, "部门或岗位为空");
                continue;
            }
            Long deptId = findDepartmentId(deptName, dimensionId);
            Long jobId = findJobTitleId(jobName, dimensionId);
            if (deptId == null || jobId == null) {
                // 「未匹配」与「同名多名」要分开说：前者要人指定，后者要人在候选里挑一个，
                // 混成一句「未匹配」会让用户去改文件名，改完还是匹配不上
                String why;
                if (deptId == null) {
                    why = countDepartmentName(deptName) > 1
                            ? "部门同名多个，需指定：" + deptName : "部门未匹配：" + deptName;
                } else {
                    why = countJobTitleName(jobName) > 1
                            ? "岗位同名多个，需指定：" + jobName : "岗位未匹配：" + jobName;
                }
                pending(plan, "POSITION", label, why);
                continue;
            }
            if (!validatePositionItems(row, plan, label, knownElements)) {
                continue;
            }
            Long existing = findPositionReqId(deptId, jobId, dimensionId);
            boolean overwrite = Boolean.TRUE.equals(row.getOverwrite());
            if (existing != null && !overwrite) {
                line(plan, "POSITION", label, S_SKIP, "已存在岗位配置，将保留（需覆盖请显式勾选）");
                plan.counts.setPositionKeep(plan.counts.getPositionKeep() + 1);
                continue;
            }
            plan.positions.add(new ResolvedPosition(row, deptId, jobId, existing, overwrite));
            if (existing != null) {
                line(plan, "POSITION", label, S_CREATE, "覆盖已有岗位配置");
                plan.counts.setPositionOverwrite(plan.counts.getPositionOverwrite() + 1);
            } else {
                line(plan, "POSITION", label, S_CREATE, "新增岗位配置，" + safe(row.getItems()).size() + " 条要素");
                plan.counts.setPositionCreate(plan.counts.getPositionCreate() + 1);
            }
        }
    }

    private boolean validatePositionItems(AbilityStructureDTO.PositionReqRow row, Plan plan, String label,
                                          Set<String> knownElements) {
        List<AbilityStructureDTO.PositionItem> items = safe(row.getItems());
        if (items.isEmpty()) {
            block(plan, "POSITION", label, "能力要素列表为空");
            return false;
        }
        Set<String> seen = new HashSet<>();
        Map<String, BigDecimal> categoryWeight = new HashMap<>();
        for (AbilityStructureDTO.PositionItem item : items) {
            String elementName = trim(item.getElementName());
            String categoryName = trim(item.getCategoryName());
            if (elementName.isEmpty() || categoryName.isEmpty()) {
                block(plan, "POSITION", label, "要素或类别为空");
                return false;
            }
            if (!seen.add(elementName)) {
                block(plan, "POSITION", label, "同一要素重复配置：" + elementName);
                return false;
            }
            if (!knownElements.contains(elementName)) {
                pending(plan, "POSITION", label, "要素未在稿件或系统中声明：" + elementName);
                return false;
            }
            BigDecimal weight = item.getWeight();
            if (weight == null) {
                block(plan, "POSITION", label, "类别权重为空：" + categoryName);
                return false;
            }
            if (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(HUNDRED) > 0) {
                block(plan, "POSITION", label, "类别权重必须在 0~100 之间：" + categoryName);
                return false;
            }
            BigDecimal existing = categoryWeight.putIfAbsent(categoryName, weight);
            if (existing != null && existing.compareTo(weight) != 0) {
                block(plan, "POSITION", label, "同一类别下要素权重必须一致：" + categoryName);
                return false;
            }
        }
        return true;
    }

    private void analyzeUsers(AbilityStructureDTO dto, Plan plan, long dimensionId) {
        Set<String> knownElements = knownElementNames(plan);
        for (AbilityStructureDTO.UserReqRow row : safe(dto.getUserReqs())) {
            String name = trim(row.getResourceName());
            String no = trim(row.getResourceNo());
            String label = name + (no.isEmpty() ? "" : "（" + no + "）");
            if (!validateUserItems(row, plan, label, knownElements)) {
                continue;
            }
            ResolvedUser resolved = resolveResource(row, dimensionId);
            if (resolved.resourceId() == null) {
                pending(plan, "USER", label, resolved.message());
                plan.counts.setUserPending(plan.counts.getUserPending() + 1);
                continue;
            }
            plan.users.add(new ResolvedUser(row, resolved.resourceId(), resolved.resourceName(), resolved.message()));
            line(plan, "USER", trim(resolved.resourceName()), S_CREATE,
                    resolved.message() == null ? "新增人员要求" : resolved.message());
            plan.counts.setUserCreate(plan.counts.getUserCreate() + 1);
        }
    }

    private boolean validateUserItems(AbilityStructureDTO.UserReqRow row, Plan plan, String label,
                                      Set<String> knownElements) {
        List<AbilityStructureDTO.UserItem> items = safe(row.getItems());
        if (items.isEmpty()) {
            block(plan, "USER", label, "要素列表为空");
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (AbilityStructureDTO.UserItem item : items) {
            String elementName = trim(item.getElementName());
            if (elementName.isEmpty()) {
                block(plan, "USER", label, "要素名称为空");
                return false;
            }
            if (!seen.add(elementName)) {
                block(plan, "USER", label, "同一要素重复配置：" + elementName);
                return false;
            }
            if (!knownElements.contains(elementName)) {
                pending(plan, "USER", label, "要素未在稿件或系统中声明：" + elementName);
                return false;
            }
            if (item.getLevelNo() == null) {
                block(plan, "USER", label, "要素缺少要求等级：" + elementName);
                return false;
            }
        }
        return true;
    }

    /** 年度与测评期（勾选汇总同步时必须有开启中的年度） */
    private void resolutionYear(AbilityStructureDTO dto, Plan plan, long dimensionId) {
        if (safe(dto.getUserReqs()).isEmpty()) {
            return;
        }
        int year = dto.getYear() == null ? java.time.LocalDate.now().getYear() : dto.getYear();
        plan.year = year;
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id, status FROM uf_assess_period WHERE company_id = ? AND year = ? AND dimension_id = ?",
                companyId, year, dimensionId);
        if (periods.isEmpty()) {
            block(plan, "USER", String.valueOf(year), year + " 年度不存在，请先在年度设置中新增");
            return;
        }
        if (num(periods.get(0).get("status")) == 1) {
            block(plan, "USER", String.valueOf(year), year + " 年度已关闭，人员能力要求不允许写入");
            return;
        }
        plan.periodId = num(periods.get(0).get("id"));
    }

    private AbilityStructureVO.PreviewResult analyzeAs(AbilityStructureDTO dto, long dimensionId) {
        Plan plan = analyzePlan(dto, dimensionId);
        AbilityStructureVO.PreviewResult result = new AbilityStructureVO.PreviewResult();
        result.setLines(plan.lines);
        result.setCounts(plan.counts);
        result.setCommitable(!plan.blocked);
        result.setYear(plan.year);
        result.setPeriodId(plan.periodId);
        return result;
    }

    // ==================== 写入 ====================

    private Map<String, Long> writeCategories(Plan plan, long dimensionId, Long batchId) {
        Map<String, Long> ids = new LinkedHashMap<>(plan.existingCategories);
        for (AbilityStructureDTO.CategoryRow row : plan.newCategories) {
            AbilityCategory c = new AbilityCategory();
            c.setCategoryName(trim(row.getName()));
            c.setDescription(row.getDescription());
            c.setSort(row.getSort());
            c.setStatus(1);
            c.setDimensionId(dimensionId);
            categoryService.save(c);
            ids.put(trim(row.getName()), c.getId());
            batchItem(batchId, "CATEGORY", c.getId(), trim(row.getName()), "CREATE");
        }
        return ids;
    }

    private Map<String, Long> writeElements(Plan plan, Map<String, Long> categoryIds, long dimensionId, Long batchId) {
        Map<String, Long> ids = new LinkedHashMap<>(plan.existingElements);
        Map<String, String> levelNames = loadLevelNames(dimensionId);
        Map<String, BigDecimal> levelScores = loadLevelScoreValues(dimensionId);
        for (AbilityStructureDTO.ElementRow row : plan.newElements) {
            String name = trim(row.getName());
            AbilityElement e = new AbilityElement();
            e.setElementName(name);
            e.setCategoryId(categoryIds.get(trim(row.getCategoryName())));
            e.setDescription(row.getDescription());
            e.setAssessInFlow(row.getAssessInFlow() == null ? 1 : row.getAssessInFlow());
            e.setAllowClientAssess(row.getAllowClientAssess() == null ? 0 : row.getAllowClientAssess());
            e.setStatus(1);
            elementService.save(e);
            ids.put(name, e.getId());
            batchItem(batchId, "ELEMENT", e.getId(), name, "CREATE");

            for (AbilityStructureDTO.LevelRow lv : safe(row.getLevels())) {
                AbilityElementLevel level = new AbilityElementLevel();
                level.setElementId(e.getId());
                level.setLevel(lv.getLevelNo());
                level.setLevelName(trim(lv.getLevelName()).isEmpty()
                        ? levelNames.getOrDefault(String.valueOf(lv.getLevelNo()),
                            String.valueOf(lv.getLevelNo()))
                        : trim(lv.getLevelName()));
                // 描述只进 level_requirement（绝不放入载荷里的分值）；score 取等级分值配置，与全系统同口径
                level.setLevelRequirement(trim(lv.getDesc()));
                level.setScore(levelScores.get(String.valueOf(lv.getLevelNo())));
                levelService.save(level);
                batchItem(batchId, "LEVEL", level.getId(), name + " L" + lv.getLevelNo(), "CREATE");
            }
        }
        return ids;
    }

    private void writePositionReqs(Plan plan, Map<String, Long> categoryIds, Map<String, Long> elementIds,
                                   long dimensionId, Long batchId) {
        for (ResolvedPosition p : plan.positions) {
            long reqId;
            if (p.existingReqId() != null) {
                reqId = p.existingReqId();
                positionItemService.remove(new LambdaQueryWrapper<PositionAbilityReqItem>()
                        .eq(PositionAbilityReqItem::getReqId, reqId));
                batchItem(batchId, "POSITION_REQ", reqId, label(p.row()), "UPDATE");
            } else {
                PositionAbilityReq req = new PositionAbilityReq();
                req.setDepartmentId(p.departmentId());
                req.setJobTitleId(p.jobTitleId());
                req.setDescription(p.row().getDescription());
                positionService.save(req);
                reqId = req.getId();
                batchItem(batchId, "POSITION_REQ", reqId, label(p.row()), "CREATE");
            }
            for (AbilityStructureDTO.PositionItem item : safe(p.row().getItems())) {
                String elementName = trim(item.getElementName());
                Long elementId = elementIds.get(elementName);
                if (elementId == null) {
                    elementId = findElementId(elementName, dimensionId);
                }
                Long categoryId = categoryIds.get(trim(item.getCategoryName()));
                if (categoryId == null) {
                    categoryId = findCategoryId(trim(item.getCategoryName()), dimensionId);
                }
                PositionAbilityReqItem entity = new PositionAbilityReqItem();
                entity.setReqId(reqId);
                entity.setCategoryId(categoryId);
                entity.setElementId(elementId);
                entity.setWeight(item.getWeight());
                positionItemService.save(entity);
            }
        }
    }

    private List<AbilityStructureImportServiceImpl.ResolvedUserRef> writeUserReqs(
            Plan plan, Map<String, Long> elementIds, long dimensionId, Long batchId) {
        List<AbilityStructureImportServiceImpl.ResolvedUserRef> resources = new ArrayList<>();
        if (plan.users.isEmpty()) {
            return resources;
        }
        int year = plan.year;
        Set<Integer> configured = loadConfiguredLevels(dimensionId);
        for (ResolvedUser u : plan.users) {
            long resourceId = u.resourceId();
            // 同一人同年度只保留一份要求：已存在则替换明细（幂等重试）
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id FROM uf_user_ability_req_new WHERE resource_id = ? AND assess_year = ? AND dimension_id = ?",
                    resourceId, year, dimensionId);
            long reqId;
            if (existing.isEmpty()) {
                UserAbilityReqNew req = new UserAbilityReqNew();
                req.setResourceId(resourceId);
                req.setAssessYear(year);
                req.setDescription(u.row().getDescription());
                req.setDimensionId(dimensionId);
                userReqService.save(req);
                reqId = req.getId();
                batchItem(batchId, "USER_REQ", reqId, u.resourceName(), "CREATE");
            } else {
                reqId = num(existing.get(0).get("id"));
                userItemService.remove(new LambdaQueryWrapper<UserAbilityReqItem>()
                        .eq(UserAbilityReqItem::getReqId, reqId));
                batchItem(batchId, "USER_REQ", reqId, u.resourceName(), "UPDATE");
            }
            for (AbilityStructureDTO.UserItem item : safe(u.row().getItems())) {
                Integer levelNo = item.getLevelNo();
                if (!configured.contains(levelNo)) {
                    throw new BusinessException(4001, "要素 " + trim(item.getElementName())
                            + " 的要求等级无效：" + levelNo);
                }
                String elementName = trim(item.getElementName());
                Long elementId = elementIds.get(elementName);
                if (elementId == null) {
                    elementId = findElementId(elementName, dimensionId);
                }
                if (elementId == null) {
                    throw new BusinessException(4002, "要素未在稿件或系统中找到：" + elementName);
                }
                UserAbilityReqItem entity = new UserAbilityReqItem();
                entity.setReqId(reqId);
                entity.setElementId(elementId);
                entity.setCategoryId(categoryIdOf(elementId));
                entity.setLevelId(resolveLevelId(elementId, levelNo));
                entity.setScore(item.getScore());
                userItemService.save(entity);
            }
            resources.add(new AbilityStructureImportServiceImpl.ResolvedUserRef(resourceId, u.resourceName()));
        }
        return resources;
    }

    /**
     * 汇总同步：由调用方在结构事务提交后逐人调用（方案 14 §六），失败点名不阻断他人。
     */
    public List<AbilityStructureVO.SyncFailure> syncSheets(List<ResolvedUserRef> users, int year) {
        List<AbilityStructureVO.SyncFailure> failures = new ArrayList<>();
        for (ResolvedUserRef u : users) {
            try {
                sheetSyncService.syncRequirement(u.resourceId(), year, true);
            } catch (Exception e) {
                AbilityStructureVO.SyncFailure f = new AbilityStructureVO.SyncFailure();
                f.setResourceId(u.resourceId());
                f.setResourceName(u.resourceName());
                f.setMessage(e.getMessage());
                failures.add(f);
                log.warn("测评汇总同步失败，resourceId={}：{}", u.resourceId(), e.getMessage());
            }
        }
        return failures;
    }

    public record ResolvedUserRef(long resourceId, String resourceName) {
    }

    // ==================== 查询辅助 ====================

    private Long findCategoryId(String name, long dimensionId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM uf_ability_category WHERE category_name = ? AND dimension_id = ? AND deleted = 0",
                Long.class, name, dimensionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Set<String> loadElementNames(long dimensionId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT element_name FROM uf_ability_element WHERE dimension_id = ? AND deleted = 0",
                String.class, dimensionId));
    }

    private Set<Integer> loadConfiguredLevels(long dimensionId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT level_no FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                Integer.class, companyId, dimensionId));
    }

    /** level_no → 等级分值（仅用于列偏移兜底检查：描述若等于分值即视为取错列） */
    private Map<String, Integer> loadLevelScore(long dimensionId) {
        Map<String, Integer> map = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT level_no, score FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            map.put(String.valueOf(num(row.get("level_no"))), (int) Math.round(
                    ((Number) row.get("score")).doubleValue()));
        }
        return map;
    }

    /** level_no → 等级分值（写入 uf_ability_element_level.score，与等级分值配置保持一致） */
    private Map<String, BigDecimal> loadLevelScoreValues(long dimensionId) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT level_no, score FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            Object score = row.get("score");
            map.put(String.valueOf(num(row.get("level_no"))),
                    score == null ? BigDecimal.ZERO : new BigDecimal(score.toString()));
        }
        return map;
    }

    /** level_no → 等级名称（缺省 level_name 的来源） */
    private Map<String, String> loadLevelNames(long dimensionId) {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT level_no, level_desc FROM uf_assess_level_score WHERE company_id = ? AND dimension_id = ?",
                companyId, dimensionId)) {
            Object desc = row.get("level_desc");
            if (desc != null) {
                map.put(String.valueOf(num(row.get("level_no"))), String.valueOf(desc));
            }
        }
        return map;
    }


    private Long findDepartmentId(String name, long dimensionId) {
        Long viaAlias = findAliasId("DEPARTMENT", name, dimensionId);
        if (viaAlias != null) {
            return viaAlias;
        }
        // canceled 在泛微库里多数是 NULL（未停用），写成 = '0' 会把正常部门全部漏掉——
        // 与 EcologyDataController 统一口径 ISNULL(canceled, 0) = 0
        // 同名部门大量存在（多个子公司各有「安环部」）：只认唯一命中，多条一律交给别名确认，
        // 按 id 排序取第一条会静默把要求挂到别的公司的同名部门上
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM HrmDepartment WHERE (departmentmark = ? OR departmentname = ?)"
                        + " AND ISNULL(canceled, '0') = '0' ORDER BY id",
                Long.class, name, name);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    private Long findJobTitleId(String name, long dimensionId) {
        Long viaAlias = findAliasId("JOB_TITLE", name, dimensionId);
        if (viaAlias != null) {
            return viaAlias;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM HrmJobTitles WHERE (jobtitlemark = ? OR jobtitlename = ?)"
                        + " AND ISNULL(canceled, '0') = '0' ORDER BY id",
                Long.class, name, name);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    /** 同名部门数量：用于把「未匹配」与「同名歧义」区分开（两者都需要人确认，但处置方式不同） */
    private int countDepartmentName(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM HrmDepartment WHERE (departmentmark = ? OR departmentname = ?)"
                        + " AND ISNULL(canceled, '0') = '0'", Integer.class, name, name);
        return n == null ? 0 : n;
    }

    private int countJobTitleName(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM HrmJobTitles WHERE (jobtitlemark = ? OR jobtitlename = ?)"
                        + " AND ISNULL(canceled, '0') = '0'", Integer.class, name, name);
        return n == null ? 0 : n;
    }

    /**
     * 别名表命中（方案 14 §三）：文件里的部门/岗位写法与系统不一致时，人工确认过一次即固化，
     * 下次同写法自动匹配——这是"名称不硬配"规则唯一的合法例外（有历史确认依据，不是猜）。
     * 命中即累加 hit_count 供审计；表缺失（sql/33 未应用）按未命中处理，不影响导入。
     */
    private Long findAliasId(String kind, String sourceName, long dimensionId) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }
        try {
            List<Long> ids = jdbc.queryForList(
                    "SELECT target_id FROM uf_assess_import_alias" +
                    " WHERE company_id = ? AND dimension_id = ? AND kind = ? AND source_name = ?",
                    Long.class, companyId, dimensionId, kind, normalizeName(sourceName));
            if (ids.isEmpty()) {
                return null;
            }
            jdbc.update("UPDATE uf_assess_import_alias SET hit_count = hit_count + 1" +
                    " WHERE company_id = ? AND dimension_id = ? AND kind = ? AND source_name = ?",
                    companyId, dimensionId, kind, normalizeName(sourceName));
            return ids.get(0);
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("别名表查询失败（请确认 sql/33 已应用到库）：{}", e.getMessage());
            return null;
        }
    }

    /** 名称归一化：去首尾/中间空白，全角转半角，去常见分隔标点——别名键必须与人工确认时的写法同源 */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.trim().toCharArray()) {
            if (Character.isWhitespace(c) || c == '（' || c == '）' || c == '(' || c == ')' || c == '·') {
                continue;
            }
            sb.append(c >= 'Ａ' && c <= 'Ｚ' ? (char) (c - 0xFEE0)
                    : c >= 'ａ' && c <= 'ｚ' ? (char) (c - 0xFEE0) : c == '　' ? ' ' : c);
        }
        return sb.toString().trim().toLowerCase();
    }

    private Long findPositionReqId(long departmentId, long jobTitleId, long dimensionId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM uf_position_ability_req_new WHERE department_id = ? AND job_title_id = ? AND dimension_id = ?",
                Long.class, departmentId, jobTitleId, dimensionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 工号优先、姓名+部门兜底；同名多人一律 PENDING，绝不取第一个 */
    private ResolvedUser resolveResource(AbilityStructureDTO.UserReqRow row, long dimensionId) {
        String no = trim(row.getResourceNo());
        String name = trim(row.getResourceName());
        if (!no.isEmpty()) {
            List<Map<String, Object>> byNo = jdbc.queryForList(
                    "SELECT id, lastname FROM HrmResource WHERE loginid = ?", no);
            if (byNo.size() == 1) {
                return new ResolvedUser(row, num(byNo.get(0).get("id")),
                        String.valueOf(byNo.get(0).get("lastname")), "工号匹配");
            }
            if (byNo.size() > 1) {
                return new ResolvedUser(row, null, name, "工号命中多人：" + no);
            }
        }
        Long deptId = trim(row.getDepartmentName()).isEmpty() ? null
                : findDepartmentId(trim(row.getDepartmentName()), dimensionId);
        List<Map<String, Object>> byName = deptId == null
                ? jdbc.queryForList("SELECT id, lastname FROM HrmResource WHERE lastname = ?", name)
                : jdbc.queryForList("SELECT id, lastname FROM HrmResource WHERE lastname = ? AND departmentid = ?",
                name, deptId);
        if (byName.size() == 1) {
            return new ResolvedUser(row, num(byName.get(0).get("id")), name, null);
        }
        if (byName.size() > 1) {
            return new ResolvedUser(row, null, name, "同名多人，需人工指定工号");
        }
        return new ResolvedUser(row, null, name, "查无此人（工号与姓名均未命中）");
    }

    /** 稿件内声明 + 系统已有的要素名集合（人员/岗位引用只能落在这个集合里） */
    private Set<String> knownElementNames(Plan plan) {
        Set<String> names = new HashSet<>(plan.systemElementNames);
        plan.newElements.forEach(e -> names.add(trim(e.getName())));
        return names;
    }

    private Long findElementId(String name, long dimensionId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM uf_ability_element WHERE element_name = ? AND dimension_id = ? AND deleted = 0",
                Long.class, name, dimensionId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Long categoryIdOf(Long elementId) {
        if (elementId == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT category_id FROM uf_ability_element WHERE id = ?", Long.class, elementId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 要素在指定等级号上的等级行 ID。已有要素（跳过路径）在旧数据里可能缺某一等级，
     * 此时按等级分值配置补一条，保证人员要求的外键始终有效。
     */
    private Long resolveLevelId(Long elementId, int levelNo) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM uf_ability_element_level WHERE element_id = ? AND level = ?",
                Long.class, elementId, levelNo);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        Map<String, String> names = loadLevelNames(DimensionContext.getOrDefault());
        AbilityElementLevel level = new AbilityElementLevel();
        level.setElementId(elementId);
        level.setLevel(levelNo);
        level.setLevelName(names.getOrDefault(String.valueOf(levelNo), String.valueOf(levelNo)));
        level.setScore(loadLevelScoreValues(DimensionContext.getOrDefault()).get(String.valueOf(levelNo)));
        levelService.save(level);
        return level.getId();
    }

    private void batchItem(Long batchId, String type, long entityId, String naturalKey, String action) {
        if (batchId == null) {
            return;
        }
        jdbc.update("INSERT INTO uf_assess_import_batch_item (batch_id, entity_type, entity_id, natural_key, action)" +
                        " VALUES (?, ?, ?, ?, ?)", batchId, type, entityId, naturalKey, action);
    }

    // ==================== 行工具 ====================

    private void line(Plan plan, String section, String name, String status, String message) {
        AbilityStructureVO.Line l = new AbilityStructureVO.Line();
        l.setSection(section);
        l.setName(name);
        l.setStatus(status);
        l.setMessage(message);
        plan.lines.add(l);
    }

    private void pending(Plan plan, String section, String name, String message) {
        line(plan, section, name, S_PENDING, message);
        plan.counts.setUnresolved(plan.counts.getUnresolved() + 1);
    }

    private void block(Plan plan, String section, String name, String message) {
        line(plan, section, name, S_BLOCK, message);
        plan.counts.setBlocked(plan.counts.getBlocked() + 1);
        plan.blocked = true;
    }

    private String firstBlockMessage(AbilityStructureVO.PreviewResult preview) {
        return preview.getLines().stream().filter(l -> S_BLOCK.equals(l.getStatus()))
                .map(l -> l.getName() + "：" + l.getMessage()).findFirst().orElse("未知阻断项");
    }

    private static String label(AbilityStructureDTO.PositionReqRow row) {
        return trim(row.getDepartmentName()) + " · " + trim(row.getJobTitleName());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim().replace(' ', ' ').trim();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
