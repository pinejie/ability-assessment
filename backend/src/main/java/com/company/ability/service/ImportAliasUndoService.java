package com.company.ability.service;

import java.util.List;
import java.util.Map;

/**
 * 导入别名与批次撤销（方案 14 §三 / §七）。
 *
 * 别名：文件里的部门/岗位写法与系统不一致时的唯一合法匹配依据（"名称不硬配"规则的例外）。
 * 撤销：按批次台账回滚，只删本批 CREATE，遇到过 UPDATE 一律拒绝并要求显式 force。
 */
public interface ImportAliasUndoService {

    /**
     * 沉淀别名（审核页「确认匹配」按钮）。源名归一化后存，重复确认覆盖目标并累加计数。
     *
     * @return 实际写入条数
     */
    int confirmAliases(List<AliasItem> items, long dimensionId);

    /**
     * 撤销一个批次。默认只回滚 CREATE；台账里存在 UPDATE 时拒绝并点名，force=true 才连带删除覆盖写的实体。
     *
     * @return removed=实际删除实体数，refused=拒绝原因清单，deleted=按实体类型的删除计数
     */
    UndoResult undo(long batchId, boolean force, long dimensionId);

    /** 别名确认项 */
    record AliasItem(String kind, String sourceName, long targetId, String targetName) {
    }

    /** 撤销结果 */
    record UndoResult(int removed, List<String> refused, Map<String, Integer> deleted) {
    }
}
