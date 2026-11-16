-- 44 删除「司机专职能力」（element_id = 113）4 级、5 级空说明行
--
-- 依据：业务口径确认该要素实际只有 3 个等级（1–3 级说明完整且逐级递进），
--       4、5 级的 level_requirement 为 NULL，属导入时按「5 档通用模板」补出的空档位。
--       删除前已核查引用：
--         · uf_user_ability_req_item.level_id IN (555, 556) → 0 行
--         · uf_assess_sheet_row（element_id=113）required_level_no IN (4,5) → 0 行
--         · 该要素的人工要求与测评页面要求等级全部落在第 3 级
--       原始数据备份见 sql/43-backup-driver-level-4-5.sql。
--
-- 影响：仅 uf_ability_element_level 两行配置；不改表结构、不改代码、不动评级数据。
--       该要素在评价页仍按维度级配置（uf_assess_level_score）渲染 5 个选项，
--       4、5 级会变成「未配置说明」的档位 —— 这是本方案的已知局限（治标不治本）。

SET NOCOUNT ON;

PRINT '=== 删除前 ===';
SELECT id, element_id, level, level_name,
       CASE WHEN level_requirement IS NULL THEN '(NULL)' ELSE LEFT(level_requirement, 30) END AS req_head,
       score
FROM dbo.uf_ability_element_level
WHERE element_id = 113
ORDER BY level;

-- 引用再确认：有引用则整批取消，不删
IF EXISTS (SELECT 1 FROM dbo.uf_user_ability_req_item WHERE level_id IN (555, 556))
BEGIN
    THROW 50201, N'人员能力要求明细仍引用 4/5 级等级行，已取消删除', 1;
END;

IF EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_row WHERE element_id = 113 AND required_level_no IN (4, 5))
BEGIN
    THROW 50202, N'测评页面行仍引用 4/5 级要求等级，已取消删除', 1;
END;

DELETE FROM dbo.uf_ability_element_level
WHERE element_id = 113 AND level IN (4, 5) AND level_requirement IS NULL;

PRINT '=== 删除后 ===';
SELECT id, element_id, level, level_name,
       CASE WHEN level_requirement IS NULL THEN '(NULL)' ELSE LEFT(level_requirement, 30) END AS req_head,
       score
FROM dbo.uf_ability_element_level
WHERE element_id = 113
ORDER BY level;
