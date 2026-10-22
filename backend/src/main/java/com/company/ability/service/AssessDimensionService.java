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
     * 查询指定人员可用的维度（启用 + 授权匹配；2026-10-22 起：无授权行的维度仅管理员可见）。
     * admin=true（系统管理员）返回全部启用维度；
     * resourceId 为空（无登录身份，宽松兼容内部调用/调试）返回全部启用维度。
     */
    List<AssessDimensionVO> authorized(Long resourceId, boolean admin);

    /**
     * 判断人员对指定维度是否有访问权（请求级越权校验用）。
     * 与 authorized() 同一套规则：无授权行 = 仅管理员（管理员由调用方先行豁免）；
     * 配置授权后按「人员 ID 或一级分部」精确命中。
     */
    boolean isAuthorizedForUser(Long dimensionId, Long resourceId);
}
