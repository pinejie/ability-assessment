package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    /** 错误码：等级分值不合法（序号不全、分值非正或未递增） */
    static final int ERR_LEVEL_SCORE = 40007;

    private static final List<String> ASSESSOR_CODES = List.of("LEADER", "CLIENT", "SELF");
    private static final Map<String, String> ASSESSOR_NAMES = Map.of(
            "LEADER", "直接领导", "CLIENT", "客户评分", "SELF", "自评分");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AbilityCategoryService abilityCategoryService;
    private final AssessWeightAbilityMapper abilityMapper;
    private final AssessWeightAssessorMapper assessorMapper;
    private final AssessGradeLevelMapper gradeMapper;
    private final AssessLevelScoreMapper levelScoreMapper;

    // ==================== 能力类型权重 ====================

    @Override
    public List<AssessConfigVO.AbilityWeightVO> getAbilityWeights(Long companyId) {
        Map<Long, BigDecimal> saved = abilityMapper.selectList(
                new LambdaQueryWrapper<AssessWeightAbility>().eq(AssessWeightAbility::getCompanyId, companyId))
                .stream().collect(Collectors.toMap(AssessWeightAbility::getCategoryId,
                        w -> w.getWeight() == null ? BigDecimal.ZERO : w.getWeight(), (a, b) -> a, HashMap::new));

        List<AssessConfigVO.AbilityWeightVO> result = new ArrayList<>();
        for (AbilityCategory c : abilityCategoryService.list(
                new LambdaQueryWrapper<AbilityCategory>().orderByAsc(AbilityCategory::getSort))) {
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

        Map<Long, AbilityCategory> categories = abilityCategoryService.list().stream()
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
                .eq(AssessWeightAbility::getCompanyId, dto.getCompanyId()));
        for (AssessConfigDTO.AbilityWeightItem item : items) {
            AssessWeightAbility e = new AssessWeightAbility();
            e.setCompanyId(dto.getCompanyId());
            e.setCategoryId(item.getCategoryId());
            e.setWeight(item.getWeight());
            abilityMapper.insert(e);
        }
        log.info("保存能力类型权重成功，companyId: {}, 条数: {}", dto.getCompanyId(), items.size());
    }

    // ==================== 测评对象权重 ====================

    @Override
    public List<AssessConfigVO.AssessorWeightVO> getAssessorWeights(Long companyId) {
        Map<String, BigDecimal> saved = assessorMapper.selectList(
                new LambdaQueryWrapper<AssessWeightAssessor>().eq(AssessWeightAssessor::getCompanyId, companyId))
                .stream().collect(Collectors.toMap(AssessWeightAssessor::getAssessorCode, AssessWeightAssessor::getWeight));
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
                .eq(AssessWeightAssessor::getCompanyId, dto.getCompanyId()));
        for (String code : ASSESSOR_CODES) {
            AssessWeightAssessor e = new AssessWeightAssessor();
            e.setCompanyId(dto.getCompanyId());
            e.setAssessorCode(code);
            e.setWeight(sent.get(code));
            assessorMapper.insert(e);
        }
        log.info("保存测评对象权重成功，companyId: {}", dto.getCompanyId());
    }

    // ==================== 等级标准 ====================

    @Override
    public List<AssessConfigVO.GradeLevelVO> getGradeLevels(Long companyId) {
        List<AssessConfigVO.GradeLevelVO> result = new ArrayList<>();
        for (AssessGradeLevel g : gradeMapper.selectList(new LambdaQueryWrapper<AssessGradeLevel>()
                .eq(AssessGradeLevel::getCompanyId, companyId)
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
                .eq(AssessGradeLevel::getCompanyId, dto.getCompanyId()));
        for (int i = 0; i < items.size(); i++) {
            AssessConfigDTO.GradeLevelItem item = items.get(i);
            AssessGradeLevel e = new AssessGradeLevel();
            e.setCompanyId(dto.getCompanyId());
            e.setLevelName(item.getLevelName());
            e.setLowerBound(item.getLowerBound());
            e.setUpperBound(item.getUpperBound());
            e.setSortOrder(i + 1);
            gradeMapper.insert(e);
        }
        log.info("保存等级标准成功，companyId: {}, 行数: {}", dto.getCompanyId(), items.size());
    }

    // ==================== 等级分值 ====================

    @Override
    public List<AssessConfigVO.LevelScoreVO> getLevelScores(Long companyId) {
        List<AssessConfigVO.LevelScoreVO> result = new ArrayList<>();
        for (AssessLevelScore e : levelScoreMapper.selectList(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, companyId)
                .orderByAsc(AssessLevelScore::getLevelNo))) {
            AssessConfigVO.LevelScoreVO vo = new AssessConfigVO.LevelScoreVO();
            vo.setLevelNo(e.getLevelNo());
            vo.setScore(e.getScore());
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLevelScores(AssessConfigDTO.LevelScoreSaveDTO dto) {
        List<AssessConfigDTO.LevelScoreItem> items = dto.getItems() == null ? List.of() : dto.getItems();

        // 必须正好 1-5 级各一行
        Map<Integer, BigDecimal> byLevel = new HashMap<>();
        for (AssessConfigDTO.LevelScoreItem item : items) {
            if (item.getLevelNo() == null || item.getLevelNo() < 1 || item.getLevelNo() > 5) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级序号必须为 1-5");
            }
            if (byLevel.containsKey(item.getLevelNo())) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级 " + item.getLevelNo() + " 重复");
            }
            if (item.getScore() == null || item.getScore().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ERR_LEVEL_SCORE, "等级 " + item.getLevelNo() + " 的分值必须大于 0");
            }
            byLevel.put(item.getLevelNo(), item.getScore());
        }
        if (byLevel.size() != 5) {
            throw new BusinessException(ERR_LEVEL_SCORE, "等级分值必须同时配置 1-5 级");
        }
        // 分值须随等级升高严格递增
        for (int level = 2; level <= 5; level++) {
            if (byLevel.get(level).compareTo(byLevel.get(level - 1)) <= 0) {
                throw new BusinessException(ERR_LEVEL_SCORE,
                        "等级 " + level + " 的分值必须大于等级 " + (level - 1) + " 的分值");
            }
        }

        levelScoreMapper.delete(new LambdaQueryWrapper<AssessLevelScore>()
                .eq(AssessLevelScore::getCompanyId, dto.getCompanyId()));
        for (int level = 1; level <= 5; level++) {
            AssessLevelScore e = new AssessLevelScore();
            e.setCompanyId(dto.getCompanyId());
            e.setLevelNo(level);
            e.setScore(byLevel.get(level));
            levelScoreMapper.insert(e);
        }
        log.info("保存等级分值成功，companyId: {}", dto.getCompanyId());
    }
}
