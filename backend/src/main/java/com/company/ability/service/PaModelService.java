package com.company.ability.service;

import com.company.ability.dto.PaBandSaveDTO;
import com.company.ability.dto.PaCategoryCreateDTO;
import com.company.ability.dto.PaCategoryUpdateDTO;
import com.company.ability.dto.PaItemCreateDTO;
import com.company.ability.dto.PaItemUpdateDTO;
import com.company.ability.vo.PaBandVO;
import com.company.ability.vo.PaModelVO;

import java.util.List;

/**
 * 岗位能力模型服务：一个岗位一整套能力类别 / 能力项 / 等级 / 分值区间。
 *
 * <p>整体读取（{@link #getModel}）与分散写入分开：读要一次拿全（页面三块互相联动），
 * 写按对象粒度分接口（类别、能力项、区间各自保存）。</p>
 */
public interface PaModelService {

    /** 读整个岗位模型（含权重合计等徽标数据） */
    PaModelVO getModel(Long postId);

    // ---------------- 能力类别 ----------------

    Long createCategory(Long postId, PaCategoryCreateDTO dto);

    void updateCategory(Long postId, PaCategoryUpdateDTO dto);

    void deleteCategory(Long postId, Long categoryId);

    // ---------------- 能力项（含等级） ----------------

    Long createItem(Long postId, PaItemCreateDTO dto);

    void updateItem(Long postId, PaItemUpdateDTO dto);

    void deleteItem(Long postId, Long itemId);

    // ---------------- 分值区间 ----------------

    List<PaBandVO> listBands(Long postId);

    /**
     * 整批替换岗位的分值区间。
     *
     * <p>区间在页面上是整块编辑的，逐条增删会短暂落库中间态（重叠/断档），
     * 而这类错只会在出分时暴露，所以统一整批校验 + 整批替换。
     * 传空列表表示清空（允许：模型可以先不配区间，报告上等级说明留空）。</p>
     */
    void saveBands(Long postId, List<PaBandSaveDTO> bands);
}
