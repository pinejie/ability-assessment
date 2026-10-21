package com.company.ability.service;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.AssessDimensionUpdateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.vo.AssessDimensionAuthVO;
import com.company.ability.vo.AssessDimensionVO;

import java.util.List;

/**
 * 测评维度服务（类租户）
 */
public interface AssessDimensionService {

    /**
     * 全部维度列表（管理页用，带授权条目数）
     */
    List<AssessDimensionVO> listAll();

    /**
     * 创建维度
     */
    Long create(AssessDimensionCreateDTO dto);

    /**
     * 更新维度
     */
    void update(AssessDimensionUpdateDTO dto);

    /**
     * 删除维度（默认维度、已有业务数据的维度禁止删除）
     */
    void delete(Long id);

    /**
     * 查询维度授权列表（带分部/人员名称）
     */
    List<AssessDimensionAuthVO> listAuths(Long dimensionId);

    /**
     * 整体覆盖保存维度授权
     */
    void saveAuths(Long dimensionId, List<DimensionAuthItemDTO> items);

    /**
     * 查询指定人员可用的维度（启用 + 授权匹配；无授权行的维度视为所有人可用）。
     * resourceId 为空时返回全部启用维度（当前系统无统一登录，未传用户时不做过滤）。
     */
    List<AssessDimensionVO> authorized(Long resourceId);

    /**
     * 判断人员对指定维度是否有访问权（请求级越权校验用）。
     * 与 authorized() 同一套规则：无授权行 = 所有人可用。
     */
    boolean isAuthorizedForUser(Long dimensionId, Long resourceId);
}
