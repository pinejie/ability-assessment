package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.UserAbilityReqNewCreateDTO;
import com.company.ability.dto.UserAbilityReqNewUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.entity.UserAbilityReqNew;
import com.company.ability.entity.UserAbilityReqItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.UserAbilityReqNewMapper;
import com.company.ability.service.AbilityElementLevelService;
import com.company.ability.service.AssessSheetSyncService;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.UserAbilityReqNewService;
import com.company.ability.service.UserAbilityReqItemService;
import com.company.ability.vo.UserAbilityReqNewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.ability.context.DimensionContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人员能力要求主表服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAbilityReqNewServiceImpl
    extends ServiceImpl<UserAbilityReqNewMapper, UserAbilityReqNew>
    implements UserAbilityReqNewService {

    private final AbilityElementService abilityElementService;
    private final AbilityElementLevelService abilityElementLevelService;
    private final UserAbilityReqItemService itemService;
    private final AssessSheetSyncService assessSheetSyncService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUserAbilityReq(UserAbilityReqNewCreateDTO dto) {
        // 创建主记录
        UserAbilityReqNew req = new UserAbilityReqNew();
        req.setResourceId(dto.getResourceId());
        req.setDescription(dto.getDescription());
        req.setAssessYear(dto.getAssessYear() != null ? dto.getAssessYear() : LocalDate.now().getYear());
        save(req);

        // 创建明细记录
        List<UserAbilityReqItem> items = new ArrayList<>();
        for (UserAbilityReqNewCreateDTO.ElementItem elementItem : dto.getItems()) {
            // 检查能力要素是否存在
            AbilityElement element = abilityElementService.getById(elementItem.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在，ID: " + elementItem.getElementId());
            }

            // 检查等级配置是否存在
            if (elementItem.getLevelId() != null) {
                AbilityElementLevel level = abilityElementLevelService.getById(elementItem.getLevelId());
                if (level == null) {
                    throw new BusinessException("等级配置不存在，ID: " + elementItem.getLevelId());
                }
            }

            UserAbilityReqItem item = new UserAbilityReqItem();
            item.setReqId(req.getId());
            item.setCategoryId(elementItem.getCategoryId());
            item.setElementId(elementItem.getElementId());
            item.setLevelId(elementItem.getLevelId());
            item.setScore(elementItem.getScore());
            items.add(item);
        }

        // 逐条保存明细
        for (UserAbilityReqItem item : items) {
            itemService.save(item);
        }

        // 同步测评汇总页面结构（与保存同一事务，失败则整体回滚）
        assessSheetSyncService.syncRequirement(req.getResourceId(), req.getAssessYear(), true);

        log.info("创建人员能力要求成功，ID: {}, 人员ID: {}, 明细数: {}",
                req.getId(), dto.getResourceId(), items.size());
        return req.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserAbilityReq(UserAbilityReqNewUpdateDTO dto) {
        UserAbilityReqNew req = getById(dto.getId());
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }

        // 更新主记录
        if (dto.getDescription() != null) {
            req.setDescription(dto.getDescription());
        }
        updateById(req);

        // 删除旧的明细
        LambdaQueryWrapper<UserAbilityReqItem> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(UserAbilityReqItem::getReqId, dto.getId());
        itemService.remove(deleteWrapper);

        // 创建新的明细
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            List<UserAbilityReqItem> items = new ArrayList<>();
            for (UserAbilityReqNewUpdateDTO.ElementItem elementItem : dto.getItems()) {
                AbilityElement element = abilityElementService.getById(elementItem.getElementId());
                if (element == null) {
                    throw new BusinessException("能力要素不存在，ID: " + elementItem.getElementId());
                }

                if (elementItem.getLevelId() != null) {
                    AbilityElementLevel level = abilityElementLevelService.getById(elementItem.getLevelId());
                    if (level == null) {
                        throw new BusinessException("等级配置不存在，ID: " + elementItem.getLevelId());
                    }
                }

                UserAbilityReqItem item = new UserAbilityReqItem();
                item.setReqId(dto.getId());
                item.setCategoryId(elementItem.getCategoryId());
                item.setElementId(elementItem.getElementId());
                item.setLevelId(elementItem.getLevelId());
                item.setScore(elementItem.getScore());
                items.add(item);
            }

            for (UserAbilityReqItem item : items) {
                itemService.save(item);
            }
        }

        // 同步测评汇总页面结构；syncAssess=false 时仅标记与人员能力要求不一致
        boolean syncAssess = dto.getSyncAssess() == null || dto.getSyncAssess();
        assessSheetSyncService.syncRequirement(req.getResourceId(), req.getAssessYear(), syncAssess);

        log.info("更新人员能力要求成功，ID: {}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserAbilityReq(Long id) {
        UserAbilityReqNew req = getById(id);
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }

        // 测评侧校验：年度已关闭、已有评价数据时拒绝；无评价数据时同时删除测评页面
        assessSheetSyncService.removeRequirement(req.getResourceId(), req.getAssessYear());

        // 删除明细
        LambdaQueryWrapper<UserAbilityReqItem> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(UserAbilityReqItem::getReqId, id);
        itemService.remove(deleteWrapper);

        // 删除主记录
        removeById(id);
        log.info("删除人员能力要求成功，ID: {}", id);
    }

    @Override
    public UserAbilityReqNewVO getUserAbilityReqById(Long id) {
        UserAbilityReqNew req = getById(id);
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }
        return convertToVO(req);
    }

    @Override
    public PageResult<UserAbilityReqNewVO> pageUserAbilityReqs(PageRequest pageRequest, Integer year) {
        pageRequest.validate();
        Page<UserAbilityReqNew> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<UserAbilityReqNew> wrapper = new QueryWrapper<>();
        if (year != null) {
            wrapper.eq("assess_year", year);
        }
        wrapper.orderByAsc("id");
        Page<UserAbilityReqNew> result = page(page, wrapper);
        List<UserAbilityReqNewVO> voList = convertToVOs(result.getRecords());
        return PageResult.from(result, voList);
    }

    @Override
    public UserAbilityReqNewVO getByResourceId(Long resourceId) {
        LambdaQueryWrapper<UserAbilityReqNew> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAbilityReqNew::getResourceId, resourceId);
        UserAbilityReqNew req = getOne(wrapper, false);
        if (req == null) {
            return null;
        }
        return convertToVO(req);
    }

    private UserAbilityReqNewVO convertToVO(UserAbilityReqNew req) {
        List<UserAbilityReqNewVO> list = convertToVOs(Collections.singletonList(req));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 批量组装 VO：当前页的记录一次性取人员姓名、明细及类别/要素/等级名称，避免 N+1。
     */
    private List<UserAbilityReqNewVO> convertToVOs(List<UserAbilityReqNew> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> reqIds = reqs.stream().map(UserAbilityReqNew::getId).collect(Collectors.toList());

        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT id, req_id, category_id, element_id, level_id, score FROM uf_user_ability_req_item " +
                        "WHERE req_id IN (" + placeholders(reqIds.size()) + ") AND dimension_id = ? ORDER BY id",
                withDimension(reqIds));

        Set<Long> resourceIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        Set<Long> elementIds = new HashSet<>();
        Set<Long> levelIds = new HashSet<>();
        for (UserAbilityReqNew req : reqs) {
            if (req.getResourceId() != null) resourceIds.add(req.getResourceId());
        }
        for (Map<String, Object> row : itemRows) {
            if (row.get("category_id") != null) categoryIds.add(num(row.get("category_id")));
            if (row.get("element_id") != null) elementIds.add(num(row.get("element_id")));
            if (row.get("level_id") != null) levelIds.add(num(row.get("level_id")));
        }

        Map<Long, String> resourceNames = loadNames(
                "SELECT id, lastname AS name FROM HrmResource WHERE id IN (%s)", resourceIds);
        Map<Long, String> categoryNames = loadNames(
                "SELECT id, category_name AS name FROM uf_ability_category WHERE id IN (%s)", categoryIds);
        Map<Long, String> elementNames = loadNames(
                "SELECT id, element_name AS name FROM uf_ability_element WHERE id IN (%s)", elementIds);

        Map<Long, Map<String, Object>> levels = new HashMap<>();
        if (!levelIds.isEmpty()) {
            List<Map<String, Object>> levelRows = jdbcTemplate.queryForList(
                    "SELECT id, level_name, level_requirement FROM uf_ability_element_level WHERE id IN (" +
                            placeholders(levelIds.size()) + ")", levelIds.toArray());
            for (Map<String, Object> row : levelRows) {
                levels.put(num(row.get("id")), row);
            }
        }

        Map<Long, List<Map<String, Object>>> itemsByReq = new HashMap<>();
        for (Map<String, Object> row : itemRows) {
            itemsByReq.computeIfAbsent(num(row.get("req_id")), k -> new ArrayList<>()).add(row);
        }

        List<UserAbilityReqNewVO> result = new ArrayList<>(reqs.size());
        for (UserAbilityReqNew req : reqs) {
            UserAbilityReqNewVO vo = new UserAbilityReqNewVO();
            BeanUtils.copyProperties(req, vo);
            if (req.getResourceId() != null) {
                vo.setResourceLastName(resourceNames.get(req.getResourceId()));
            }

            List<UserAbilityReqNewVO.UserAbilityReqItemVO> itemVOs = new ArrayList<>();
            for (Map<String, Object> row : itemsByReq.getOrDefault(req.getId(), Collections.emptyList())) {
                UserAbilityReqNewVO.UserAbilityReqItemVO itemVO = new UserAbilityReqNewVO.UserAbilityReqItemVO();
                itemVO.setId(num(row.get("id")));
                if (row.get("category_id") != null) {
                    long categoryId = num(row.get("category_id"));
                    itemVO.setCategoryId(categoryId);
                    itemVO.setCategoryName(categoryNames.get(categoryId));
                }
                if (row.get("element_id") != null) {
                    long elementId = num(row.get("element_id"));
                    itemVO.setElementId(elementId);
                    itemVO.setElementName(elementNames.get(elementId));
                }
                if (row.get("level_id") != null) {
                    long levelId = num(row.get("level_id"));
                    itemVO.setLevelId(levelId);
                    Map<String, Object> level = levels.get(levelId);
                    if (level != null) {
                        itemVO.setLevelName((String) level.get("level_name"));
                        itemVO.setLevelRequirement((String) level.get("level_requirement"));
                    }
                }
                itemVO.setScore((java.math.BigDecimal) row.get("score"));
                itemVOs.add(itemVO);
            }
            vo.setItems(itemVOs);
            result.add(vo);
        }
        return result;
    }

    /** 按 ID 集合批量取「id -> 名称」，缺行时对应名称为 null（与原逐条查询吞异常的行为一致）。 */
    private Map<Long, String> loadNames(String sqlTemplate, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                String.format(sqlTemplate, placeholders(ids.size())), ids.toArray());
        Map<Long, String> names = new HashMap<>();
        for (Map<String, Object> row : rows) {
            names.put(num(row.get("id")), (String) row.get("name"));
        }
        return names;
    }

    private Object[] withDimension(List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) {
            args[i] = ids.get(i);
        }
        args[ids.size()] = DimensionContext.getOrDefault();
        return args;
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
