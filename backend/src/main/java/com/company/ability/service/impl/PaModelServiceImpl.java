package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.dto.PaCategoryCreateDTO;
import com.company.ability.dto.PaCategoryUpdateDTO;
import com.company.ability.dto.PaItemCreateDTO;
import com.company.ability.dto.PaItemLevelDTO;
import com.company.ability.dto.PaItemUpdateDTO;
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
import com.company.ability.service.PaModelService;
import com.company.ability.vo.PaBandVO;
import com.company.ability.vo.PaCategoryVO;
import com.company.ability.vo.PaItemLevelVO;
import com.company.ability.vo.PaItemVO;
import com.company.ability.vo.PaModelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 岗位能力模型服务实现。
 *
 * <p>校验分三层，各自守不同的问题：</p>
 * <ol>
 *   <li><b>库层</b>：唯一索引（类别名、能力项编码、等级排序号）+ CHECK（评级主体至少一个）</li>
 *   <li><b>本类</b>：给出人能看懂的提示（英文索引名对使用者没有意义）；
 *       以及库层表达不了的规则 —— 等级分值唯一、至少 2 档、区间首尾开口且无缝不重叠</li>
 *   <li><b>引用保护</b>：被评级/测评页引用的配置不许物理删除，只能停用</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaModelServiceImpl implements PaModelService {

    /** 单个能力项至少 2 档：1 档没有区分度，评价人点哪都一样 */
    private static final int MIN_LEVELS = 2;

    private final PaPostMapper postMapper;
    private final PaCategoryMapper categoryMapper;
    private final PaItemMapper itemMapper;
    private final PaItemLevelMapper levelMapper;
    private final PaBandMapper bandMapper;
    private final PaRatingMapper ratingMapper;
    private final PaSheetItemMapper sheetItemMapper;

    // ================================================================ 读

    @Override
    public PaModelVO getModel(Long postId) {
        PaPost post = requirePost(postId);

        PaModelVO model = new PaModelVO();
        model.setPostId(post.getId());
        model.setDepartmentId(post.getDepartmentId());
        model.setDepartmentName(post.getDepartmentName());
        model.setPostName(post.getPostName());

        List<PaCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<PaCategory>()
                .eq(PaCategory::getPostId, postId)
                .orderByAsc(PaCategory::getSort).orderByAsc(PaCategory::getId));
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(PaCategory::getId, PaCategory::getCategoryName));

        List<PaItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getPostId, postId)
                .orderByAsc(PaItem::getCategoryId).orderByAsc(PaItem::getSort).orderByAsc(PaItem::getId));
        Map<Long, List<PaItemLevel>> levelsByItem = items.isEmpty()
                ? Map.of()
                : levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                        .in(PaItemLevel::getItemId, items.stream().map(PaItem::getId).toList())
                        .orderByAsc(PaItemLevel::getSort).orderByAsc(PaItemLevel::getId))
                .stream().collect(Collectors.groupingBy(PaItemLevel::getItemId));

        // 左树角标：只数启用中的能力项（停用的不会进测评页）
        Map<Long, Long> enabledCountByCategory = items.stream()
                .filter(i -> Objects.equals(i.getStatus(), 1))
                .collect(Collectors.groupingBy(PaItem::getCategoryId, Collectors.counting()));

        BigDecimal weightTotal = BigDecimal.ZERO;
        int unconfigured = 0;
        List<PaItemVO> itemVOs = new ArrayList<>(items.size());
        for (PaItem item : items) {
            PaItemVO vo = new PaItemVO();
            BeanUtils.copyProperties(item, vo);
            vo.setCategoryName(categoryNames.get(item.getCategoryId()));
            List<PaItemLevel> levels = levelsByItem.getOrDefault(item.getId(), List.of());
            List<PaItemLevelVO> levelVOs = levels.stream().map(l -> {
                PaItemLevelVO lv = new PaItemLevelVO();
                BeanUtils.copyProperties(l, lv);
                return lv;
            }).toList();
            vo.setLevels(levelVOs);
            vo.setLevelCount(levelVOs.size());
            itemVOs.add(vo);

            if (Objects.equals(item.getStatus(), 1)) {
                weightTotal = weightTotal.add(item.getWeight() == null ? BigDecimal.ZERO : item.getWeight());
                if (levelVOs.size() < MIN_LEVELS) {
                    unconfigured++;
                }
            }
        }
        model.setItems(itemVOs);

        List<PaCategoryVO> categoryVOs = categories.stream().map(c -> {
            PaCategoryVO vo = new PaCategoryVO();
            BeanUtils.copyProperties(c, vo);
            vo.setItemCount(enabledCountByCategory.getOrDefault(c.getId(), 0L).intValue());
            return vo;
        }).toList();
        model.setCategories(categoryVOs);

        model.setBands(listBands(postId));
        model.setWeightTotal(weightTotal);
        model.setItemCount((int) items.stream().filter(i -> Objects.equals(i.getStatus(), 1)).count());
        model.setUnconfiguredItemCount(unconfigured);
        return model;
    }

    @Override
    public List<PaBandVO> listBands(Long postId) {
        requirePost(postId);
        return bandMapper.selectList(new LambdaQueryWrapper<PaBand>()
                        .eq(PaBand::getPostId, postId)
                        .orderByAsc(PaBand::getSort).orderByAsc(PaBand::getId))
                .stream().map(b -> {
                    PaBandVO vo = new PaBandVO();
                    BeanUtils.copyProperties(b, vo);
                    return vo;
                }).toList();
    }

    // ================================================================ 能力类别

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(Long postId, PaCategoryCreateDTO dto) {
        requirePost(postId);
        String name = dto.getCategoryName().trim();
        assertCategoryNameUnique(postId, name, null);

        PaCategory category = new PaCategory();
        BeanUtils.copyProperties(dto, category);
        category.setPostId(postId);
        category.setCategoryName(name);
        category.setDimensionId(DimensionContext.getOrDefault());
        categoryMapper.insert(category);
        log.info("新增能力类别，postId={}, categoryId={}, name={}", postId, category.getId(), name);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(Long postId, PaCategoryUpdateDTO dto) {
        requirePost(postId);
        PaCategory category = categoryMapper.selectById(dto.getId());
        if (category == null || !Objects.equals(category.getPostId(), postId)) {
            throw new BusinessException("能力类别不存在");
        }
        if (dto.getCategoryName() != null && !dto.getCategoryName().isBlank()) {
            String name = dto.getCategoryName().trim();
            assertCategoryNameUnique(postId, name, category.getId());
            category.setCategoryName(name);
        }
        if (dto.getDescription() != null) {
            category.setDescription(dto.getDescription());
        }
        if (dto.getSort() != null) {
            category.setSort(dto.getSort());
        }
        if (dto.getStatus() != null) {
            category.setStatus(dto.getStatus());
        }
        categoryMapper.updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long postId, Long categoryId) {
        requirePost(postId);
        PaCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !Objects.equals(category.getPostId(), postId)) {
            throw new BusinessException("能力类别不存在");
        }
        // 类别下挂着能力项时不许删：能力项带着等级和历史评级，静默级联删除等于删数据
        long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<PaItem>()
                .eq(PaItem::getCategoryId, categoryId));
        if (itemCount > 0) {
            throw new BusinessException("该类别下还有 " + itemCount + " 个能力项，不能删除。"
                    + "请先移走或删除这些能力项，或把类别改为停用。");
        }
        categoryMapper.deleteById(categoryId);
        log.info("删除能力类别，postId={}, categoryId={}", postId, categoryId);
    }

    // ================================================================ 能力项

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createItem(Long postId, PaItemCreateDTO dto) {
        requirePost(postId);
        requireCategoryInPost(postId, dto.getCategoryId());
        validateItemFields(dto.getWeight(), dto.getRaterLeader(), dto.getRater360());
        validateLevels(dto.getLevels(), true);

        PaItem item = new PaItem();
        BeanUtils.copyProperties(dto, item, "levels");
        item.setPostId(postId);
        item.setItemCode(trimToNull(dto.getItemCode()));
        item.setItemName(dto.getItemName().trim());
        // copyProperties 已带过来，这里再规整一次：纯空格存进去，页面上是个空白标签，看不出是「没填」
        item.setRaterText(trimToNull(dto.getRaterText()));
        item.setDimensionId(DimensionContext.getOrDefault());
        itemMapper.insert(item);

        saveLevels(item.getId(), dto.getLevels(), List.of());
        log.info("新增能力项，postId={}, itemId={}, name={}", postId, item.getId(), item.getItemName());
        return item.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(Long postId, PaItemUpdateDTO dto) {
        requirePost(postId);
        PaItem item = itemMapper.selectById(dto.getId());
        if (item == null || !Objects.equals(item.getPostId(), postId)) {
            throw new BusinessException("能力项不存在");
        }
        if (dto.getCategoryId() != null && !dto.getCategoryId().equals(item.getCategoryId())) {
            requireCategoryInPost(postId, dto.getCategoryId());
            item.setCategoryId(dto.getCategoryId());
        }
        if (dto.getItemName() != null && !dto.getItemName().isBlank()) {
            item.setItemName(dto.getItemName().trim());
        }
        if (dto.getItemCode() != null) {
            // 编码允许重复（sql/65）：它只是参考号，不是唯一标识
            item.setItemCode(trimToNull(dto.getItemCode()));
        }
        if (dto.getDefinition() != null) {
            item.setDefinition(dto.getDefinition());
        }
        // raterText 与 definition 同口径：传 null 不动，传空串就是清空。
        // 清空这一半不能只靠 itemMapper.updateById —— NOT_NULL 更新策略会跳过 null 字段，
        // 「删了描述但存不进去」在页面上表现为保存成功、刷新又回来
        boolean clearRaterText = dto.getRaterText() != null && trimToNull(dto.getRaterText()) == null;
        if (dto.getRaterText() != null) {
            item.setRaterText(trimToNull(dto.getRaterText()));
        }

        BigDecimal weight = dto.getWeight() == null ? item.getWeight() : dto.getWeight();
        Integer raterLeader = dto.getRaterLeader() == null ? item.getRaterLeader() : dto.getRaterLeader();
        Integer rater360 = dto.getRater360() == null ? item.getRater360() : dto.getRater360();
        validateItemFields(weight, raterLeader, rater360);
        item.setWeight(weight);
        item.setRaterLeader(raterLeader);
        item.setRater360(rater360);

        if (dto.getSort() != null) {
            item.setSort(dto.getSort());
        }
        if (dto.getStatus() != null) {
            item.setStatus(dto.getStatus());
        }
        itemMapper.updateById(item);
        if (clearRaterText) {
            itemMapper.update(null, new UpdateWrapper<PaItem>()
                    .eq("id", item.getId())
                    .set("rater_text", null));
        }

        // levels 为 null = 不动等级；否则整批覆盖。
        // 覆盖时同样要求至少 MIN_LEVELS 档：曾经只在新增时拦，结果「3 档删到 1 档」能存进去 ——
        // 这种能力项在填表页一个档位都点不出来，评价人整页提交会被「评价不完整」挡住。
        if (dto.getLevels() != null) {
            validateLevels(dto.getLevels(), true);
            List<PaItemLevel> existing = levelMapper.selectList(new LambdaQueryWrapper<PaItemLevel>()
                    .eq(PaItemLevel::getItemId, item.getId()));
            saveLevels(item.getId(), dto.getLevels(), existing);
        }
        log.info("更新能力项，postId={}, itemId={}", postId, item.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long postId, Long itemId) {
        requirePost(postId);
        PaItem item = itemMapper.selectById(itemId);
        if (item == null || !Objects.equals(item.getPostId(), postId)) {
            throw new BusinessException("能力项不存在");
        }
        assertItemNotReferenced(itemId);
        itemMapper.deleteById(itemId);
        levelMapper.delete(new LambdaQueryWrapper<PaItemLevel>().eq(PaItemLevel::getItemId, itemId));
        log.info("删除能力项，postId={}, itemId={}", postId, itemId);
    }

    // ================================================================ 分值区间

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBands(Long postId, List<PaBandSaveDTO> bands) {
        requirePost(postId);
        List<PaBandSaveDTO> list = bands == null ? List.of() : bands;
        validateBands(list);

        // 整批替换：先清空再插入。区间行不被任何业务表引用（评级只存 band_name 快照），
        // 所以物理删除是安全的，且不会越攒越多。
        bandMapper.delete(new LambdaQueryWrapper<PaBand>().eq(PaBand::getPostId, postId));
        long dim = DimensionContext.getOrDefault();
        for (PaBandSaveDTO dto : list) {
            PaBand band = new PaBand();
            band.setPostId(postId);
            band.setDimensionId(dim);
            band.setBandName(dto.getBandName().trim());
            band.setLowerBound(dto.getLowerBound());
            band.setUpperBound(dto.getUpperBound());
            band.setSort(dto.getSort() == null ? 0 : dto.getSort());
            bandMapper.insert(band);
        }
        log.info("保存分值区间，postId={}, 共 {} 段", postId, list.size());
    }

    // ================================================================ 内部：校验

    private PaPost requirePost(Long postId) {
        if (postId == null) {
            throw new BusinessException("岗位不能为空");
        }
        PaPost post = postMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException("岗位不存在");
        }
        return post;
    }

    private void requireCategoryInPost(Long postId, Long categoryId) {
        PaCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !Objects.equals(category.getPostId(), postId)) {
            throw new BusinessException("能力类别不存在或不属于该岗位");
        }
    }

    private void assertCategoryNameUnique(Long postId, String name, Long excludeId) {
        LambdaQueryWrapper<PaCategory> w = new LambdaQueryWrapper<PaCategory>()
                .eq(PaCategory::getPostId, postId)
                .eq(PaCategory::getCategoryName, name);
        if (excludeId != null) {
            w.ne(PaCategory::getId, excludeId);
        }
        if (categoryMapper.selectCount(w) > 0) {
            throw new BusinessException("该岗位下已存在名为「" + name + "」的能力类别");
        }
    }

    private void validateItemFields(BigDecimal weight, Integer raterLeader, Integer rater360) {
        if (weight == null) {
            throw new BusinessException("权重不能为空");
        }
        if (weight.signum() < 0) {
            throw new BusinessException("权重不能为负数");
        }
        if (weight.compareTo(new BigDecimal("99999")) > 0) {
            throw new BusinessException("权重过大，请填百分数（如 8 表示 8%）");
        }
        boolean leader = Objects.equals(raterLeader, 1);
        boolean r360 = Objects.equals(rater360, 1);
        if (!leader && !r360) {
            // 与库层 CK_pa_item_rater 同口径，这里先拦一次给出中文提示
            throw new BusinessException("评级主体至少选一个：没有主体的能力项没人能评，测评推不动");
        }
    }

    /**
     * 等级校验。
     *
     * <p>{@code requireMin} 为 true 时强制至少 {@link #MIN_LEVELS} 档（新增与整批覆盖都要）。
     * 只有 0/1 档的能力项在填表页点不出任何档位，评价人会卡在「评价不完整」上。</p>
     */
    private void validateLevels(List<PaItemLevelDTO> levels, boolean requireMin) {
        if (levels == null || levels.isEmpty()) {
            if (requireMin) {
                throw new BusinessException("每个能力项至少配置 " + MIN_LEVELS + " 个等级");
            }
            return;
        }
        if (levels.size() < MIN_LEVELS && requireMin) {
            throw new BusinessException("每个能力项至少配置 " + MIN_LEVELS + " 个等级");
        }

        Set<Integer> sorts = new HashSet<>();
        Set<BigDecimal> scores = new HashSet<>();
        for (PaItemLevelDTO l : levels) {
            if (l.getLevelName() == null || l.getLevelName().isBlank()) {
                throw new BusinessException("等级名称不能为空");
            }
            if (l.getScore() == null) {
                throw new BusinessException("等级「" + l.getLevelName() + "」的分值不能为空");
            }
            if (l.getScore().signum() < 0) {
                throw new BusinessException("等级「" + l.getLevelName() + "」的分值不能为负数");
            }
            int sort = l.getSort() == null ? 0 : l.getSort();
            if (!sorts.add(sort)) {
                throw new BusinessException("等级排序号 " + sort + " 重复，同一能力项内排序号必须唯一");
            }
            // 用 stripTrailingZeros 比较：4 与 4.00 是同一个分值，不能当两档
            if (!scores.add(l.getScore().stripTrailingZeros())) {
                throw new BusinessException("等级分值 " + l.getScore().stripTrailingZeros().toPlainString()
                        + " 重复，同一能力项内分值必须唯一");
            }
        }
    }

    /**
     * 分值区间校验：必须把整条分值轴铺满且不重叠。
     *
     * <p>区间语义 <b>[lower, upper)</b>：下界含、上界不含，所以相邻两段是「前一段上界 == 后一段下界」，
     * 分界点上的得分归后一段（3.00 分落 [3,4) 能手，不落 [2,3) 熟手）。</p>
     *
     * <p>规则：</p>
     * <ol>
     *   <li>最高一档上界为空（+∞）—— 否则更高的得分命中不到任何区间</li>
     *   <li>中间各段上下界都不能为空，且首尾相接：前一段上界 == 后一段下界</li>
     * </ol>
     * <p>第 2 条同时挡掉「重叠」和「断档」—— 断档会让某段得分命中不到任何区间，
     * 报告上等级说明就是空白，而空白在页面上看起来跟"没配"一模一样，极难排查。</p>
     *
     * <p>最低一档的下界<b>可以留空</b>（= −∞），也可以写具体分值（如写 1 就是「1 分起」），
     * 业务方的表述是「[1,2) 新手 … [5] 专家」，下界是从 1 开始写的，不能强制留空。</p>
     */
    private void validateBands(List<PaBandSaveDTO> bands) {
        if (bands.isEmpty()) {
            return;
        }
        for (PaBandSaveDTO b : bands) {
            if (b.getBandName() == null || b.getBandName().isBlank()) {
                throw new BusinessException("等级说明不能为空");
            }
            if (b.getLowerBound() != null && b.getUpperBound() != null
                    && b.getLowerBound().compareTo(b.getUpperBound()) >= 0) {
                throw new BusinessException("区间「" + b.getBandName() + "」的下界必须小于上界"
                        + "（区间是左闭右开：下界 ≤ 得分 < 上界）");
            }
        }

        List<PaBandSaveDTO> sorted = bands.stream()
                .sorted(Comparator.comparing(PaBandSaveDTO::getLowerBound,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        PaBandSaveDTO last = sorted.get(sorted.size() - 1);
        if (last.getUpperBound() != null) {
            throw new BusinessException("最高一档「" + last.getBandName()
                    + "」的上界请留空（表示 +∞），否则高于 " + last.getUpperBound().toPlainString()
                    + " 的得分命中不到任何区间");
        }

        for (int i = 0; i < sorted.size() - 1; i++) {
            PaBandSaveDTO cur = sorted.get(i);
            PaBandSaveDTO next = sorted.get(i + 1);
            if (cur.getUpperBound() == null) {
                throw new BusinessException("区间「" + cur.getBandName()
                        + "」不是最高一档，上界不能留空（留空表示 +∞）");
            }
            if (next.getLowerBound() == null) {
                throw new BusinessException("区间「" + next.getBandName()
                        + "」不是最低一档，下界不能留空（留空表示 −∞）");
            }
            int cmp = cur.getUpperBound().compareTo(next.getLowerBound());
            if (cmp == 0) {
                continue;
            }
            if (cmp > 0) {
                throw new BusinessException("区间「" + cur.getBandName() + "」与「" + next.getBandName()
                        + "」重叠：前者上界 " + cur.getUpperBound().toPlainString()
                        + " 大于后者下界 " + next.getLowerBound().toPlainString());
            }
            throw new BusinessException("区间「" + cur.getBandName() + "」与「" + next.getBandName()
                    + "」之间断档：" + cur.getUpperBound().toPlainString() + " ~ "
                    + next.getLowerBound().toPlainString() + " 这段得分命中不到任何区间");
        }
    }

    // ================================================================ 内部：写入

    /**
     * 整批覆盖等级。
     *
     * <p>按 {@code id} 判新旧：有 id 且属于本能力项 → 更新；无 id → 新增；
     * 库里有但请求里没出现 → 删除。删除前查引用，任何一档被评级用过就整批拒绝
     * （部分成功会让用户看到"保存了但没完全保存"）。</p>
     */
    private void saveLevels(Long itemId, List<PaItemLevelDTO> levels, List<PaItemLevel> existing) {
        List<PaItemLevelDTO> list = levels == null ? List.of() : levels;
        Set<Long> keepIds = list.stream().map(PaItemLevelDTO::getId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Long> removeIds = existing.stream().map(PaItemLevel::getId)
                .filter(id -> !keepIds.contains(id)).toList();
        if (!removeIds.isEmpty()) {
            long rated = ratingMapper.selectCount(new LambdaQueryWrapper<PaRating>()
                    .in(PaRating::getLevelId, removeIds));
            if (rated > 0) {
                throw new BusinessException("要删除的等级已被 " + rated + " 条评级使用，不能删除。"
                        + "可以改名或调整分值，或先把相关测评撤回。");
            }
            levelMapper.delete(new LambdaQueryWrapper<PaItemLevel>().in(PaItemLevel::getId, removeIds));
        }

        Map<Long, PaItemLevel> existingById = existing.stream()
                .collect(Collectors.toMap(PaItemLevel::getId, Function.identity()));
        long dim = DimensionContext.getOrDefault();
        for (PaItemLevelDTO dto : list) {
            if (dto.getId() != null) {
                PaItemLevel level = existingById.get(dto.getId());
                if (level == null) {
                    throw new BusinessException("等级不属于该能力项，请刷新页面后重试");
                }
                level.setLevelName(dto.getLevelName().trim());
                level.setScore(dto.getScore());
                level.setLevelDesc(dto.getLevelDesc());
                level.setSort(dto.getSort() == null ? 0 : dto.getSort());
                levelMapper.updateById(level);
            } else {
                PaItemLevel level = new PaItemLevel();
                level.setItemId(itemId);
                level.setDimensionId(dim);
                level.setLevelName(dto.getLevelName().trim());
                level.setScore(dto.getScore());
                level.setLevelDesc(dto.getLevelDesc());
                level.setSort(dto.getSort() == null ? 0 : dto.getSort());
                levelMapper.insert(level);
            }
        }
    }

    /** 能力项被测评页或评级引用后不许删（口径 #15） */
    private void assertItemNotReferenced(Long itemId) {
        long ratingCount = ratingMapper.selectCount(new LambdaQueryWrapper<PaRating>()
                .eq(PaRating::getItemId, itemId));
        long sheetItemCount = sheetItemMapper.selectCount(new LambdaQueryWrapper<PaSheetItem>()
                .eq(PaSheetItem::getItemId, itemId));
        if (ratingCount > 0 || sheetItemCount > 0) {
            throw new BusinessException("该能力项已被测评数据引用（" + sheetItemCount + " 条测评页明细 / "
                    + ratingCount + " 条评级），不能删除。可改为停用 —— 停用后新建的测评不再包含它，历史报告不受影响。");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
