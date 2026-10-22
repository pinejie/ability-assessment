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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /** 默认维度，禁止删除（存量数据挂载点） */
    private static final long DEFAULT_DIMENSION_ID = 1L;

    /** 删除维度前检查的业务表（有数据则禁止删除，防止业务数据变孤儿） */
    private static final List<String> DATA_CHECK_TABLES = List.of(
            "uf_ability_category", "uf_ability_element", "uf_assess_period", "uf_assess_sheet",
            "uf_org_ability_req", "uf_position_ability_req_new", "uf_user_ability_req_new");

    private final AssessDimensionMapper dimensionMapper;
    private final AssessDimensionAuthMapper authMapper;
    private final JdbcTemplate jdbc;

    @Override
    public List<AssessDimensionVO> listAll() {
        List<AssessDimension> dimensions = dimensionMapper.selectList(
                new LambdaQueryWrapper<AssessDimension>()
                        .orderByAsc(AssessDimension::getSort)
                        .orderByAsc(AssessDimension::getId));
        Map<Long, Long> authCounts = countAuthsByDimension();
        return dimensions.stream()
                .map(d -> toVO(d, authCounts.getOrDefault(d.getId(), 0L)))
                .collect(Collectors.toList());
    }

    @Override
    public Long create(AssessDimensionCreateDTO dto) {
        AssessDimension dimension = new AssessDimension();
        BeanUtils.copyProperties(dto, dimension);
        dimensionMapper.insert(dimension);
        log.info("创建测评维度成功，ID: {}, 名称: {}", dimension.getId(), dimension.getDimensionName());
        return dimension.getId();
    }

    @Override
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
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireDimension(id);
        if (Objects.equals(id, DEFAULT_DIMENSION_ID)) {
            throw new BusinessException("默认维度不允许删除");
        }
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
        for (DimensionAuthItemDTO item : items == null ? List.<DimensionAuthItemDTO>of() : items) {
            if (item.getAuthType() == null
                    || (item.getAuthType() != AssessDimensionAuth.TYPE_SUBCOMPANY
                        && item.getAuthType() != AssessDimensionAuth.TYPE_RESOURCE)) {
                throw new BusinessException("授权类型不合法（1-分部，2-人员）");
            }
            if (!keys.add(item.getAuthType() + "#" + item.getAuthId())) {
                throw new BusinessException("授权条目重复：类型 " + item.getAuthType() + " 对象 " + item.getAuthId());
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

        // 管理员：全部启用维度可用（顶栏切换器直达所有租户）
        if (admin) {
            return dimensions.stream()
                    .map(d -> toVO(d, authByDimension.getOrDefault(d.getId(), List.of()).size()))
                    .collect(Collectors.toList());
        }

        Long subcompanyId = null;
        if (resourceId != null) {
            List<Long> subs = jdbc.queryForList(
                    "SELECT subcompanyid1 FROM HrmResource WHERE id = ?", Long.class, resourceId);
            subcompanyId = subs.isEmpty() ? null : subs.get(0);
        }

        List<AssessDimensionVO> result = new ArrayList<>();
        for (AssessDimension d : dimensions) {
            if (isAuthorizedFor(d.getId(), authByDimension.get(d.getId()), resourceId, subcompanyId)) {
                result.add(toVO(d, authByDimension.getOrDefault(d.getId(), List.of()).size()));
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
        Long subcompanyId = subs.isEmpty() ? null : subs.get(0);
        return isAuthorizedFor(dimensionId, auths, resourceId, subcompanyId);
    }

    /**
     * 授权语义（2026-10-22 调整）：
     * 无授权行 = 仅管理员可用（管理员经 authorized()/DimensionContextFilter 豁免直达，不进本判断）；
     * 配置后，要求命中「人员 ID 精确授权」或「人员一级分部（subcompanyid1）授权」。
     * resourceId 为空（无登录身份）：宽松放行——泛微钩子等内部集成调用不受租户授权约束。
     */
    private boolean isAuthorizedFor(Long dimensionId, List<AssessDimensionAuth> auths,
                                    Long resourceId, Long subcompanyId) {
        if (resourceId == null) {
            return true;
        }
        if (auths == null || auths.isEmpty()) {
            return false;
        }
        for (AssessDimensionAuth auth : auths) {
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_RESOURCE)
                    && Objects.equals(auth.getAuthId(), resourceId)) {
                return true;
            }
            if (Objects.equals(auth.getAuthType(), AssessDimensionAuth.TYPE_SUBCOMPANY)
                    && subcompanyId != null && Objects.equals(auth.getAuthId(), subcompanyId)) {
                return true;
            }
        }
        return false;
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

    private Map<Long, Long> countAuthsByDimension() {
        Map<Long, Long> counts = new HashMap<>();
        for (AssessDimensionAuth auth : authMapper.selectList(null)) {
            counts.merge(auth.getDimensionId(), 1L, Long::sum);
        }
        return counts;
    }

    private AssessDimension requireDimension(Long id) {
        AssessDimension dimension = dimensionMapper.selectById(id);
        if (dimension == null) {
            throw new BusinessException("维度不存在，ID: " + id);
        }
        return dimension;
    }

    private AssessDimensionVO toVO(AssessDimension dimension, long authCount) {
        AssessDimensionVO vo = new AssessDimensionVO();
        BeanUtils.copyProperties(dimension, vo);
        vo.setAuthCount(authCount);
        return vo;
    }
}
