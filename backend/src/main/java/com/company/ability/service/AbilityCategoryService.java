package com.company.ability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.company.ability.dto.AbilityCategoryCreateDTO;
import com.company.ability.dto.AbilityCategoryUpdateDTO;
import com.company.ability.dto.PageRequest;
import com.company.ability.dto.PageResult;
import com.company.ability.entity.AbilityCategory;
import com.company.ability.vo.AbilityCategoryVO;

import java.util.List;

/**
 * 能力类别服务接口
 */
public interface AbilityCategoryService extends IService<AbilityCategory> {

    /**
     * 创建能力类别
     */
    Long createCategory(AbilityCategoryCreateDTO dto);

    /**
     * 更新能力类别
     */
    void updateCategory(AbilityCategoryUpdateDTO dto);

    /**
     * 删除能力类别
     */
    void deleteCategory(Long id);

    /**
     * 根据ID查询能力类别
     */
    AbilityCategoryVO getCategoryById(Long id);

    /**
     * 分页查询能力类别
     */
    PageResult<AbilityCategoryVO> pageCategories(PageRequest pageRequest);

    /**
     * 查询能力类别列表（用于选择框，可过滤状态）
     */
    List<AbilityCategoryVO> listCategories(Integer status);
}
