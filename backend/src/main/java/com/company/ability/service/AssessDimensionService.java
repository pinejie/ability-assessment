package com.company.ability.service;

import com.company.ability.dto.AssessDimensionCreateDTO;
import com.company.ability.dto.AssessDimensionUpdateDTO;
import com.company.ability.dto.DimensionAuthItemDTO;
import com.company.ability.vo.AssessDimensionAuthVO;
import com.company.ability.vo.AssessDimensionVO;
import com.company.ability.vo.DimensionScopeVO;

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
     * 配置授权后按「人员 ID 命中，或 人员分部沿上级链命中授权分部（子树语义）」。
     */
    boolean isAuthorizedForUser(Long dimensionId, Long resourceId);

    /**
     * 判断人员在指定维度里是否算「维度管理员」。
     *
     * <p>2026-11-23 合并后（sql/58）：管理员 <b>就是「人员授权」本身</b>，
     * 落在 {@code uf_assess_dimension_auth}（auth_type=2），不再单独存一张表。
     * 页面上的「人员授权」一栏即可用范围，同时授予这条可见性。</p>
     *
     * <p>只用于可见性判断（报告里 360 测评人姓名露不露），<b>不授予任何管理权限</b> ——
     * 维度增删改、授权配置仍然只认 {@code uf_assess_admin}。名字叫管理员但权限只有这一条，
     * 是有意的：先解决「谁该看到姓名」，权限下放另说。</p>
     *
     * <p>{@code resourceId} 为空（泛微钩子等无登录身份的调用）返回 false ——
     * 与 {@link #isAuthorizedForUser} 的宽松口径相反。那条宽松口径是为了不打断既有集成，
     * 而这里的结果是把真人姓名多露一份，宁可保守。</p>
     */
    boolean isDimensionAdmin(Long dimensionId, Long resourceId);

    /**
     * 查询维度的组织范围：授权分部（展开子树、扣掉排除分部）及其下的部门 ID。
     *
     * <p>用途是把「选部门」「选被测人」的候选收窄到本维度授权的分部 ——
     * 岗位挂到外分部的部门上、被测人来自外分部，都会让等级没有业务含义。
     * 判定口径与 {@link #isAuthorizedForUser} 的分部部分一致，但这里算的是
     * 「哪些部门在范围内」，不是「某个人能不能进」。</p>
     *
     * <p>{@code restricted=false}（该维度没有任何授权分部）表示不限，调用方应保持全量；
     * 这与「有授权分部但下面一个部门都没有」是不同的结果，不能混用空数组表达。</p>
     */
    DimensionScopeVO currentScope(Long dimensionId);
}
