package com.company.ability.service.impl;

import com.company.ability.exception.BusinessException;
import com.company.ability.service.ImportAliasUndoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 别名沉淀与批次撤销（方案 14 §三 / §七）。
 *
 * 撤销纪律：台账里只记 CREATE 的实体才自动删；出现 UPDATE（覆盖写）说明现有配置被改过，
 * 自动删会把用户原来的配置一起带走——因此默认拒绝并点名，必须显式 force 才继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportAliasUndoServiceImpl implements ImportAliasUndoService {

    /** 允许沉淀的别名类型：类别/要素靠名称精确匹配，别名只对部门与岗位有意义 */
    private static final Set<String> ALIAS_KINDS = Set.of("DEPARTMENT", "JOB_TITLE");

    private final JdbcTemplate jdbc;

    @Value("${assess.company-id:1}")
    private long companyId;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmAliases(List<AliasItem> items, long dimensionId) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        if (items.size() > 200) {
            throw new BusinessException(400, "一次最多确认 200 条别名");
        }
        int saved = 0;
        for (AliasItem item : items) {
            String kind = item.kind() == null ? "" : item.kind().trim().toUpperCase();
            if (!ALIAS_KINDS.contains(kind)) {
                throw new BusinessException(400, "不支持的别名类型：" + item.kind());
            }
            String source = AbilityStructureImportServiceImpl.normalizeName(item.sourceName());
            if (source.isEmpty()) {
                throw new BusinessException(400, "别名的文件写法不能为空");
            }
            String targetName = item.targetName() == null || item.targetName().isBlank()
                    ? String.valueOf(item.targetId()) : item.targetName().trim();
            // 目标必须真实存在（防前端传错 ID 把别名指向鬼影）
            String table = "DEPARTMENT".equals(kind) ? "HrmDepartment" : "HrmJobTitles";
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?",
                    Integer.class, item.targetId());
            if (exists == null || exists == 0) {
                throw new BusinessException(400, "目标实体不存在：" + targetName);
            }
            int updated = jdbc.update("UPDATE uf_assess_import_alias SET target_id = ?, target_name = ?," +
                            " confirmed_at = SYSDATETIME() WHERE company_id = ? AND dimension_id = ?" +
                            " AND kind = ? AND source_name = ?",
                    item.targetId(), targetName, companyId, dimensionId, kind, source);
            if (updated == 0) {
                jdbc.update("INSERT INTO uf_assess_import_alias (company_id, dimension_id, kind, source_name," +
                                " target_id, target_name, hit_count, confirmed_by) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                        companyId, dimensionId, kind, source, item.targetId(), targetName, 0L);
            }
            saved++;
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UndoResult undo(long batchId, boolean force, long dimensionId) {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();

        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT entity_type, entity_id, natural_key, action FROM uf_assess_import_batch_item" +
                " WHERE batch_id = ? ORDER BY id", batchId);
        if (items.isEmpty()) {
            throw new BusinessException(404, "该批次没有写入记录（可能尚未写入，或台账未记录）");
        }
        // 批次必须属于当前维度：台账没有 dimension_id 列，只能经 run 记录反查，防跨维度误删
        List<Long> dims = jdbc.queryForList(
                "SELECT dimension_id FROM uf_assess_import_run WHERE id = ? AND company_id = ?",
                Long.class, batchId, companyId);
        if (dims.isEmpty() || dims.get(0) == null || dims.get(0) != dimensionId) {
            throw new BusinessException(403, "该批次不属于当前维度，无法撤销");
        }
        String status = jdbc.queryForObject(
                "SELECT status FROM uf_assess_import_run WHERE id = ?", String.class, batchId);
        if ("DRAFT".equals(status) || "PARSING".equals(status)) {
            throw new BusinessException(409, "批次尚未写入，无需撤销");
        }

        // 先扫一遍：存在 UPDATE 且未 force → 整体拒绝（不做部分删除，避免删一半留一半）
        if (!force) {
            List<String> conflicts = new ArrayList<>();
            for (Map<String, Object> it : items) {
                if ("UPDATE".equals(String.valueOf(it.get("action")))) {
                    conflicts.add(String.valueOf(it.get("entity_type")) + "："
                            + String.valueOf(it.get("natural_key")));
                }
            }
            if (!conflicts.isEmpty()) {
                throw new BusinessException(409, "本批包含覆盖写（" + String.join("、", conflicts)
                        + "），撤销会连带删除被覆盖的原有配置，已拒绝。确认要撤销请显式勾选强制撤销。");
            }
        }

        // 删除顺序按依赖倒序：台账是按写入顺序记的（类别→要素→等级→岗位→人员），
        // 照台账顺序删会先撞 uf_ability_element.category_id 外键。这里按引用关系排序：
        // 引用别人的先删（人员/岗位要求引用要素与等级），被引用的后删（要素→类别）
        List<Map<String, Object>> ordered = new ArrayList<>(items);
        ordered.sort(java.util.Comparator.comparingInt(it -> deleteOrder(String.valueOf(it.get("entity_type")))));
        for (Map<String, Object> it : ordered) {
            String type = String.valueOf(it.get("entity_type"));
            long id = ((Number) it.get("entity_id")).longValue();
            int n = deleteByType(type, id);
            if (n > 0) {
                deleted.merge(type, n, Integer::sum);
            }
        }
        // 台账本身保留（审计用），只标记该批已撤销
        jdbc.update("UPDATE uf_assess_import_run SET fail_reason = ? WHERE id = ?",
                "已撤销（" + deleted.values().stream().mapToInt(Integer::intValue).sum() + " 个实体）", batchId);

        int removed = deleted.values().stream().mapToInt(Integer::intValue).sum();
        log.info("批次 {} 撤销完成，删除 {} 个实体：{}", batchId, removed, deleted);
        return new UndoResult(removed, refused, deleted);
    }

    /** 删除顺序权重：数值小的先删。引用别人的在前，被引用的在后（FK：element→category） */
    private int deleteOrder(String type) {
        return switch (type) {
            case "USER_REQ" -> 0;
            case "POSITION_REQ" -> 1;
            case "LEVEL" -> 2;
            case "ELEMENT" -> 3;
            case "CATEGORY" -> 4;
            default -> 5;
        };
    }

    /** 按台账实体类型删除；返回实际删除行数（已不存在按 0 计，不报错——重试幂等） */
    private int deleteByType(String type, long id) {
        return switch (type) {
            case "CATEGORY" -> jdbc.update("DELETE FROM uf_ability_category WHERE id = ?", id);
            case "ELEMENT" -> jdbc.update("DELETE FROM uf_ability_element WHERE id = ?", id);
            case "LEVEL" -> jdbc.update("DELETE FROM uf_ability_element_level WHERE id = ?", id);
            case "POSITION_REQ" -> {
                jdbc.update("DELETE FROM uf_position_ability_req_item WHERE req_id = ?", id);
                yield jdbc.update("DELETE FROM uf_position_ability_req_new WHERE id = ?", id);
            }
            case "USER_REQ" -> {
                jdbc.update("DELETE FROM uf_user_ability_req_item WHERE req_id = ?", id);
                yield jdbc.update("DELETE FROM uf_user_ability_req_new WHERE id = ?", id);
            }
            default -> 0;
        };
    }
}
