package com.company.ability.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户对一轮问题的回答：既可以按 slot 逐条给（前端渲染成表单时），
 * 也可以整段自然语言（用户直接打字时）。
 *
 * <p>两种形态都要支持，因为两种交互都合理：候选值明确的问题（「按 98% 还是摊平到 100%」）
 * 给成选项最省事；部门这种没法枚举的，用户更愿意直接说「质量部」。</p>
 */
@Data
public class PaImportAnswer {

    /** 按 slot 逐条回答：slot → 值。优先于 {@link #text} */
    private Map<String, String> values;

    /** 整段自然语言回答。{@code values} 为空时才用 */
    private String text;

    /**
     * 部门：用户选定的泛微部门 id。
     *
     * <p>单独一个字段而不是塞进 {@code values}：部门必须落到真实的 HrmDepartment.id，
     * 用户说的是「质量部」这种名字，重名部门在泛微里很常见（同一层级下不同公司都叫质量部），
     * 靠字符串匹配会挂错人。前端用部门树选，选完直接给 id。</p>
     */
    private Long departmentId;

    /**
     * 用户选了「全公司通用（不绑定部门）」。
     *
     * <p>必须有这个独立标志，不能拿 {@code departmentId == null} 当「通用」：
     * null 在本类里的既有含义是「这一轮没回答部门」，两者混在一起的话，
     * 用户什么都没答也会被当成确认导入通用岗位。</p>
     */
    private boolean genericDepartment;

    /** 用户勾选的 sheet（SELECTING 阶段用）。空且 {@code confirmNone} 为 false 时表示「还没选」 */
    private List<String> selectedSheets = new ArrayList<>();

    /** 用户明确点「这些表都不导」 */
    private boolean confirmNone;
}
