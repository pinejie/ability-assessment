package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.context.DimensionContext;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PaPostCreateDTO;
import com.company.ability.dto.PaPostUpdateDTO;
import com.company.ability.entity.PaCategory;
import com.company.ability.entity.PaItem;
import com.company.ability.entity.PaPost;
import com.company.ability.entity.PaSheet;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PaCategoryMapper;
import com.company.ability.mapper.PaItemMapper;
import com.company.ability.mapper.PaPostMapper;
import com.company.ability.mapper.PaSheetMapper;
import com.company.ability.service.PaPostService;
import com.company.ability.vo.PaPostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 测评岗位服务实现。
 *
 * <p>岗位是能力模型的锚点，三处一致性由本类保证：</p>
 * <ol>
 *   <li><b>同部门内岗位名唯一</b> —— 库层筛选唯一索引兜底，这里先查一遍给出人能看懂的提示。
 *       全公司通用岗位（{@code departmentId == null}）按「全公司唯一」校验</li>
 *   <li><b>部门名落快照</b> —— 从泛微 HrmDepartment 实时读取后写入 department_name；
 *       泛微改名后历史报告不该跟着变，所以只在保存时取一次</li>
 *   <li><b>删除前查引用</b> —— 有人岗映射或测评页引用过就拒绝物理删除</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaPostServiceImpl extends ServiceImpl<PaPostMapper, PaPost> implements PaPostService {

    private final JdbcTemplate jdbc;
    private final PaCategoryMapper categoryMapper;
    private final PaItemMapper itemMapper;
    private final PaSheetMapper sheetMapper;

    // ------------------------------------------------------------------ 写

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPost(PaPostCreateDTO dto) {
        // 通用岗位没有部门：不查部门表、不落部门名快照（查了反而会因为 null 报「部门不存在」）
        String deptName = dto.getDepartmentId() == null ? null : resolveDepartmentName(dto.getDepartmentId());
        assertNameUnique(dto.getDepartmentId(), dto.getPostName(), null);

        PaPost post = new PaPost();
        BeanUtils.copyProperties(dto, post);
        post.setDepartmentName(deptName);
        post.setDimensionId(DimensionContext.getOrDefault());
        save(post);
        log.info("创建测评岗位成功，ID: {}, 部门: {}, 岗位: {}",
                post.getId(), deptName == null ? "全公司通用" : deptName, post.getPostName());
        return post.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePost(PaPostUpdateDTO dto) {
        PaPost post = getById(dto.getId());
        if (post == null) {
            throw new BusinessException("岗位不存在");
        }
        // 部门和名称都可能变，唯一性要按「改后」的组合校验，且排除自己
        assertNameUnique(dto.getDepartmentId(), dto.getPostName(), dto.getId());

        // 换部门时重新取部门名快照（旧快照是旧部门的）。
        // 用 Objects.equals 而不是 dto.getDepartmentId().equals(...)：部门置空时前者不会 NPE，
        // 后者会在「把岗位改成通用」这条路径上直接炸。
        if (!Objects.equals(dto.getDepartmentId(), post.getDepartmentId())) {
            post.setDepartmentName(dto.getDepartmentId() == null
                    ? null : resolveDepartmentName(dto.getDepartmentId()));
        }
        BeanUtils.copyProperties(dto, post, "id", "dimensionId", "deleted");
        // department_id 改空要走显式 SQL：updateById 的 NOT_NULL 策略会跳过 null 字段，
        // 「改成通用」会表现为保存成功、刷新还是原来的部门。
        if (dto.getDepartmentId() == null) {
            updateById(post);
            update(null, new UpdateWrapper<PaPost>()
                    .eq("id", post.getId())
                    .set("department_id", null)
                    .set("department_name", null));
        } else {
            updateById(post);
        }
        log.info("更新测评岗位成功，ID: {}", post.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long id) {
        PaPost post = getById(id);
        if (post == null) {
            throw new BusinessException("岗位不存在");
        }

        // 引用保护（口径 #15：有引用时禁止物理删除）
        long sheetCount = sheetMapper.selectCount(new LambdaQueryWrapper<PaSheet>()
                .eq(PaSheet::getPostId, id));
        if (sheetCount > 0) {
            throw new BusinessException("该岗位已被引用（" + sheetCount
                    + " 个测评页），不能删除。可改为停用。");
        }

        // 能力项/类别是岗位的下级配置，随岗位一起软删（否则会留下永远访问不到的孤儿数据）
        itemMapper.delete(new LambdaQueryWrapper<PaItem>().eq(PaItem::getPostId, id));
        categoryMapper.delete(new LambdaQueryWrapper<PaCategory>().eq(PaCategory::getPostId, id));
        removeById(id);
        log.info("删除测评岗位成功，ID: {}，级联软删其类别与能力项", id);
    }

    // ------------------------------------------------------------------ 读

    @Override
    public PaPostVO getPostById(Long id) {
        PaPost post = getById(id);
        if (post == null) {
            throw new BusinessException("岗位不存在");
        }
        return toVO(post, statsOf(List.of(id)).get(id));
    }

    @Override
    public PageResult<PaPostVO> pagePosts(PageRequest pageRequest, Long departmentId, String keyword,
                                          Integer status, Boolean generic) {
        pageRequest.validate();
        QueryWrapper<PaPost> wrapper = new QueryWrapper<>();
        if (departmentId != null) {
            wrapper.eq("department_id", departmentId);
        }
        // 通用岗位单独筛：部门下拉里选不到「没有部门」这种值，
        // 只能靠这个开关把 department_id IS NULL 的那批捞出来
        if (Boolean.TRUE.equals(generic)) {
            wrapper.isNull("department_id");
        } else if (Boolean.FALSE.equals(generic)) {
            wrapper.isNotNull("department_id");
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            // 岗位名 / 编码 / 部门名 三处都能搜：用户往往只记得其中一个
            String like = keyword.trim();
            wrapper.and(w -> w.like("post_name", like)
                    .or().like("post_code", like)
                    .or().like("department_name", like));
        }
        // 有部门的按部门名排，通用岗位（部门为空）集中排在最后 —— SQL Server 的 ASC 把 NULL 排最前，
        // 会让「全公司通用」的一堆压在普通岗位上面，看着像默认项
        wrapper.last("ORDER BY CASE WHEN department_id IS NULL THEN 1 ELSE 0 END, department_name, sort, id");

        Page<PaPost> page = page(new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize()), wrapper);
        Map<Long, PaPostVO> stats = statsOf(page.getRecords().stream().map(PaPost::getId).toList());
        List<PaPostVO> voList = page.getRecords().stream()
                .map(p -> toVO(p, stats.get(p.getId())))
                .collect(Collectors.toList());
        return PageResult.from(page, voList);
    }

    @Override
    public List<PaPostVO> listPosts(Long departmentId) {
        QueryWrapper<PaPost> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);
        if (departmentId != null) {
            // 部门下拉只给这个部门的岗位；通用岗位不在这里出（见 listAllPosts）
            wrapper.eq("department_id", departmentId);
        }
        wrapper.orderByAsc("department_name").orderByAsc("sort").orderByAsc("id");
        List<PaPost> list = list(wrapper);
        Map<Long, PaPostVO> stats = statsOf(list.stream().map(PaPost::getId).toList());
        return list.stream().map(p -> toVO(p, stats.get(p.getId()))).collect(Collectors.toList());
    }

    @Override
    public List<PaPostVO> listGenericPosts() {
        QueryWrapper<PaPost> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1).isNull("department_id");
        wrapper.orderByAsc("sort").orderByAsc("id");
        List<PaPost> list = list(wrapper);
        Map<Long, PaPostVO> stats = statsOf(list.stream().map(PaPost::getId).toList());
        return list.stream().map(p -> toVO(p, stats.get(p.getId()))).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 批量算每个岗位的类别数 / 能力项数 / 权重合计 / 等级未配好的能力项数。
     *
     * <p>一次 group by 查完，避免列表 20 行发 60 条 SQL。
     * 计数只数启用中的类别与能力项 —— 停用的那些不会进入测评页，算进来会让合计虚高。</p>
     *
     * <p>「等级未配好」= 启用能力项里等级不足 2 档的个数。等级表没有逻辑删除列
     * （删除即物理删除），所以不用带 deleted 条件；口径与 {@code PaModelServiceImpl}
     * 的 unconfiguredItemCount 必须一致，否则列表显示「配好了」、点进去却提示「没配好」。</p>
     */
    private Map<Long, PaPostVO> statsOf(Collection<Long> postIds) {
        Map<Long, PaPostVO> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) {
            return result;
        }
        for (Long pid : postIds) {
            PaPostVO vo = new PaPostVO();
            vo.setCategoryCount(0);
            vo.setItemCount(0);
            vo.setWeightTotal(BigDecimal.ZERO);
            vo.setUnconfiguredItemCount(0);
            result.put(pid, vo);
        }

        String in = postIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT post_id, COUNT(*) AS cnt FROM uf_pa_category " +
                "WHERE deleted = 0 AND status = 1 AND post_id IN (" + in + ") GROUP BY post_id")) {
            PaPostVO vo = result.get(((Number) m.get("post_id")).longValue());
            if (vo != null) {
                vo.setCategoryCount(((Number) m.get("cnt")).intValue());
            }
        }

        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT post_id, COUNT(*) AS cnt, SUM(weight) AS total FROM uf_pa_item " +
                "WHERE deleted = 0 AND status = 1 AND post_id IN (" + in + ") GROUP BY post_id")) {
            PaPostVO vo = result.get(((Number) m.get("post_id")).longValue());
            if (vo != null) {
                vo.setItemCount(((Number) m.get("cnt")).intValue());
                Object total = m.get("total");
                vo.setWeightTotal(total == null ? BigDecimal.ZERO : new BigDecimal(total.toString()));
            }
        }

        // LEFT JOIN 而不是「先数等级再比对」：一档都没配的能力项在等级表里没有行，
        // 用 COUNT(l.id) 才是 0；COUNT(*) 会把它算成 1，正好漏掉最该报的那种。
        // 内层按 item 分组筛出不足 2 档的项，外层再按岗位数个数，一个岗位只回一行。
        for (Map<String, Object> m : jdbc.queryForList(
                "SELECT post_id, COUNT(*) AS cnt FROM (" +
                "  SELECT i.post_id, i.id FROM uf_pa_item i " +
                "  LEFT JOIN uf_pa_item_level l ON l.item_id = i.id " +
                "  WHERE i.deleted = 0 AND i.status = 1 AND i.post_id IN (" + in + ") " +
                "  GROUP BY i.post_id, i.id HAVING COUNT(l.id) < 2" +
                ") t GROUP BY post_id")) {
            PaPostVO vo = result.get(((Number) m.get("post_id")).longValue());
            if (vo != null) {
                vo.setUnconfiguredItemCount(((Number) m.get("cnt")).intValue());
            }
        }
        return result;
    }

    /**
     * 岗位名唯一性。
     *
     * <p>普通岗位：同部门内唯一（跨部门同名允许，口径 #1）。
     * 通用岗位（{@code departmentId == null}）：全公司唯一 —— 库里两条 NULL 部门会撞唯一索引，
     * 这里先查一遍给出人能看懂的提示，不要让人对着「违反唯一约束」猜。</p>
     *
     * <p>注意 {@code eq("department_id", null)} 生成的是 {@code department_id = NULL}，
     * 在 SQL 里永远不成立，会静默漏掉重名校验 —— NULL 必须走 {@code isNull}。</p>
     */
    private void assertNameUnique(Long departmentId, String postName, Long excludeId) {
        if (postName == null || postName.isBlank()) {
            return;
        }
        LambdaQueryWrapper<PaPost> w = new LambdaQueryWrapper<PaPost>()
                .eq(PaPost::getPostName, postName.trim());
        if (departmentId == null) {
            w.isNull(PaPost::getDepartmentId);
        } else {
            w.eq(PaPost::getDepartmentId, departmentId);
        }
        if (excludeId != null) {
            w.ne(PaPost::getId, excludeId);
        }
        if (count(w) > 0) {
            throw new BusinessException(departmentId == null
                    ? "已存在名为「" + postName.trim() + "」的全公司通用岗位"
                    : "该部门下已存在名为「" + postName.trim() + "」的岗位");
        }
    }

    /** 从泛微部门表取部门名并校验存在 */
    private String resolveDepartmentName(Long departmentId) {
        List<String> names = jdbc.queryForList(
                "SELECT departmentmark FROM HrmDepartment WHERE id = ? AND ISNULL(canceled, 0) = 0",
                String.class, departmentId);
        if (names.isEmpty()) {
            throw new BusinessException("部门不存在或已撤销，请重新选择");
        }
        return names.get(0);
    }

    private PaPostVO toVO(PaPost post, PaPostVO stats) {
        PaPostVO vo = new PaPostVO();
        BeanUtils.copyProperties(post, vo);
        vo.setGeneric(post.isGeneric());
        if (stats != null) {
            vo.setCategoryCount(stats.getCategoryCount());
            vo.setItemCount(stats.getItemCount());
            vo.setWeightTotal(stats.getWeightTotal());
            vo.setUnconfiguredItemCount(stats.getUnconfiguredItemCount());
        }
        return vo;
    }
}
