package com.company.ability.service;

import com.company.ability.dto.AbilityStructureDTO;
import com.company.ability.vo.AbilityStructureVO;

/**
 * 能力结构导入（方案 14）：类别/要素/等级/岗位要求/人员要求，kind = ABILITY_STRUCTURE。
 * preview 只读（不写任何表）；commit 单事务写结构数据，测评汇总在事务后逐人同步。
 * 调用方负责切维度上下文（DimensionContext）。
 */
public interface AbilityStructureImportService {

    /**
     * 确定性校验 + 写入计划，不落库。
     *
     * @param dto       交付稿
     * @param sessionId 会话（批次维度隔离校验用，可空）
     */
    AbilityStructureVO.PreviewResult preview(AbilityStructureDTO dto, String sessionId);

    /**
     * 按 preview 同一套规则重校验后写入。
     *
     * @param batchId 批次 ID（uf_assess_import_run.id），用于写入台账与撤销；可空
     */
    AbilityStructureVO.CommitResult commit(AbilityStructureDTO dto, String sessionId, Long batchId);

    /** 构件化维度隔离：同一契约下只处理一个维度，跨维度一律拒绝 */
    void requireSingleDimension(AbilityStructureDTO dto);
}
