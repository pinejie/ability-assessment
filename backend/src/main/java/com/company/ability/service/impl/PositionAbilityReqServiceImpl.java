package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.dto.PositionAbilityReqCreateDTO;
import com.company.ability.dto.PositionAbilityReqUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.PositionAbilityReq;
import com.company.ability.entity.PositionAbilityReqItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.PositionAbilityReqMapper;
import com.company.ability.service.AbilityElementService;
import com.company.ability.service.PositionAbilityReqItemService;
import com.company.ability.service.PositionAbilityReqService;
import com.company.ability.vo.PositionAbilityReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.ability.context.DimensionContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 岗位能力要求服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PositionAbilityReqServiceImpl
    extends ServiceImpl<PositionAbilityReqMapper, PositionAbilityReq>
    implements PositionAbilityReqService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AbilityElementService abilityElementService;
    private final PositionAbilityReqItemService itemService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPositionAbilityReq(PositionAbilityReqCreateDTO dto) {
        validateItems(dto.getItems());
        checkDuplicatePosition(dto.getDepartmentId(), dto.getJobTitleId(), null);

        // 创建主记录
        PositionAbilityReq req = new PositionAbilityReq();
        req.setDepartmentId(dto.getDepartmentId());
        req.setJobTitleId(dto.getJobTitleId());
        req.setDescription(dto.getDescription());
        save(req);

        // 创建明细记录
        List<PositionAbilityReqItem> items = new ArrayList<>();
        for (PositionAbilityReqCreateDTO.ElementItem elementItem : dto.getItems()) {
            // 检查能力要素是否存在
            AbilityElement element = abilityElementService.getById(elementItem.getElementId());
            if (element == null) {
                throw new BusinessException("能力要素不存在，ID: " + elementItem.getElementId());
            }

            PositionAbilityReqItem item = new PositionAbilityReqItem();
            item.setReqId(req.getId());
            item.setCategoryId(elementItem.getCategoryId());
            item.setElementId(elementItem.getElementId());
            item.setWeight(elementItem.getWeight());
            items.add(item);
        }

        // 逐条保存明细
        for (PositionAbilityReqItem item : items) {
            itemService.save(item);
        }

        log.info("创建岗位能力要求成功，ID: {}, 部门ID: {}, 岗位ID: {}, 明细数: {}",
                req.getId(), dto.getDepartmentId(), dto.getJobTitleId(), items.size());
        return req.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePositionAbilityReq(PositionAbilityReqUpdateDTO dto) {
        PositionAbilityReq req = getById(dto.getId());
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }

        // 更新主记录
        BeanUtils.copyProperties(dto, req);
        checkDuplicatePosition(req.getDepartmentId(), req.getJobTitleId(), req.getId());
        updateById(req);

        // 删除旧的明细
        LambdaQueryWrapper<PositionAbilityReqItem> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(PositionAbilityReqItem::getReqId, dto.getId());
        itemService.remove(deleteWrapper);

        // 创建新的明细
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            validateItems(dto.getItems());
            List<PositionAbilityReqItem> items = new ArrayList<>();
            for (PositionAbilityReqCreateDTO.ElementItem elementItem : dto.getItems()) {
                AbilityElement element = abilityElementService.getById(elementItem.getElementId());
                if (element == null) {
                    throw new BusinessException("能力要素不存在，ID: " + elementItem.getElementId());
                }

                PositionAbilityReqItem item = new PositionAbilityReqItem();
                item.setReqId(dto.getId());
                item.setCategoryId(elementItem.getCategoryId());
                item.setElementId(elementItem.getElementId());
                item.setWeight(elementItem.getWeight());
                items.add(item);
            }

            for (PositionAbilityReqItem item : items) {
                itemService.save(item);
            }
        }

        log.info("更新岗位能力要求成功，ID: {}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePositionAbilityReq(Long id) {
        PositionAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }

        // 删除明细
        LambdaQueryWrapper<PositionAbilityReqItem> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(PositionAbilityReqItem::getReqId, id);
        itemService.remove(deleteWrapper);

        // 删除主记录
        removeById(id);
        log.info("删除岗位能力要求成功，ID: {}", id);
    }

    @Override
    public PositionAbilityReqVO getPositionAbilityReqById(Long id) {
        PositionAbilityReq req = getById(id);
        if (req == null) {
            throw new BusinessException("岗位能力要求不存在");
        }
        return convertToVO(req);
    }

    @Override
    public PageResult<PositionAbilityReqVO> pagePositionAbilityReqs(PageRequest pageRequest) {
        pageRequest.validate();
        Page<PositionAbilityReq> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        QueryWrapper<PositionAbilityReq> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("id");
        Page<PositionAbilityReq> result = page(page, wrapper);
        List<PositionAbilityReqVO> voList = convertToVOs(result.getRecords());
        return PageResult.from(result, voList);
    }

    @Override
    public PageResult<PositionAbilityReqVO> pagePositionAbilityReqsByJobTitleId(Long jobTitleId, PageRequest pageRequest) {
        pageRequest.validate();
        Page<PositionAbilityReq> page = new Page<>(pageRequest.getPageNum(), pageRequest.getPageSize());
        LambdaQueryWrapper<PositionAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PositionAbilityReq::getJobTitleId, jobTitleId)
               .orderByAsc(PositionAbilityReq::getId);
        Page<PositionAbilityReq> result = page(page, wrapper);
        List<PositionAbilityReqVO> voList = convertToVOs(result.getRecords());
        return PageResult.from(result, voList);
    }

    @Override
    public PositionAbilityReqVO getByDepartmentAndJobTitle(Long departmentId, Long jobTitleId) {
        LambdaQueryWrapper<PositionAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PositionAbilityReq::getDepartmentId, departmentId)
               .eq(PositionAbilityReq::getJobTitleId, jobTitleId);
        PositionAbilityReq req = getOne(wrapper, false);
        if (req == null) {
            return null;
        }
        return convertToVO(req);
    }

    /**
     * 岗位权重校验：权重只到能力类别。
     * - 同一要素不能重复配置；
     * - 同一类别下的所有要素必须携带同一个权重（该权重作用于类别内每个要素，不按要素数均分）；
     * - 每个类别权重 0~100，不要求岗位合计 100（岗位可能只选部分全局类别）。
     * 全局类别权重只是带出模板，落到岗位后以岗位配置为准。
     */
    private void validateItems(List<PositionAbilityReqCreateDTO.ElementItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("能力要素列表不能为空");
        }
        Set<Long> elementIds = new HashSet<>();
        Map<Long, BigDecimal> categoryWeight = new HashMap<>();
        for (PositionAbilityReqCreateDTO.ElementItem item : items) {
            if (!elementIds.add(item.getElementId())) {
                throw new BusinessException("同一能力要素不能重复配置，ID: " + item.getElementId());
            }
            BigDecimal weight = item.getWeight();
            if (weight == null) {
                throw new BusinessException("能力类别权重不能为空，类别ID: " + item.getCategoryId());
            }
            if (weight.compareTo(BigDecimal.ZERO) < 0 || weight.compareTo(HUNDRED) > 0) {
                throw new BusinessException("能力类别权重必须在 0~100 之间，类别ID: " + item.getCategoryId());
            }
            BigDecimal existing = categoryWeight.putIfAbsent(item.getCategoryId(), weight);
            if (existing != null && existing.compareTo(weight) != 0) {
                throw new BusinessException("同一能力类别下的要素权重必须一致，类别ID: " + item.getCategoryId());
            }
        }
    }

    /** 同一维度下「部门 + 岗位」只能有一份岗位能力要求，避免算分取权重时命中多份配置 */
    private void checkDuplicatePosition(Long departmentId, Long jobTitleId, Long excludeId) {
        if (departmentId == null || jobTitleId == null) {
            return;
        }
        LambdaQueryWrapper<PositionAbilityReq> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PositionAbilityReq::getDepartmentId, departmentId)
               .eq(PositionAbilityReq::getJobTitleId, jobTitleId)
               .ne(excludeId != null, PositionAbilityReq::getId, excludeId);
        if (count(wrapper) > 0) {
            throw new BusinessException("该部门+岗位已配置能力要求，请直接编辑现有配置");
        }
    }

    private PositionAbilityReqVO convertToVO(PositionAbilityReq req) {
        List<PositionAbilityReqVO> list = convertToVOs(Collections.singletonList(req));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 批量组装 VO：当前页的记录一次性取明细与各类名称，避免逐行/逐明细查库（N+1）。
     */
    private List<PositionAbilityReqVO> convertToVOs(List<PositionAbilityReq> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> reqIds = reqs.stream().map(PositionAbilityReq::getId).collect(Collectors.toList());

        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT id, req_id, category_id, element_id, weight FROM uf_position_ability_req_item " +
                        "WHERE req_id IN (" + placeholders(reqIds.size()) + ") AND dimension_id = ? ORDER BY id",
                withDimension(reqIds));

        Set<Long> departmentIds = new HashSet<>();
        Set<Long> jobTitleIds = new HashSet<>();
        Set<Long> categoryIds = new HashSet<>();
        Set<Long> elementIds = new HashSet<>();
        for (PositionAbilityReq req : reqs) {
            if (req.getDepartmentId() != null) departmentIds.add(req.getDepartmentId());
            if (req.getJobTitleId() != null) jobTitleIds.add(req.getJobTitleId());
        }
        for (Map<String, Object> row : itemRows) {
            if (row.get("category_id") != null) categoryIds.add(num(row.get("category_id")));
            if (row.get("element_id") != null) elementIds.add(num(row.get("element_id")));
        }

        Map<Long, String> departmentNames = loadNames(
                "SELECT id, departmentmark AS name FROM HrmDepartment WHERE id IN (%s)", departmentIds);
        Map<Long, String> jobTitleNames = loadNames(
                "SELECT id, jobtitlemark AS name FROM HrmJobTitles WHERE id IN (%s)", jobTitleIds);
        Map<Long, String> categoryNames = loadNames(
                "SELECT id, category_name AS name FROM uf_ability_category WHERE id IN (%s)", categoryIds);
        Map<Long, String> elementNames = loadNames(
                "SELECT id, element_name AS name FROM uf_ability_element WHERE id IN (%s)", elementIds);

        Map<Long, List<Map<String, Object>>> itemsByReq = new HashMap<>();
        for (Map<String, Object> row : itemRows) {
            itemsByReq.computeIfAbsent(num(row.get("req_id")), k -> new ArrayList<>()).add(row);
        }

        List<PositionAbilityReqVO> result = new ArrayList<>(reqs.size());
        for (PositionAbilityReq req : reqs) {
            PositionAbilityReqVO vo = new PositionAbilityReqVO();
            BeanUtils.copyProperties(req, vo);
            if (req.getDepartmentId() != null) {
                vo.setDepartmentName(departmentNames.get(req.getDepartmentId()));
            }
            if (req.getJobTitleId() != null) {
                vo.setJobTitleName(jobTitleNames.get(req.getJobTitleId()));
            }

            List<PositionAbilityReqVO.PositionAbilityReqItemVO> itemVOs = new ArrayList<>();
            for (Map<String, Object> row : itemsByReq.getOrDefault(req.getId(), Collections.emptyList())) {
                PositionAbilityReqVO.PositionAbilityReqItemVO itemVO = new PositionAbilityReqVO.PositionAbilityReqItemVO();
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
                itemVO.setWeight((BigDecimal) row.get("weight"));
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
