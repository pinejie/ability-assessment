package com.company.ability.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前维度的组织范围（部门候选收窄用）。
 *
 * <p>「所属分部」不只决定谁能进这个维度，也决定这个维度里的人能选到哪些部门 ——
 * 岗位只能挂在本维度授权分部下的部门上，被测人也只能从这些部门里挑。
 * 前端拿这个结果去裁剪部门树与人员候选。</p>
 *
 * <p>{@code restricted=false} 表示该维度没有配任何「授权分部」，不做范围收窄
 * （此时通常只有系统管理员能进，见 {@code isAuthorizedFor} 的「无授权行」分支）。
 * 前端必须按这个开关判断，<b>不能</b>拿 {@code departmentIds} 空数组当作「没有部门」——
 * 那是两件完全不同的事：一个是不限，一个是一个都没有。</p>
 */
@Data
public class DimensionScopeVO {

    /** 是否受分部授权限制（false = 不限，departmentIds 无意义） */
    private boolean restricted;

    /** 授权分部（已展开子树、已扣掉排除分部） */
    private List<Long> subcompanyIds;

    /** 授权分部下的部门 ID；restricted=true 时用它裁剪部门树与人选候选 */
    private List<Long> departmentIds;
}
