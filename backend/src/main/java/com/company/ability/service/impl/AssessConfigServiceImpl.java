package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.AssessConfigDTO;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.entity.AssessGradeLevel;
import com.company.ability.entity.AssessLevelScore;
import com.company.ability.entity.AssessWeightAbility;
import com.company.ability.entity.AssessWeightAssessor;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessGradeLevelMapper;
import com.company.ability.mapper.AssessLevelScoreMapper;
import com.company.ability.mapper.AssessWeightAbilityMapper;
import com.company.ability.mapper.AssessWeightAssessorMapper;
import com.company.ability.service.AbilityCategoryService;
import com.company.ability.service.AssessConfigService;
import com.company.ability.vo.AssessConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 能力测评配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessConfigServiceImpl implements AssessConfigService {

    /** 错误码：权重合计不等于100 */
    static final int ERR_WEIGHT_SUM = 40001;
    /** 错误码：启用项缺少权重 */
    static final int ERR_WEIGHT_MISSING = 40002;
    /** 错误码：区间下限不小于上限 */
    static final int ERR_LOWER_NOT_LESS_UPPER = 40003;
    /** 错误码：相邻区间不衔接（空缺或重叠） */
    static final int ERR_GAP_OR_OVERLAP = 40004;
    /** 错误码：首行下限/末行上限/中间行边界为空不合法 */
    static final int ERR_BOUNDARY_NULL = 40005;
    /** 错误码：等级标准至少保留一行 */
    static final int ERR_GRADE_EMPTY = 40006;
    /** 错误码：等级分值不合法（字段缺失、重复、被引用等） */
    static final int ERR_LEVEL_SCORE = 40007;

    private static final List<String> ASSESSOR_CODES = List.of("LEADER", "CLIENT", "SELF");
    private static final Map<String, String> ASSESSOR_NAMES = Map.of(
            "LEADER", "直接领导", "CLIENT", "客户评分", "SELF", "自评分");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 分值物理上限：列类型 DECIMAL(5,2)（sql/08），超出会触发算术溢出 500，提前拦成业务提示 */
    private static final BigDecimal MAX_LEVEL_SCORE = new BigDecimal("999.99");

    private final AbilityCategoryService abilityCategoryService;
    private final AssessWeightAbilityMapper abilityMapper;
    private final AssessWeightAssessorMapper assessorMapper;
    private final AssessGradeLevelMapper gradeMapper;
    private final AssessLevelScoreMapper levelScoreMapper;
    /** 等级引用保护查跨表计数（要求等级、评级数据），走 JdbcTemplate 原生 SQL */
    private final JdbcTemplate jdbc;

    /**
     * 当前维度：配置读写一律显式带上（无请求头时回落默认维度 1），
     * 不依赖租户拦截器——拦截器在无头时跳过过滤，先删后插的模式会跨维度误删。
     * 有头时与拦截器追加的条件同值，重复无害。
     */
    private static long currentDim() {
        return DimensionContext.getOrDefault();
    }

    // ==================== 能力类型权重 ====================

    @Override
    public List<AssessConfigVO.AbilityWeightVO> getAbilityWeights(Long companyId) {
        long dim = currentDim();
        Map<Long, BigDecimal> saved = abilityMapper.selectList(
                new LambdaQueryWrapper<AssessWeightAbility>()
                        .eq(AssessWeightAbility::getCompanyId, companyId)
                        .eq(AssessWeightAbility::getDimensionId, dim))
                .stream().collect(Collectors.toMap(AssessWeightAbility::getCategoryId,
                        w -> w.getWeight() == null ? BigDecimal.ZERO : w.getWeight(), (a, b) -> a, HashMap::new));

        List<AssessConfigVO.AbilityWeightVO> result = new ArrayList<>();
        for (AbilityCategory c : abilityCategoryService.list(
                new LambdaQueryWrapper<AbilityCategory>()
                        .eq(AbilityCategory::getDimensionId, dim)
                        .orderByAsc(AbilityCategory::getSort))) {
            AssessConfigVO.AbilityWeightVO vo = new AssessConfigVO.AbilityWeightVO();
            vo.setCategoryId(c.getId());
            vo.setCategoryName(c.getCategoryName());
            vo.setEnabled(c.getStatus() != null && c.getStatus() == 1);
            vo.setWeight(saved.get(c.getId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAbilityWeights(AssessConfigDTO.AbilityWeightSaveDTO dto) {
        List<AssessConfigDTO.AbilityWeightItem> items = dto.getItems() == null ? List.of() : dto.getItems();
        long dim = currentDim();

        Map<Long, AbilityCategory> categories = abilityCategoryService.list(
                        new LambdaQueryWrapper<AbilityCategory>().eq(AbilityCategory::getDimensionId, dim)).stream()
                .collect(Collectors.toMap(AbilityCategory::getId, c -> c));
        Set<Long> enabledIds = categories.values().stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .map(AbilityCategory::getId).collect(Collectors.toSet());

        BigDecimal sum = BigDecimal.ZERO;
        Set<Long> sentEnabled = new HashSet<>();
        for (AssessConfigDTO.AbilityWeightItem item : items) {
            if (item.getCategoryId() == null || !categories.containsKey(item.getCategoryId())) {
                throw new BusinessException(500, "能力类别不存在，ID: " + item.getCategoryId());
            }
            if (enabledIds.contains(item.getCategoryId())) {
                if (item.getWeight() == null) {
                    throw new BusinessException(ERR_WEIGHT_MISSING, "存在未填写权重的启用能力类型");
                }
                sum = sum.add(item.getWeight());
                sentEnabled.add(item.getCategoryId());
            }
        }
        if (!sentEnabled.containsAll(enabledIds)) {
            throw new BusinessException(ERR_WEIGHT_MISSING, "存在未填写权重的启用能力类型");
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw new BusinessException(ERR_WEIGHT_SUM, "能力类型权重合计必须为100%，当前为" + sum.stripTrailingZeros().toPlainString() + "%");
        }

        abilityMapper.delete(new LambdaQueryWrapper<AssessWeightAbility>()
                .eq(AssessWeightAbility::getCompanyId, dto.getCompanyId())
                .eq(AssessWeightAbility::getDimensionId, dim));
        for (AssessConfigDTO.AbilityWeightItem item : items) {
            AssessWeightAbility e = new AssessWeightAbility();
            e.setCompanyId(dto.getCompanyId());
            e.setDimensionId(dim);
            e.setCategoryId(item.getCategoryId());
            e.setWeight(item.getWeight());
            abilityMapper.insert(e);
        }
        log.info("保存能力类型权重成功，companyId: {}, dimensionId: {}, 条数: {}", dto.getCompanyId(), dim, items.size());
    }

    // ==================== 测评对象权重 ====================

    @Override
    public List<AssessConfigVO.AssessorWeightVO> getAssessorWeights(Long companyId) {
        // merge 函数防重复键抛 IllegalStateException（toMap 无 merge 时遇重复键直接 500）
        Map<String, BigDecimal> saved = assessorMapper.selectList(
                new LambdaQueryWrapper<AssessWeightAssessor>()
                        .eq(AssessWeightAssessor::getCompanyId, companyId)
                        .eq(AssessWeightAssessor::getDimensionId, currentDim()))
                .stream().collect(Collectors.toMap(AssessWeightAssessor::getAssessorCode,
                        AssessWeightAssessor::getWeight, (a, b) -> a));
        List<AssessConfigVO.AssessorWeightVO> result = new ArrayList<>();
        for (String code : ASSESSOR_CODES) {
            AssessConfigVO.AssessorWeightVO vo = new AssessConfigVO.AssessorWeightVO();
            vo.setAssessorCode(code);
            vo.setAssessorName(ASSESSOR_NAMES.get(code));
            vo.setWeight(saved.get(code));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAssessorWeights(AssessConfigDTO.AssessorWeightSaveDTO dto) {
        List<AssessConfigDTO.AssessorWeightItem> items = dto.getItems() == null ? List.of() : dto.getItems();
        long dim = currentDim();

        Map<String, BigDecimal> sent = new HashMap<>();
        for (AssessConfigDTO.AssessorWeightItem item : items) {
            if (!ASSESSOR_CODES.contains(item.getAssessorCode())) {
                throw new BusinessException(500, "未知测评对象: " + item.getAssessorCode());
            }
            if (item.getWeight() == null) {
                throw new BusinessException(ERR_WEIGHT_MISSING, "测评对象权重不能为空");
            }
            sent.put(item.getAssessorCode(), item.getWeight());
        }
        if (!sent.keySet().containsAll(ASSESSOR_CODES)) {
            throw new BusinessException(ERR_WEIGHT_MISSING, "测评对象必须同时配置直接领导、客户评分、自评分");
        }
        BigDecimal sum = sent.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(HUNDRED) != 0) {
            throw new BusinessException(ERR_WEIGHT_SUM, "测评对象权重合计必须为100%，当前为" + sum.stripTrailingZeros().toPlainString() + "%");
        }

        assessorMapper.delete(new LambdaQueryWrapper<AssessWeightAssessor>()
                .eq(AssessWeightAssessor::getCompanyId, dto.getCompanyId())
                .eq(AssessWeightAssessor::getDimensionId, dim));
        for (String code : ASSESSOR_CODES) {
            AssessWeightAssessor e = new AssessWeightAssessor();
            e.setCompanyId(dto.getCompanyId());
            e.setDimensionId(dim);
            e.setAssessorCode(code);
            e.setWeight(sent.get(code));
            assessorMapper.insert(e);
        }
        log.info("保存测评对象权重成功，companyId: {}, dimensionId: {}", dto.getCompanyId(), dim);
    }

    // ==================== 等级标准 ====================

    @Override
    public List<AssessConfigVO.GradeLevelVO> getGradeLevels(Long companyId) {
        List<AssessConfigVO.GradeLevelVO> result = new ArrayList<>();
        for (AssessGradeLevel g : gradeMapper.selectList(new LambdaQueryWrapper<AssessGradeLevel>()
                .eq(AssessGradeLevel::getCompanyId, companyId)
                .eq(AssessGradeLevel::getDimensionId, currentDim())
                .orderByAsc(AssessGradeLevel::getSortOrder))) {
            AssessConfigVO.GradeLevelVO vo = new AssessConfigVO.GradeLevelVO();
            vo.setLevelName(g.getLevelName());
            vo.setLowerBound(g.getLowerBound());
            vo.setUpperBound(g.getUpperBound());
            vo.setSortOrder(g.getSortOrder());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveGradeLevels(AssessConfigDTO.GradeLevelSaveDTO dto) {
        List<AssessConfigDTO.GradeLevelItem> items = dto.getItems() == null ? List.of() : dto.getItems();
        long dim = currentDim();
        if (items.isEmpty()) {
            throw new BusinessException(ERR_GRADE_EMPTY, "等级标准至少保留一行");
        }

        // 按提交顺序校验：首行下限为空，末行上限为空，中间行边界必须有值
        int last = items.size() - 1;
        for (int i = 0; i < items.size(); i++) {
            AssessConfigDTO.GradeLevelItem cur = items.get(i);
            if (cur.getLevelName() == null || cur.getLevelName().isBlank()) {
                throw new BusinessException(ERR_BOUNDARY_NULL, "第" + (i + 1) + "行等级名称不能为空");
            }
            if (i == 0 && cur.getLowerBound() != null) {
                throw new BusinessException(ERR_BOUNDARY_NULL, "首行下限必须为空（负无穷）");
            }
            if (i == last && cur.getUpperBound() != null) {
                throw new BusinessException(ERR_BOUNDARY_NULL, "末行上限必须为空（正无穷）");
            }
            if (i > 0 && cur.getLowerBound() == null) {
                throw new BusinessException(ERR_BOUNDARY_NULL, "第" + (i + 1) + "行下限不能为空");
            }
            if (i < last && cur.getUpperBound() == null) {
                throw new BusinessException(ERR_BOUNDARY_NULL, "第" + (i + 1) + "行上限不能为空");
            }
            if (cur.getLowerBound() != null && cur.getUpperBound() != null
                    && cur.getLowerBound().compareTo(cur.getUpperBound()) >= 0) {
                throw new BusinessException(ERR_LOWER_NOT_LESS_UPPER,
                        "第" + (i + 1) + "行（" + cur.getLevelName() + "）下限必须小于上限");
            }
            // 左开右闭 (l, u]：相邻区间应首尾衔接，上一行上限等于下一行下限
            if (i > 0 && items.get(i - 1).getUpperBound().compareTo(cur.getLowerBound()) != 0) {
                throw new BusinessException(ERR_GAP_OR_OVERLAP,
                        "第" + i + "行与第" + (i + 1) + "行区间不衔接，存在空缺或重叠");
            }
        }

        gradeMapper.delete(new LambdaQueryWrapper<AssessGradeLevel>()
                .eq(AssessGradeLevel::getCompanyId, dto.getCompanyId())
                .eq(AssessGradeLevel::getDimensionId, dim));
        for (int i = 0; i < items.size(); i++) {
            AssessConfigDTO.GradeLevelItem item = items.get(i);
            AssessGradeLevel e = new AssessGradeLevel();
            e.setCompanyId(dto.getCompanyId());
            e.setDimensionId(dim);
            e.setLevelName(item.getLevelName());
            e.setLowerBound(item.getLowerBound());
            e.setUpperBound(item.getUpperBound());
            e.setSortOrder(i + 1);
            gradeMapper.insert(e);
        }
        log.info("保存等级标准成功，companyId: {}, dimensionId: {}, 行数: {}", dto.getCompanyId(), dim, items.size());
    }

    // ==================== 等级分值（等级唯一定义源） ====================

    /**
     * 返回当前维度实际配置的等级定义（按等级号升序）。
     * 等级数量不固定：这里配置了几个等级，配置等级弹窗与测评算分就是几个。
     */
    @Override
    public List<AssessConfigVO.LevelScoreVO> getLevelScores(Long companyId) {
        List<AssessConfigVO.LevelScoreVO> result = new ArrayList<>();
        for (AssessLevelScore e : levelScoreMapper.selectList(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, companyId)
                .eq(AssessLevelScore::getDimensionId, currentDim())
                .orderByAsc(AssessLevelScore::getLevelNo))) {
            AssessConfigVO.LevelScoreVO vo = new AssessConfigVO.LevelScoreVO();
            vo.setLevelNo(e.getLevelNo());
            vo.setLevelDesc(e.getLevelDesc());
            vo.setRateValue(e.getRateValue());
            vo.setScore(e.getScore());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLevelScores(AssessConfigDTO.LevelScoreSaveDTO dto) {
        List<AssessConfigDTO.LevelScoreItem> items = dto.getItems() == null ? List.of() : dto.getItems();
        long dim = currentDim();
        if (items.isEmpty()) {
            throw new BusinessException(ERR_LEVEL_SCORE, "至少配置一个等级");
        }

        // 逐行校验：等级号唯一、描述必填、对应的评价唯一、分值有效（不要求等级连续，不要求分值递增）
        Set<Integer> levelNos = new HashSet<>();
        Set<Integer> rateValues = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            AssessConfigDTO.LevelScoreItem item = items.get(i);
            String row = "第" + (i + 1) + "行";
            if (item.getLevelNo() == null || item.getLevelNo() < 1) {
                throw new BusinessException(ERR_LEVEL_SCORE, row + "等级必须为正整数");
            }
            if (!levelNos.add(item.getLevelNo())) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级 " + item.getLevelNo() + " 重复");
            }
            if (item.getLevelDesc() == null || item.getLevelDesc().isBlank()) {
                throw new BusinessException(ERR_LEVEL_SCORE, row + "等级描述不能为空");
            }
            if (item.getRateValue() == null || item.getRateValue() < 1) {
                throw new BusinessException(ERR_LEVEL_SCORE, row + "对应的评价必须为正整数");
            }
            if (!rateValues.add(item.getRateValue())) {
                throw new BusinessException(ERR_LEVEL_SCORE,
                        "对应的评价 " + item.getRateValue() + " 重复（一个评价值只能对应一个等级）");
            }
            if (item.getScore() == null || item.getScore().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级 " + item.getLevelNo() + " 的分值必须大于 0");
            }
            if (item.getScore().compareTo(MAX_LEVEL_SCORE) > 0) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级 " + item.getLevelNo() + " 的分值不能超过 999.99");
            }
        }

        // 引用保护：等级号被 要求等级/页面数据 引用、评价值被 已录入评级 引用时禁止删除或改号
        List<AssessLevelScore> existing = levelScoreMapper.selectList(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, dto.getCompanyId())
                .eq(AssessLevelScore::getDimensionId, dim));
        for (AssessLevelScore old : existing) {
            if (old.getLevelNo() != null && !levelNos.contains(old.getLevelNo())) {
                int refs = countLevelReferences(dto.getCompanyId(), dim, old.getLevelNo());
                if (refs > 0) {
                    throw new BusinessException(ERR_LEVEL_SCORE,
                            "等级 " + old.getLevelNo() + " 已被人员能力要求或测评页面引用（" + refs + " 处），禁止删除或改号");
                }
            }
            if (old.getRateValue() != null && !rateValues.contains(old.getRateValue())) {
                int refs = countRatingReferences(dto.getCompanyId(), dim, old.getRateValue());
                if (refs > 0) {
                    throw new BusinessException(ERR_LEVEL_SCORE,
                            "对应的评价 " + old.getRateValue() + " 已有测评评级数据（" + refs + " 条），禁止删除或修改");
                }
            }
        }

        levelScoreMapper.delete(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, dto.getCompanyId())
                .eq(AssessLevelScore::getDimensionId, dim));
        for (AssessConfigDTO.LevelScoreItem item : items) {
            AssessLevelScore e = new AssessLevelScore();
            e.setCompanyId(dto.getCompanyId());
            e.setDimensionId(dim);
            e.setLevelNo(item.getLevelNo());
            e.setLevelDesc(item.getLevelDesc().trim());
            e.setRateValue(item.getRateValue());
            e.setScore(item.getScore());
            levelScoreMapper.insert(e);
        }

        // 被删等级清理孤儿要素等级行（引用已在上面全部拦掉，这里只剩只填过等级要求的行）
        for (AssessLevelScore old : existing) {
            if (old.getLevelNo() != null && !levelNos.contains(old.getLevelNo())) {
                jdbc.update("DELETE FROM uf_ability_element_level WHERE level = ? AND dimension_id = ?",
                        old.getLevelNo(), dim);
            }
        }
        log.info("保存等级分值成功，companyId: {}, dimensionId: {}, 等级数: {}", dto.getCompanyId(), dim, items.size());
    }

    /** 等级号引用计数：人员能力要求明细（经要素等级行）、测评页面行（要求等级快照） */
    private int countLevelReferences(Long companyId, long dim, int levelNo) {
        Integer n = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM uf_user_ability_req_item i" +
                "          JOIN uf_ability_element_level l ON i.level_id = l.id" +
                "          WHERE l.level = ? AND l.dimension_id = ?)" +
                "     + (SELECT COUNT(*) FROM uf_assess_sheet_row r" +
                "          JOIN uf_assess_sheet s ON s.id = r.sheet_id" +
                "          WHERE r.required_level_no = ? AND ISNULL(r.dimension_id, s.dimension_id) = ?" +
                "            AND s.company_id = ?)",
                Integer.class, levelNo, dim, levelNo, dim, companyId);
        return n == null ? 0 : n;
    }

    /** 评价值引用计数：已录入的页面评级明细 */
    private int countRatingReferences(Long companyId, long dim, int rateValue) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_sheet_rating rt" +
                " JOIN uf_assess_sheet s ON s.id = rt.sheet_id" +
                " WHERE rt.level_no = ? AND ISNULL(rt.dimension_id, s.dimension_id) = ? AND s.company_id = ?",
                Integer.class, rateValue, dim, companyId);
        return n == null ? 0 : n;
    }
}
