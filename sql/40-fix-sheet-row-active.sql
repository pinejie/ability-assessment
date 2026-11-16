-- ============================================================
-- 40 数据修正：uf_assess_sheet_row.active 与要素开关对齐
-- ------------------------------------------------------------
-- 背景（方案 §14 阻断级风险）：
--   页面行是否参与评价，有两份来源，必须一致：
--     ① uf_assess_sheet_row.active        —— 存储过程看这个（usp_assess_rate 第 4 步校验）
--     ② uf_ability_element.assess_in_flow —— 网页侧看这个（AssessSheetSyncService:161 派生）
--   两者不一致时会出现「网页认为该评 20 条、库内只有 19 条 active」，
--   @n 算成 20、客户/领导提交数 19 → @leader / @self 为 NULL → 分数永不出。
--
-- 触发本次修正的具体数据：
--   element_id = 57「企业文化知识」在 uf_ability_element 上 assess_in_flow = 0（不参与），
--   但其在 sheet 14-49 的页面行 active 仍为 1（历史同步遗留），共 36 行，且无任何评级。
--   后果：这 36 张页面在网页侧按 19 条要素收集评级，入库后 recalc 因 active 仍算 20 → 算不出分。
--
-- 处理：以 uf_ability_element 为准，批量把 active 拉回一致。
-- 影响范围：仅修正不一致行；一致行不受影响（SET 值与原值相同）。
--
-- 已核实（2026-11 于生产库执行后复查）：
--   修正前 mismatch = 36，修正后 mismatch = 0（共 578 行页面行）。
--
-- 回滚：本脚本不可逆。请先在修正前留档：
--   SELECT r.* INTO dbo.uf_assess_sheet_row_bak_20261108 FROM dbo.uf_assess_sheet_row r
--   JOIN dbo.uf_ability_element e ON e.id = r.element_id
--   WHERE r.active <> CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END;
-- ============================================================

USE ecology;
GO

SET XACT_ABORT ON;
GO

-- ① 修正前看看有多少行不一致（应为 0 才说明数据健康）
SELECT COUNT(*) AS mismatch_before
FROM dbo.uf_assess_sheet_row r
JOIN dbo.uf_ability_element e ON e.id = r.element_id
WHERE r.active <> CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END;
GO

-- ② 留档（只在备份表不存在时建，避免重复执行报错）
IF OBJECT_ID('dbo.uf_assess_sheet_row_bak_active', 'U') IS NULL
    SELECT r.*
    INTO dbo.uf_assess_sheet_row_bak_active
    FROM dbo.uf_assess_sheet_row r
    JOIN dbo.uf_ability_element e ON e.id = r.element_id
    WHERE r.active <> CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END;
GO

-- ③ 修正
UPDATE r
SET r.active = CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END
FROM dbo.uf_assess_sheet_row r
JOIN dbo.uf_ability_element e ON e.id = r.element_id
WHERE r.active <> CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END;

PRINT CONCAT('已修正行数：', @@ROWCOUNT);
GO

-- ④ 复查（必须为 0）
SELECT COUNT(*) AS mismatch_after
FROM dbo.uf_assess_sheet_row r
JOIN dbo.uf_ability_element e ON e.id = r.element_id
WHERE r.active <> CASE WHEN e.assess_in_flow <> 0 THEN 1 ELSE 0 END;
GO
