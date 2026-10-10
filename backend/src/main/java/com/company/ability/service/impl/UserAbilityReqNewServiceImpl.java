package com.company.ability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.company.ability.dto.UserAbilityReqNewCreateDTO;
import com.company.ability.dto.UserAbilityReqNewUpdateDTO;
import com.company.ability.entity.AbilityElement;
import com.company.ability.entity.AbilityElementLevel;
import com.company.ability.entity.UserAbilityReqNew;
import com.company.ability.entity.UserAbilityReqItem;
import com.company.ability.exception.BusinessException;
import com.company.ability.mapper.UserAbilityReqNewMapper;
import com.company.ability.service.AbilityElementLevelService;
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

import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUserAbilityReq(UserAbilityReqNewCreateDTO dto) {
        // 创建主记录
        UserAbilityReqNew req = new UserAbilityReqNew();
        req.setResourceId(dto.getResourceId());
        req.setDescription(dto.getDescription());
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

        log.info("更新人员能力要求成功，ID: {}", dto.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUserAbilityReq(Long id) {
        UserAbilityReqNew req = getById(id);
        if (req == null) {
            throw new BusinessException("人员能力要求不存在");
        }

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
    public List<UserAbilityReqNewVO> listAllUserAbilityReqs() {
        List<UserAbilityReqNew> list = list();
        return list.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
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
        UserAbilityReqNewVO vo = new UserAbilityReqNewVO();
        BeanUtils.copyProperties(req, vo);

        // 查询人员姓名
        try {
            String lastName = jdbcTemplate.queryForObject(
                "SELECT lastname FROM HrmResource WHERE id = ?",
                String.class,
                req.getResourceId()
            );
            vo.setResourceLastName(lastName);
        } catch (Exception e) {
            log.warn("查询人员姓名失败，人员ID: {}", req.getResourceId());
        }

        // 查询明细
        LambdaQueryWrapper<UserAbilityReqItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(UserAbilityReqItem::getReqId, req.getId());
        List<UserAbilityReqItem> items = itemService.list(itemWrapper);

        List<UserAbilityReqNewVO.UserAbilityReqItemVO> itemVOs = items.stream()
            .map(item -> {
                UserAbilityReqNewVO.UserAbilityReqItemVO itemVO = new UserAbilityReqNewVO.UserAbilityReqItemVO();
                itemVO.setId(item.getId());
                itemVO.setCategoryId(item.getCategoryId());
                itemVO.setElementId(item.getElementId());
                itemVO.setLevelId(item.getLevelId());
                itemVO.setScore(item.getScore());

                // 查询能力类别名称
                if (item.getCategoryId() != null) {
                    try {
                        String categoryName = jdbcTemplate.queryForObject(
                            "SELECT category_name FROM uf_ability_category WHERE id = ?",
                            String.class,
                            item.getCategoryId()
                        );
                        itemVO.setCategoryName(categoryName);
                    } catch (Exception e) {
                        log.warn("查询能力类别名称失败，类别ID: {}", item.getCategoryId());
                    }
                }

                // 查询能力要素名称
                try {
                    AbilityElement element = abilityElementService.getById(item.getElementId());
                    if (element != null) {
                        itemVO.setElementName(element.getElementName());
                    }
                } catch (Exception e) {
                    log.warn("查询能力要素名称失败，要素ID: {}", item.getElementId());
                }

                // 查询等级信息
                if (item.getLevelId() != null) {
                    try {
                        AbilityElementLevel level = abilityElementLevelService.getById(item.getLevelId());
                        if (level != null) {
                            itemVO.setLevelName(level.getLevelName());
                            itemVO.setLevelRequirement(level.getLevelRequirement());
                        }
                    } catch (Exception e) {
                        log.warn("查询等级信息失败，等级ID: {}", item.getLevelId());
                    }
                }

                return itemVO;
            })
            .collect(Collectors.toList());

        vo.setItems(itemVOs);

        return vo;
    }
}
