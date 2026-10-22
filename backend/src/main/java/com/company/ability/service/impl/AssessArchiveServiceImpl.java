package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessArchiveDTO;
import com.company.ability.entity.*;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.*;
import com.company.ability.service.AssessArchiveService;
import com.company.ability.vo.AssessSubjectVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 测评归档计算实现
 *
 * 要素分 = 等级分值 ÷ 10 × 类别权重(%) ÷ 类别要素数
 * 实际得分 = 直接领导 × 领导权重% + 客户均分 × 客户权重% + 自评 × 自评权重%
 * 得分率 = 实际得分 ÷ 岗位标准分 × 100（百分比）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessArchiveServiceImpl implements AssessArchiveService {

    static final int ERR_NO_CLIENT = 40101;
    static final int ERR_RATING_INCOMPLETE = 40102;
    static final int ERR_LEVEL_INVALID = 40103;
    static final int ERR_NO_REQUIREMENT = 40104;
    static final int ERR_CONFIG_INCOMPLETE = 40105;
    static final int ERR_PERIOD_CLOSED = 40107;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN = new BigDecimal("10");
    private static final int CALC_SCALE = 6;

    private final AssessPeriodMapper periodMapper;
    private final AssessSubjectMapper subjectMapper;
    private final AssessSubjectItemMapper subjectItemMapper;
    private final AssessSubjectClientMapper subjectClientMapper;
    private final AssessWeightAbilityMapper abilityWeightMapper;
    private final AssessWeightAssessorMapper assessorWeightMapper;
    private final AssessLevelScoreMapper levelScoreMapper;
    private final AssessGradeLevelMapper gradeMapper;
    private final UserAbilityReqNewMapper reqNewMapper;
    private final UserAbilityReqItemMapper reqItemMapper;
    private final AbilityElementLevelMapper elementLevelMapper;
    private final AbilityElementMapper elementMapper;
    private final AbilityCategoryMapper categoryMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssessSubjectVO archive(AssessArchiveDTO.ArchiveRequest req) {
        // 1. 幂等：已归档则直接返回
        AssessSubject existing = subjectMapper.selectOne(new LambdaQueryWrapper<AssessSubject>()
                .eq(AssessSubject::getWorkflowRequestId, req.getWorkflowRequestId()));
        if (existing != null) {
            log.info("流程 {} 已归档，返回已有结果", req.getWorkflowRequestId());
            return getSubjectDetail(existing.getId());
        }

        // 2. 期次必须存在且未关闭
        AssessPeriod period = periodMapper.selectById(req.getPeriodId());
        if (period == null || !Objects.equals(period.getCompanyId(), req.getCompanyId())
                || (period.getStatus() != null && period.getStatus() == 1)) {
            throw new BusinessException(ERR_PERIOD_CLOSED, "测评期次不存在或已关闭");
        }

        // 2b. 维度由期次传导（钩子调用不带维度头，所有取数/落库按此维度过滤）
        long dimensionId = period.getDimensionId() != null
                ? period.getDimensionId() : DimensionContext.DEFAULT_DIMENSION_ID;

        // 3. 评价人校验
        List<AssessArchiveDTO.RaterRatings> clients = req.getClients() == null ? List.of() : req.getClients();
        if (clients.isEmpty()) {
            throw new BusinessException(ERR_NO_CLIENT, "缺少客户评价人，无法生成测评结果");
        }
        if (req.getLeader() == null || req.getSelf() == null) {
            throw new BusinessException(ERR_RATING_INCOMPLETE, "缺少直接领导或自评的评级");
        }

        // 4. 人员能力要求：要素集合与要求等级（限定当期次所属维度）
        List<UserAbilityReqItem> reqItems = loadRequirementItems(req.getResourceId(), dimensionId);
        Map<Long, Integer> requiredLevel = new LinkedHashMap<>();
        Map<Long, Long> elementCategory = new HashMap<>();
        for (UserAbilityReqItem ri : reqItems) {
            if (ri.getLevelId() == null) {
                throw new BusinessException(ERR_NO_REQUIREMENT, "要素 " + ri.getElementId() + " 缺少要求等级");
            }
            AbilityElementLevel lv = elementLevelMapper.selectById(ri.getLevelId());
            if (lv == null || lv.getLevel() == null) {
                throw new BusinessException(ERR_NO_REQUIREMENT, "要素 " + ri.getElementId() + " 的要求等级无效");
            }
            // 要求等级范围与同步服务（AssessSheetSyncService）同口径：越界会查不到等级分值，
            // 存储过程对同数据是静默算错（join 不上），Java 侧显式报错
            if (lv.getLevel() < 1 || lv.getLevel() > 5) {
                throw new BusinessException(ERR_LEVEL_INVALID,
                        "要素 " + ri.getElementId() + " 的要求等级 " + lv.getLevel() + " 超出 1-5 范围");
            }
            requiredLevel.put(ri.getElementId(), lv.getLevel());
            elementCategory.put(ri.getElementId(), ri.getCategoryId());
        }

        // 4a. 流程中不需评价的要素（assess_in_flow=0）默认不参与计分（任职标准保留）；
        //     但页面已有外部导入评级（source='EXTERNAL'，sql/25 通道）的非流程要素参与归档计分——
        //     与 usp_assess_recalc v5 同口径
        Map<Long, Boolean> assessInFlow = loadAssessInFlow(requiredLevel.keySet());
        ExternalRatings external = loadExternalRatings(req.getPeriodId(), req.getResourceId());
        requiredLevel.keySet().removeIf(id -> Boolean.FALSE.equals(assessInFlow.get(id))
                && !external.elements.contains(id));
        elementCategory.keySet().removeIf(id -> Boolean.FALSE.equals(assessInFlow.get(id))
                && !external.elements.contains(id));
        if (requiredLevel.isEmpty()) {
            throw new BusinessException(ERR_NO_REQUIREMENT,
                    "人员能力要求的要素均设置了「流程中不评价」且无外部导入评级，无法归档测评结果");
        }

        // 4b. 要素是否允许客户评价（做法 B：不允许的要素，客户分按 0 计入加权总分）
        Map<Long, Boolean> allowClientAssess = loadAllowClientAssess(requiredLevel.keySet());

        // 5. 配置快照（限定当期次所属维度）
        Config cfg = loadConfig(req.getCompanyId(), dimensionId);

        // 6. 评级覆盖校验：请求载荷先校验取值（1-5、在要求内），合并 EXTERNAL 评级后再校验完整性
        //    （非流程要素不在流程载荷里，其评级由外部导入通道写入 uf_assess_sheet_rating）
        checkLevels(requiredLevel.keySet(), req.getLeader());
        checkLevels(requiredLevel.keySet(), req.getSelf());
        Set<Long> clientRequired = requiredLevel.keySet().stream()
                .filter(id -> !Boolean.FALSE.equals(allowClientAssess.get(id)) && !external.elements.contains(id))
                .collect(Collectors.toSet());
        for (AssessArchiveDTO.RaterRatings c : clients) {
            checkClientCoverage(clientRequired, requiredLevel.keySet(), c);
        }

        // 7. 类别要素数，用于按要素平分类别权重
        Map<Long, Long> categoryCount = elementCategory.values().stream()
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        for (Long categoryId : categoryCount.keySet()) {
            if (!cfg.abilityWeight.containsKey(categoryId)) {
                throw new BusinessException(ERR_CONFIG_INCOMPLETE, "类别 " + categoryId + " 未配置能力类型权重");
            }
        }

        // 8. 计算（合并 EXTERNAL 评级后校验完整性；同名要素以流程载荷优先）
        Map<Long, Integer> leaderLevel = mergeLevels(toLevelMap(req.getLeader()), external.leader);
        Map<Long, Integer> selfLevel = mergeLevels(toLevelMap(req.getSelf()), external.self);
        checkComplete(requiredLevel.keySet(), leaderLevel, "直接领导");
        checkComplete(requiredLevel.keySet(), selfLevel, "自评");

        BigDecimal standardTotal = BigDecimal.ZERO;
        BigDecimal leaderTotal = BigDecimal.ZERO;
        BigDecimal selfTotal = BigDecimal.ZERO;
        List<AssessSubjectItem> itemRows = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : requiredLevel.entrySet()) {
            Long elementId = e.getKey();
            Long categoryId = elementCategory.get(elementId);
            BigDecimal unitWeight = cfg.abilityWeight.get(categoryId);
            long n = categoryCount.get(categoryId);

            BigDecimal std = unitScore(cfg.levelScore, e.getValue(), unitWeight, n);
            BigDecimal leaderS = unitScore(cfg.levelScore, leaderLevel.get(elementId), unitWeight, n);
            BigDecimal selfS = unitScore(cfg.levelScore, selfLevel.get(elementId), unitWeight, n);
            standardTotal = standardTotal.add(std);
            leaderTotal = leaderTotal.add(leaderS);
            selfTotal = selfTotal.add(selfS);

            AssessSubjectItem row = new AssessSubjectItem();
            row.setCategoryId(categoryId);
            row.setCategoryName(nameOfCategory(categoryId));
            row.setElementId(elementId);
            row.setElementName(nameOfElement(elementId));
            row.setRequiredLevelNo(e.getValue());
            row.setStandardScore(round2(std));
            row.setLeaderLevelNo(leaderLevel.get(elementId));
            row.setLeaderScore(round2(leaderS));
            row.setSelfLevelNo(selfLevel.get(elementId));
            row.setSelfScore(round2(selfS));
            itemRows.add(row);
        }

        // 客户：每人单独算合计，再取平均
        List<AssessSubjectClient> clientRows = new ArrayList<>();
        BigDecimal clientSum = BigDecimal.ZERO;
        for (int i = 0; i < clients.size(); i++) {
            AssessArchiveDTO.RaterRatings c = clients.get(i);
            Map<Long, Integer> levels = mergeLevels(toLevelMap(c),
                    external.clients.getOrDefault(c.getResourceId(), Map.of()));
            for (Long eid : requiredLevel.keySet()) {
                if (external.elements.contains(eid) && !levels.containsKey(eid)) {
                    throw new BusinessException(ERR_RATING_INCOMPLETE,
                            "客户评价人 " + c.getResourceId() + " 缺少外部导入要素 " + eid
                                    + " 的评级，请先完成导入或按上稿重填");
                }
            }
            BigDecimal personTotal = BigDecimal.ZERO;
            for (Map.Entry<Long, Integer> e : requiredLevel.entrySet()) {
                Long elementId = e.getKey();
                Long categoryId = elementCategory.get(elementId);
                // 禁止客户评价的要素：客户分记 0（评级记录保留，便于追溯）；
                // EXTERNAL 要素豁免——外部导入的客户评级正常计分（与 recalc v5 做法 B 一致）
                BigDecimal s = (!external.elements.contains(elementId)
                                && Boolean.FALSE.equals(allowClientAssess.get(elementId)))
                        ? BigDecimal.ZERO
                        : unitScore(cfg.levelScore, levels.get(elementId),
                                cfg.abilityWeight.get(categoryId), categoryCount.get(categoryId));
                personTotal = personTotal.add(s);

                AssessSubjectClient cr = new AssessSubjectClient();
                cr.setClientResourceId(c.getResourceId());
                cr.setClientSeq(i + 1);
                cr.setElementId(elementId);
                cr.setLevelNo(levels.get(elementId));
                cr.setScore(round2(s));
                clientRows.add(cr);
            }
            clientSum = clientSum.add(personTotal);
        }
        BigDecimal clientAvg = clientSum.divide(BigDecimal.valueOf(clients.size()), CALC_SCALE, RoundingMode.HALF_UP);

        BigDecimal actual = leaderTotal.multiply(cfg.wLeader).add(clientAvg.multiply(cfg.wClient))
                .add(selfTotal.multiply(cfg.wSelf)).divide(HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
        if (standardTotal.signum() <= 0) {
            throw new BusinessException(ERR_NO_REQUIREMENT, "岗位标准分为 0，无法计算得分率");
        }
        BigDecimal rate = actual.multiply(HUNDRED).divide(standardTotal, CALC_SCALE, RoundingMode.HALF_UP);
        String grade = judgeGrade(rate, cfg.grades);

        // 9. 快照
        String snapshot = buildSnapshot(cfg);

        // 10. 写入
        AssessSubject subject = new AssessSubject();
        subject.setCompanyId(req.getCompanyId());
        subject.setPeriodId(req.getPeriodId());
        subject.setDimensionId(dimensionId);
        subject.setResourceId(req.getResourceId());
        subject.setDepartmentId(req.getDepartmentId());
        subject.setJobTitleId(req.getJobTitleId());
        subject.setWorkflowRequestId(req.getWorkflowRequestId());
        subject.setStatus(0);
        subject.setStandardScore(round2(standardTotal));
        subject.setLeaderScore(round2(leaderTotal));
        subject.setClientScore(round2(clientAvg));
        subject.setSelfScore(round2(selfTotal));
        subject.setActualScore(round2(actual));
        subject.setScoreRate(rate.divide(HUNDRED, 4, RoundingMode.HALF_UP));
        subject.setGradeName(grade);
        subject.setSnapshotJson(snapshot);
        subject.setArchivedBy(req.getArchivedBy());
        subject.setArchiveTime(LocalDateTime.now());
        subject.setRevision(0);
        subjectMapper.insert(subject);

        for (AssessSubjectItem row : itemRows) {
            row.setSubjectId(subject.getId());
            row.setDimensionId(dimensionId);
            subjectItemMapper.insert(row);
        }
        for (AssessSubjectClient cr : clientRows) {
            cr.setSubjectId(subject.getId());
            cr.setDimensionId(dimensionId);
            subjectClientMapper.insert(cr);
        }

        log.info("归档测评成功，subjectId: {}, 流程: {}, 实际得分: {}, 得分率: {}%",
                subject.getId(), req.getWorkflowRequestId(), actual, rate);
        return getSubjectDetail(subject.getId());
    }

    @Override
    public AssessSubjectVO getSubjectDetail(Long subjectId) {
        AssessSubject s = subjectMapper.selectById(subjectId);
        if (s == null) {
            throw new BusinessException("测评结果不存在");
        }
        AssessSubjectVO vo = new AssessSubjectVO();
        vo.setId(s.getId());
        vo.setPeriodId(s.getPeriodId());
        vo.setResourceId(s.getResourceId());
        vo.setWorkflowRequestId(s.getWorkflowRequestId());
        vo.setStandardScore(s.getStandardScore());
        vo.setLeaderScore(s.getLeaderScore());
        vo.setClientScore(s.getClientScore());
        vo.setSelfScore(s.getSelfScore());
        vo.setActualScore(s.getActualScore());
        vo.setScoreRate(s.getScoreRate() == null ? null
                : s.getScoreRate().multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
        vo.setGradeName(s.getGradeName());
        vo.setArchiveTime(s.getArchiveTime());

        List<AssessSubjectItem> items = subjectItemMapper.selectList(new LambdaQueryWrapper<AssessSubjectItem>()
                .eq(AssessSubjectItem::getSubjectId, subjectId)
                .orderByAsc(AssessSubjectItem::getId));
        vo.setItems(items.stream().map(i -> {
            AssessSubjectVO.ItemVO iv = new AssessSubjectVO.ItemVO();
            iv.setElementId(i.getElementId());
            iv.setCategoryName(i.getCategoryName());
            iv.setElementName(i.getElementName());
            iv.setRequiredLevelNo(i.getRequiredLevelNo());
            iv.setStandardScore(i.getStandardScore());
            iv.setLeaderLevelNo(i.getLeaderLevelNo());
            iv.setLeaderScore(i.getLeaderScore());
            iv.setSelfLevelNo(i.getSelfLevelNo());
            iv.setSelfScore(i.getSelfScore());
            return iv;
        }).collect(Collectors.toList()));

        List<AssessSubjectClient> clientRows = subjectClientMapper.selectList(new LambdaQueryWrapper<AssessSubjectClient>()
                .eq(AssessSubjectClient::getSubjectId, subjectId));
        Map<Integer, List<AssessSubjectClient>> byClient = clientRows.stream()
                .collect(Collectors.groupingBy(AssessSubjectClient::getClientSeq, TreeMap::new, Collectors.toList()));
        List<AssessSubjectVO.ClientVO> clientVOs = new ArrayList<>();
        for (Map.Entry<Integer, List<AssessSubjectClient>> e : byClient.entrySet()) {
            AssessSubjectVO.ClientVO cv = new AssessSubjectVO.ClientVO();
            cv.setClientSeq(e.getKey());
            cv.setClientResourceId(e.getValue().get(0).getClientResourceId());
            cv.setTotalScore(round2(e.getValue().stream().map(AssessSubjectClient::getScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            clientVOs.add(cv);
        }
        vo.setClients(clientVOs);
        return vo;
    }

    // ==================== 私有方法 ====================

    private List<UserAbilityReqItem> loadRequirementItems(Long resourceId, long dimensionId) {
        UserAbilityReqNew req = reqNewMapper.selectOne(new LambdaQueryWrapper<UserAbilityReqNew>()
                .eq(UserAbilityReqNew::getResourceId, resourceId)
                .eq(UserAbilityReqNew::getDimensionId, dimensionId)
                .orderByDesc(UserAbilityReqNew::getId)
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        if (req == null) {
            throw new BusinessException(ERR_NO_REQUIREMENT, "被测人未配置人员能力要求");
        }
        List<UserAbilityReqItem> items = reqItemMapper.selectList(new LambdaQueryWrapper<UserAbilityReqItem>()
                .eq(UserAbilityReqItem::getReqId, req.getId()));
        if (items.isEmpty()) {
            throw new BusinessException(ERR_NO_REQUIREMENT, "被测人的人员能力要求为空");
        }
        return items;
    }

    /**
     * 流程载荷取值校验：非空、1-5、要素在要求范围内。
     * 完整性不在此校验——EXTERNAL 评级（外部导入要素）在合并后由 checkComplete 统一把关。
     */
    private void checkLevels(Set<Long> elementIds, AssessArchiveDTO.RaterRatings rater) {
        if (rater == null || rater.getRatings() == null) {
            throw new BusinessException(ERR_RATING_INCOMPLETE, "评价人缺少评级");
        }
        for (AssessArchiveDTO.RatingItem r : rater.getRatings()) {
            if (r.getLevelNo() == null || r.getLevelNo() < 1 || r.getLevelNo() > 5) {
                throw new BusinessException(ERR_LEVEL_INVALID, "等级必须为 1-5");
            }
            if (!elementIds.contains(r.getElementId())) {
                throw new BusinessException(ERR_RATING_INCOMPLETE, "评级包含不在人员能力要求中的要素: " + r.getElementId());
            }
        }
    }

    /** 合并外部评级后的完整性校验：每个计分要素必须有等级 */
    private void checkComplete(Set<Long> elementIds, Map<Long, Integer> levels, String raterName) {
        List<Long> missing = elementIds.stream().filter(id -> !levels.containsKey(id)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new BusinessException(ERR_RATING_INCOMPLETE,
                    raterName + " 的评级不完整，缺少要素 " + missing + "（非流程要素需先外部导入）");
        }
    }

    /** 页面外部导入评级（uf_assess_sheet_rating source='EXTERNAL'；同要素后写覆盖先写） */
    private static final class ExternalRatings {
        final Set<Long> elements = new HashSet<>();
        final Map<Long, Integer> leader = new HashMap<>();
        final Map<Long, Integer> self = new HashMap<>();
        final Map<Long, Map<Long, Integer>> clients = new HashMap<>();
    }

    private ExternalRatings loadExternalRatings(Long periodId, Long resourceId) {
        ExternalRatings ext = new ExternalRatings();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT rt.element_id, rt.rater_type, rt.rater_resource_id, rt.level_no" +
                " FROM uf_assess_sheet_rating rt" +
                " JOIN uf_assess_sheet s ON s.id = rt.sheet_id" +
                " WHERE s.period_id = ? AND s.resource_id = ? AND rt.source = 'EXTERNAL'" +
                " ORDER BY rt.id", periodId, resourceId)) {
            long elementId = ((Number) r.get("element_id")).longValue();
            String raterType = (String) r.get("rater_type");
            long raterId = ((Number) r.get("rater_resource_id")).longValue();
            Integer levelNo = r.get("level_no") == null ? null : ((Number) r.get("level_no")).intValue();
            ext.elements.add(elementId);
            switch (raterType) {
                case "LEADER" -> ext.leader.put(elementId, levelNo);
                case "SELF" -> ext.self.put(elementId, levelNo);
                default -> ext.clients.computeIfAbsent(raterId, k -> new HashMap<>()).put(elementId, levelNo);
            }
        }
        return ext;
    }

    private static Map<Long, Integer> mergeLevels(Map<Long, Integer> base, Map<Long, Integer> externalLevels) {
        Map<Long, Integer> merged = new HashMap<>(base);
        externalLevels.forEach(merged::putIfAbsent);
        return merged;
    }

    /**
     * 客户评级覆盖校验：允许出现全部要求要素范围内的额外评级（含被禁止评价但客户仍提交的，按 0 计），
     * 但「允许客户评价」的要素必须全部有评级。
     */
    private void checkClientCoverage(Set<Long> clientRequired, Set<Long> allRequired,
                                     AssessArchiveDTO.RaterRatings rater) {
        if (rater == null || rater.getRatings() == null) {
            throw new BusinessException(ERR_RATING_INCOMPLETE, "评价人缺少评级");
        }
        Set<Long> rated = new HashSet<>();
        for (AssessArchiveDTO.RatingItem r : rater.getRatings()) {
            if (r.getLevelNo() == null || r.getLevelNo() < 1 || r.getLevelNo() > 5) {
                throw new BusinessException(ERR_LEVEL_INVALID, "等级必须为 1-5");
            }
            if (!allRequired.contains(r.getElementId())) {
                throw new BusinessException(ERR_RATING_INCOMPLETE,
                        "评级包含不在人员能力要求中的要素: " + r.getElementId());
            }
            rated.add(r.getElementId());
        }
        if (!rated.containsAll(clientRequired)) {
            throw new BusinessException(ERR_RATING_INCOMPLETE,
                    "客户评价人 " + rater.getResourceId() + " 缺少允许评价要素的评级");
        }
    }

    private Map<Long, Integer> toLevelMap(AssessArchiveDTO.RaterRatings rater) {
        Map<Long, Integer> m = new HashMap<>();
        for (AssessArchiveDTO.RatingItem r : rater.getRatings()) {
            m.put(r.getElementId(), r.getLevelNo());
        }
        return m;
    }

    /** 批量读取要素的流程需评价开关（缺省按需评价处理，兼容未迁移历史行） */
    private Map<Long, Boolean> loadAssessInFlow(Set<Long> elementIds) {
        Map<Long, Boolean> map = new HashMap<>();
        if (elementIds.isEmpty()) {
            return map;
        }
        for (AbilityElement element : elementMapper.selectBatchIds(elementIds)) {
            map.put(element.getId(), element.getAssessInFlow() == null || element.getAssessInFlow() != 0);
        }
        return map;
    }

    /** 批量读取要素的客户评价开关（缺省按允许处理，兼容未迁移历史行） */
    private Map<Long, Boolean> loadAllowClientAssess(Set<Long> elementIds) {
        Map<Long, Boolean> map = new HashMap<>();
        if (elementIds.isEmpty()) {
            return map;
        }
        for (AbilityElement element : elementMapper.selectBatchIds(elementIds)) {
            map.put(element.getId(),
                    element.getAllowClientAssess() == null || element.getAllowClientAssess() != 0);
        }
        return map;
    }

    /** 要素得分 = 等级分值 ÷ 10 × 类别权重(%) ÷ 类别要素数 */
    private BigDecimal unitScore(Map<Integer, BigDecimal> levelScore, Integer levelNo,
                                 BigDecimal categoryWeightPct, long categoryElementCount) {
        // 兜底：前两层（要求等级范围、配置覆盖1-5）已挡住理论路径，此处防未知调用，报业务异常而非 NPE
        BigDecimal ls = levelNo == null ? null : levelScore.get(levelNo);
        if (ls == null) {
            throw new BusinessException(ERR_CONFIG_INCOMPLETE,
                    "等级 " + levelNo + " 的分值配置缺失，请检查当前维度的等级分值（需 1-5 级）");
        }
        return ls.divide(TEN, CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(categoryWeightPct)
                .divide(BigDecimal.valueOf(categoryElementCount), CALC_SCALE, RoundingMode.HALF_UP);
    }

    /** 左开右闭 (lower, upper]，null 为无穷 */
    private String judgeGrade(BigDecimal ratePct, List<AssessGradeLevel> grades) {
        for (AssessGradeLevel g : grades) {
            boolean aboveLower = g.getLowerBound() == null || ratePct.compareTo(g.getLowerBound()) > 0;
            boolean belowUpper = g.getUpperBound() == null || ratePct.compareTo(g.getUpperBound()) <= 0;
            if (aboveLower && belowUpper) {
                return g.getLevelName();
            }
        }
        return null;
    }

    private BigDecimal round2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private String nameOfCategory(Long categoryId) {
        AbilityCategory c = categoryMapper.selectById(categoryId);
        return c == null ? null : c.getCategoryName();
    }

    private String nameOfElement(Long elementId) {
        AbilityElement e = elementMapper.selectById(elementId);
        return e == null ? null : e.getElementName();
    }

    private Config loadConfig(Long companyId, long dimensionId) {
        Config cfg = new Config();
        cfg.abilityWeight = new HashMap<>();
        for (AssessWeightAbility w : abilityWeightMapper.selectList(new LambdaQueryWrapper<AssessWeightAbility>()
                .eq(AssessWeightAbility::getCompanyId, companyId)
                .eq(AssessWeightAbility::getDimensionId, dimensionId))) {
            if (w.getWeight() != null) cfg.abilityWeight.put(w.getCategoryId(), w.getWeight());
        }

        Map<String, BigDecimal> assessor = new HashMap<>();
        for (AssessWeightAssessor w : assessorWeightMapper.selectList(new LambdaQueryWrapper<AssessWeightAssessor>()
                .eq(AssessWeightAssessor::getCompanyId, companyId)
                .eq(AssessWeightAssessor::getDimensionId, dimensionId))) {
            assessor.put(w.getAssessorCode(), w.getWeight());
        }
        cfg.wLeader = assessor.get("LEADER");
        cfg.wClient = assessor.get("CLIENT");
        cfg.wSelf = assessor.get("SELF");
        if (cfg.wLeader == null || cfg.wClient == null || cfg.wSelf == null) {
            throw new BusinessException(ERR_CONFIG_INCOMPLETE, "测评对象权重未配置完整");
        }

        cfg.levelScore = new HashMap<>();
        for (AssessLevelScore l : levelScoreMapper.selectList(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, companyId)
                .eq(AssessLevelScore::getDimensionId, dimensionId))) {
            cfg.levelScore.put(l.getLevelNo(), l.getScore());
        }
        // 口径对齐存储过程 usp_assess_recalc：要求「键恰好覆盖 1-5」，而非「数量==5」。
        // 脏数据（如 1,2,3,4,6 也是 5 行）若只查 size 会漏网，后续 unitScore.get(缺的级) 返回 null → NPE 500。
        for (int level = 1; level <= 5; level++) {
            if (cfg.levelScore.get(level) == null) {
                throw new BusinessException(ERR_CONFIG_INCOMPLETE,
                        "等级分值未配置完整（需 1-5 级，缺第 " + level + " 级）");
            }
        }

        cfg.grades = gradeMapper.selectList(new LambdaQueryWrapper<AssessGradeLevel>()
                .eq(AssessGradeLevel::getCompanyId, companyId)
                .eq(AssessGradeLevel::getDimensionId, dimensionId)
                .orderByAsc(AssessGradeLevel::getSortOrder));
        if (cfg.grades.isEmpty()) {
            throw new BusinessException(ERR_CONFIG_INCOMPLETE, "等级标准未配置");
        }
        return cfg;
    }

    private String buildSnapshot(Config cfg) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("abilityWeights", cfg.abilityWeight);
        snap.put("assessorWeights", Map.of("LEADER", cfg.wLeader, "CLIENT", cfg.wClient, "SELF", cfg.wSelf));
        snap.put("levelScores", cfg.levelScore);
        snap.put("gradeLevels", cfg.grades.stream().map(g -> Map.of(
                "levelName", g.getLevelName(),
                "lowerBound", g.getLowerBound() == null ? "" : g.getLowerBound().toPlainString(),
                "upperBound", g.getUpperBound() == null ? "" : g.getUpperBound().toPlainString())).collect(Collectors.toList()));
        snap.put("generatedAt", LocalDateTime.now().toString());
        try {
            return objectMapper.writeValueAsString(snap);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("快照生成失败");
        }
    }

    /** 本次计算所用的配置（归档时读取，即快照内容） */
    private static class Config {
        Map<Long, BigDecimal> abilityWeight;
        BigDecimal wLeader;
        BigDecimal wClient;
        BigDecimal wSelf;
        Map<Integer, BigDecimal> levelScore;
        List<AssessGradeLevel> grades;
    }
}
