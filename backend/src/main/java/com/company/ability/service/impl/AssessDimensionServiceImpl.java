package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.AssessDimensionUpdateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.entity.AssessDimension;
import com.company.ability.entity.AssessDimensionAuth;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.AssessDimensionAuthMapper;
import com.company.ability.mapper.AssessDimensionMapper;
import com.company.ability.service.AssessDimensionService;
import com.company.ability.vo.AssessDimensionAuthVO;
import com.company.ability.vo.AssessDimensionVO;
import com.company.ability.vo.DimensionScopeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测评维度服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessDimensionServiceImpl implements AssessDimensionService {

    /**
     * 删除维度前检查的业务表（有数据则禁止删除，防止业务数据变孤儿）。
     *
     * <p>清单随 sql/51 调整：旧模型 6 张表已在 P3 随数据 DROP，换成本维度真正还在用的
     * 新模型表 — 年度、岗位能力模型、人员映射、测评页。判定口径是「该维度下有没有
     * 已经产生业务数据」，不含维度自身的配置表（{@code uf_assess_dimension*}）。</p>
     */
    private static final List<String> DATA_CHECK_TABLES = List.of(
            "uf_assess_period", "uf_pa_post", "uf_pa_category", "uf_pa_item",
            "uf_pa_item_level", "uf_pa_band", "uf_pa_sheet");

    private final AssessDimensionMapper dimensionMapper;
    private final AssessDimensionAuthMapper authMapper;
    private final JdbcTemplate jdbc;

    @Override
    public List<AssessDimensionVO> listAll() {
        List<AssessDimension> dimensions = dimensionMapper.selectList(
                new LambdaQueryWrapper<AssessDimension>()
                        .orderByAsc(AssessDimension::getSort)
                        .orderByAsc(AssessDimension::getId));
        Map<Long, List<AssessDimensionAuth>> authsByDimension = loadAuthsByDimension();
        Map<Long, String> resourceNames = lookupResourceNames(personIds(authsByDimension));
        Map<Long, String> subcompanyNames = lookupSubcompanyNames(subcompanyIds(authsByDimension));
        return dimensions.stream()
                .map(d -> toVO(d, authsByDimension, resourceNames, subcompanyNames))
                .collect(Collectors.toList());
    }

    /** 全部授权里的分部 ID（含排除项，去重），用于批量补名称 */
    private List<Long> subcompanyIds(Map<Long, List<AssessDimensionAuth>> authsByDimension) {
        return authsByDimension.values().stream()
                .flatMap(List::stream)
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY)
                        || Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY))
                .map(AssessDimensionAuth::getAuthId)
                .distinct()
                .toList();
    }

    private List<Long> personIds(Map<Long, List<AssessDimensionAuth>> authsByDimension) {
        return authsByDimension.values().stream()
                .flatMap(List::stream)
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_RESOURCE))
                .map(AssessDimensionAuth::getAuthId)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AssessDimensionCreateDTO dto) {
        AssessDimension dimension = new AssessDimension();
        BeanUtils.copyProperties(dto, dimension);
        dimensionMapper.insert(dimension);
        log.info("创建测评维度成功，ID: {}, 名称: {}", dimension.getId(), dimension.getDimensionName());
        return dimension.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(AssessDimensionUpdateDTO dto) {
        AssessDimension dimension = requireDimension(dto.getId());
        // 保留原始值，防止 DTO 未传字段被 null 覆盖
        Integer originalStatus = dimension.getStatus();
        BeanUtils.copyProperties(dto, dimension, "id");
        if (dto.getStatus() == null) {
            dimension.setStatus(originalStatus);
        }
        dimensionMapper.updateById(dimension);
        log.info("更新测评维度成功，ID: {}", dto.getId());
    }

    @Override
    public boolean isDimensionAdmin(Long dimensionId, Long resourceId) {
        if (dimensionId == null || resourceId == null) {
            return false;
        }
        // 合并后（sql/58）管理员就是「人员授权」本身，不再单独存一张表。
        // 用 JdbcTemplate 而不是 mapper：MyBatis 一级缓存在同一事务内会缓存 selectCount 的结果，
        // 而同一次请求里「先判权限、后改授权名单」时读到旧值；生产上目前没有这种调用，
        // 但这是个只在特定顺序下才复现的坑，不如在这里绕开。查询本身很小（唯一索引命中）。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uf_assess_dimension_auth"
                        + " WHERE dimension_id = ? AND auth_type = ? AND auth_id = ?",
                Integer.class, dimensionId, AssessDimensionAuth.TYPE_RESOURCE, resourceId);
        return count != null && count > 0;
    }

    @Override
    public DimensionScopeVO currentScope(Long dimensionId) {
        DimensionScopeVO vo = new DimensionScopeVO();
        vo.setSubcompanyIds(List.of());
        vo.setDepartmentIds(List.of());
        if (dimensionId == null) {
            return vo;
        }
        List<AssessDimensionAuth> auths = authMapper.selectList(
                new LambdaQueryWrapper<AssessDimensionAuth>()
                        .eq(AssessDimensionAuth::getDimensionId, dimensionId));

        List<Long> granted = auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY))
                .map(AssessDimensionAuth::getAuthId)
                .toList();
        if (granted.isEmpty()) {
            // 没配「授权分部」= 不做范围收窄（这类维度通常只有管理员能进）。
            // 不能返回空部门列表，那会被前端理解成「一个部门都没有」。
            vo.setRestricted(false);
            return vo;
        }
        List<Long> excluded = auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY))
                .map(AssessDimensionAuth::getAuthId)
                .toList();

        Map<Long, List<Long>> children = loadSubcompanyChildren();
        Set<Long> inScope = new LinkedHashSet<>();
        // 排除先算：子树扣减要作用在展开后的集合上，否则「排除父分部、它下面还有被授权的子分部」
        // 这种配置的结果会取决于处理顺序。
        Set<Long> excludedSubtree = new HashSet<>();
        excluded.forEach(id -> collectSubtree(id, children, excludedSubtree));
        for (Long root : granted) {
            Set<Long> subtree = new HashSet<>();
            collectSubtree(root, children, subtree);
            subtree.removeAll(excludedSubtree);
            inScope.addAll(subtree);
        }

        List<Long> subcompanyIds = new ArrayList<>(inScope);
        vo.setRestricted(true);
        vo.setSubcompanyIds(subcompanyIds);
        vo.setDepartmentIds(departmentIdsOf(subcompanyIds));
        return vo;
    }

    /** 分部 id → 直接下级分部 id 列表（仅启用行） */
    private Map<Long, List<Long>> loadSubcompanyChildren() {
        Map<Long, List<Long>> children = new HashMap<>();
        jdbc.query("SELECT id, supsubcomid FROM HrmSubCompany WHERE COALESCE(canceled, 0) = 0", rs -> {
            long id = rs.getLong("id");
            Object sup = rs.getObject("supsubcomid");
            if (sup == null) {
                return;
            }
            long parentId = ((Number) sup).longValue();
            // 上级是 0 表示顶层，不产生父子边；自指或成环由 collectSubtree 的 visited 兜住
            if (parentId != 0L) {
                children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
            }
        });
        return children;
    }

    /** 收集 root 及其全部下级的 id（visited 同时充当环检测，泛微层级脏数据不会死循环） */
    private void collectSubtree(Long root, Map<Long, List<Long>> children, Set<Long> visited) {
        if (root == null || !visited.add(root)) {
            return;
        }
        for (Long child : children.getOrDefault(root, List.of())) {
            collectSubtree(child, children, visited);
        }
    }

    /** 这些分部下的部门 ID（只取启用部门） */
    private List<Long> departmentIdsOf(List<Long> subcompanyIds) {
        if (subcompanyIds == null || subcompanyIds.isEmpty()) {
            return List.of();
        }
        String placeholders = subcompanyIds.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.queryForList(
                "SELECT id FROM HrmDepartment WHERE COALESCE(canceled, 0) = 0"
                        + " AND subcompanyid1 IN (" + placeholders + ") ORDER BY id",
                Long.class, subcompanyIds.toArray());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireDimension(id);
        // 原「默认维度 id=1 禁止删除」特例已移除（2026-10-22 默认维度经清理脚本删除，见 sql/22）；
        // 通用护栏保留：维度下有业务数据即拒绝删除。
        for (String table : DATA_CHECK_TABLES) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE dimension_id = ?", Long.class, id);
            if (count != null && count > 0) {
                throw new BusinessException("该维度下已有业务数据（" + table + " " + count + " 条），不允许删除");
            }
        }
        authMapper.delete(new LambdaQueryWrapper<AssessDimensionAuth>()
                .eq(AssessDimensionAuth::getDimensionId, id));
        dimensionMapper.deleteById(id);
        log.info("删除测评维度成功，ID: {}", id);
    }

    @Override
    public List<AssessDimensionAuthVO> listAuths(Long dimensionId) {
        requireDimension(dimensionId);
        List<AssessDimensionAuth> auths = authMapper.selectList(
                new LambdaQueryWrapper<AssessDimensionAuth>()
                        .eq(AssessDimensionAuth::getDimensionId, dimensionId)
                        .orderByAsc(AssessDimensionAuth::getId));
        if (auths.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, String> subNames = lookupNames(auths, AssessDimensionAuth.TYPE_SUBCOMPANY,
                "SELECT id, subcompanyname AS name FROM HrmSubCompany WHERE id IN (%s)");
        Map<Long, String> excludedSubNames = lookupNames(auths, AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY,
                "SELECT id, subcompanyname AS name FROM HrmSubCompany WHERE id IN (%s)");
        Map<Long, String> resNames = lookupNames(auths, AssessDimensionAuth.TYPE_RESOURCE,
                "SELECT id, lastname AS name FROM HrmResource WHERE id IN (%s)");

        List<AssessDimensionAuthVO> result = new ArrayList<>();
        for (AssessDimensionAuth auth : auths) {
            AssessDimensionAuthVO vo = new AssessDimensionAuthVO();
            vo.setId(auth.getId());
            vo.setAuthType(auth.getAuthType());
            vo.setAuthId(auth.getAuthId());
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY)) {
                vo.setAuthName(subNames.get(auth.getAuthId()));
            } else if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY)) {
                vo.setAuthName(excludedSubNames.get(auth.getAuthId()));
            } else {
                vo.setAuthName(resNames.get(auth.getAuthId()));
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAuths(Long dimensionId, List<DimensionAuthItemDTO> items) {
        requireDimension(dimensionId);

        // 去重校验
        Set<String> keys = new java.util.HashSet<>();
        Set<Long> grantedSubcompanys = new java.util.HashSet<>();
        Set<Long> excludedSubcompanys = new java.util.HashSet<>();
        for (DimensionAuthItemDTO item : items == null ? List.<DimensionAuthItemDTO>of() : items) {
            if (item.getAuthType() == null
                    || (item.getAuthType() != AssessDimensionAuth.TYPE_SUBCOMPANY
                        && item.getAuthType() != AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY
                        && item.getAuthType() != AssessDimensionAuth.TYPE_RESOURCE)) {
                throw new BusinessException("授权类型不合法（1-分部，2-人员，3-排除分部）");
            }
            if (!keys.add(item.getAuthType() + "#" + item.getAuthId())) {
                throw new BusinessException("授权条目重复：类型 " + item.getAuthType() + " 对象 " + item.getAuthId());
            }
            if (Objects.equals(item.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY)) {
                grantedSubcompanys.add(item.getAuthId());
            } else if (Objects.equals(item.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY)) {
                excludedSubcompanys.add(item.getAuthId());
            }
        }

        // 同一个分部不能既授权又排除。二者叠加的结果是这个分部分到授权、又被排除掉，
        // 一个人都不会进来 —— 这份授权等于没配，只可能是误操作，所以直接拒绝，
        // 不留给「排除优先」之类的隐式规则去猜（前端也已禁止勾选）。
        for (Long id : excludedSubcompanys) {
            if (grantedSubcompanys.contains(id)) {
                throw new BusinessException("分部 " + id + " 不能同时被授权和排除");
            }
        }

        // 整体覆盖
        authMapper.delete(new LambdaQueryWrapper<AssessDimensionAuth>()
                .eq(AssessDimensionAuth::getDimensionId, dimensionId));
        if (items != null) {
            for (DimensionAuthItemDTO item : items) {
                AssessDimensionAuth auth = new AssessDimensionAuth();
                auth.setDimensionId(dimensionId);
                auth.setAuthType(item.getAuthType());
                auth.setAuthId(item.getAuthId());
                authMapper.insert(auth);
            }
        }
        log.info("保存维度授权成功，维度ID: {}, 条目数: {}", dimensionId, items == null ? 0 : items.size());
    }

    @Override
    public List<AssessDimensionVO> authorized(Long resourceId, boolean admin) {
        List<AssessDimension> dimensions = dimensionMapper.selectList(
                new LambdaQueryWrapper<AssessDimension>()
                        .eq(AssessDimension::getStatus, 1)
                        .orderByAsc(AssessDimension::getSort)
                        .orderByAsc(AssessDimension::getId));
        Map<Long, List<AssessDimensionAuth>> authByDimension = authMapper
                .selectList(new LambdaQueryWrapper<AssessDimensionAuth>())
                .stream()
                .collect(Collectors.groupingBy(AssessDimensionAuth::getDimensionId,
                        LinkedHashMap::new, Collectors.toList()));
        // 维度的授权名单在顶栏切换器里用不上（只关心「有哪些维度 / 叫什么」），
        // 取名称要再查两张表，这里跳过，省掉每次请求的两次查询。
        if (admin) {
            return dimensions.stream()
                    .map(d -> toVO(d, authByDimension, Map.of(), Map.of()))
                    .collect(Collectors.toList());
        }

        Set<Long> subcompanyChain = Collections.emptySet();
        if (resourceId != null) {
            List<Long> subs = jdbc.queryForList(
                    "SELECT subcompanyid1 FROM HrmResource WHERE id = ?", Long.class, resourceId);
            subcompanyChain = subcompanyChainWithAncestors(subs.isEmpty() ? null : subs.get(0));
        }

        List<AssessDimensionVO> result = new ArrayList<>();
        for (AssessDimension d : dimensions) {
            // 维度管理员就是「人员授权」，与普通授权走同一个判断，不再额外豁免（sql/58 合并后）
            if (isAuthorizedFor(d.getId(), authByDimension.get(d.getId()), resourceId, subcompanyChain)) {
                result.add(toVO(d, authByDimension, Map.of(), Map.of()));
            }
        }
        return result;
    }

    @Override
    public boolean isAuthorizedForUser(Long dimensionId, Long resourceId) {
        if (resourceId == null) {
            return true; // 无登录身份（泛微钩子/调试匿名）：宽松，与 DimensionContext 口径一致
        }
        List<AssessDimensionAuth> auths = authMapper.selectList(
                new LambdaQueryWrapper<AssessDimensionAuth>()
                        .eq(AssessDimensionAuth::getDimensionId, dimensionId));
        List<Long> subs = jdbc.queryForList(
                "SELECT subcompanyid1 FROM HrmResource WHERE id = ?", Long.class, resourceId);
        Set<Long> subcompanyChain = subcompanyChainWithAncestors(subs.isEmpty() ? null : subs.get(0));
        return isAuthorizedFor(dimensionId, auths, resourceId, subcompanyChain);
    }

    /**
     * 授权语义（2026-10-22 子树匹配；2026-11-23 合并管理员；同日新增排除分部）：
     *
     * <ol>
     *   <li>无授权行 = 仅系统管理员可用（系统管理员经 authorized()/DimensionContextFilter 豁免直达，
     *       不进本判断）</li>
     *   <li>人员在「人员授权」里 → 直接放行。<b>人员授权压过排除分部</b>：把他一个人手工加进来
     *       是比按组织划范围更强的意图，若还能被分部排除掉，这条配置就没有可信的用法</li>
     *   <li>人员分部链（本级→逐级上级）命中授权分部，<b>且不命中任何排除分部</b> → 放行。
     *       排除同样含下级：排除中达科技，它下面新设的分支也不归本维度</li>
     * </ol>
     *
     * <p>resourceId 为空（无登录身份）：宽松放行——泛微钩子等内部集成调用不受租户授权约束。</p>
     */
    private boolean isAuthorizedFor(Long dimensionId, List<AssessDimensionAuth> auths,
                                    Long resourceId, Set<Long> subcompanyChain) {
        if (resourceId == null) {
            return true;
        }
        if (auths == null || auths.isEmpty()) {
            return false;
        }
        // 先单独扫人员授权，不能和分部判断混在同一个循环里：
        // 若列表里排除项排在人员授权项之前，混着写会先 return false，
        // 结果取决于数据库行序 —— 这类 bug 只在某些维度上复现，最难查。
        for (AssessDimensionAuth auth : auths) {
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_RESOURCE)
                    && Objects.equals(auth.getAuthId(), resourceId)) {
                return true;
            }
        }
        boolean bySubcompany = false;
        for (AssessDimensionAuth auth : auths) {
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY)
                    && subcompanyChain.contains(auth.getAuthId())) {
                return false;
            }
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY)
                    && subcompanyChain.contains(auth.getAuthId())) {
                bySubcompany = true;
            }
        }
        return bySubcompany;
    }

    /**
     * 人员分部 → 沿上级链到根的 id 集合（含本级）。分部表行数级小（一次全量拉上级映射，
     * 内存走链），深度上限 32 防御泛微层级脏数据成环。
     */
    private Set<Long> subcompanyChainWithAncestors(Long subcompanyId) {
        Set<Long> chain = new HashSet<>();
        if (subcompanyId == null) {
            return chain;
        }
        Map<Long, Long> parents = loadSubcompanyParents();
        Long current = subcompanyId;
        int depth = 0;
        while (current != null && current != 0L && depth++ < 32 && chain.add(current)) {
            current = parents.get(current);
        }
        return chain;
    }

    /** 分部 id → 上级分部 id（仅启用行；无上级/已注销记为链终点） */
    private Map<Long, Long> loadSubcompanyParents() {
        Map<Long, Long> parents = new HashMap<>();
        jdbc.query("SELECT id, supsubcomid FROM HrmSubCompany WHERE COALESCE(canceled, 0) = 0",
                rs -> {
                    Object sup = rs.getObject("supsubcomid");
                    parents.put(rs.getLong("id"), sup == null ? null : ((Number) sup).longValue());
                });
        return parents;
    }

    /** 按类型批量查泛微对象名称（表为平台表，只读） */
    private Map<Long, String> lookupNames(List<AssessDimensionAuth> auths, int authType, String sqlTemplate) {
        List<Long> ids = auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), authType))
                .map(AssessDimensionAuth::getAuthId)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, String> names = new HashMap<>();
        jdbc.query(String.format(sqlTemplate, placeholders), rs -> {
            names.put(rs.getLong("id"), rs.getString("name"));
        }, ids.toArray());
        return names;
    }

    /**
     * 全部维度的授权名单（sql/58）：分部与人员都在 uf_assess_dimension_auth 一张表里，
     * 一次全量拉回内存分组，不必按维度逐个查（那会变成 N+1）。名称统一批量补。
     */
    private Map<Long, List<AssessDimensionAuth>> loadAuthsByDimension() {
        return authMapper.selectList(new LambdaQueryWrapper<AssessDimensionAuth>()
                        .orderByAsc(AssessDimensionAuth::getDimensionId)
                        .orderByAsc(AssessDimensionAuth::getId))
                .stream()
                .collect(Collectors.groupingBy(AssessDimensionAuth::getDimensionId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, String> lookupResourceNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT id, lastname AS name FROM HrmResource WHERE id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, ids.toArray());
        return names;
    }

    private Map<Long, String> lookupSubcompanyNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        Map<Long, String> names = new HashMap<>();
        jdbc.query("SELECT id, subcompanyname AS name FROM HrmSubCompany WHERE id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, ids.toArray());
        return names;
    }

    private AssessDimension requireDimension(Long id) {
        AssessDimension dimension = dimensionMapper.selectById(id);
        if (dimension == null) {
            throw new BusinessException("维度不存在，ID: " + id);
        }
        return dimension;
    }

    /**
     * 维度 VO。授权名单与「所属分部 / 维度管理员」合并后（sql/58），
     * 列表页展示的「归属」「管理员」「授权」都来自同一份数据，不再有第二个入口。
     */
    private AssessDimensionVO toVO(AssessDimension dimension, Map<Long, List<AssessDimensionAuth>> authsByDimension,
                                   Map<Long, String> resourceNames, Map<Long, String> subcompanyNames) {
        AssessDimensionVO vo = new AssessDimensionVO();
        BeanUtils.copyProperties(dimension, vo);
        List<AssessDimensionAuth> auths = authsByDimension.getOrDefault(dimension.getId(), List.of());
        vo.setSubcompanys(auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY))
                .map(a -> authItem(a.getAuthId(), subcompanyNames))
                .toList());
        vo.setExcludedSubcompanys(auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_EXCLUDED_SUBCOMPANY))
                .map(a -> authItem(a.getAuthId(), subcompanyNames))
                .toList());
        vo.setPersons(auths.stream()
                .filter(a -> Objects.equals(a.getAuthType(), AssessDimensionAuth.TYPE_RESOURCE))
                .map(a -> authItem(a.getAuthId(), resourceNames))
                .toList());
        vo.setAuthCount((long) auths.size());
        return vo;
    }

    private AssessDimensionVO.AuthItem authItem(Long id, Map<Long, String> names) {
        AssessDimensionVO.AuthItem item = new AssessDimensionVO.AuthItem();
        item.setId(id);
        item.setName(names.get(id));
        return item;
    }
}
