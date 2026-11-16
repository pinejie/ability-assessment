-- 43 备份：「司机专职能力」（element_id = 113）4 级、5 级空说明行
--
-- 背景：该要素在 uf_ability_element_level 中有 5 行（1–5 级），但 4 级、5 级的
--       level_requirement 为 NULL。业务口径确认：这个要素实际只有 3 个等级，
--       4、5 级是导入时按「5 档通用模板」补齐产生的空档位，不是漏填。
--       人员能力要求（uf_user_ability_req_item）与测评页面（uf_assess_sheet_row）
--       对该要素的引用全部落在第 3 级，4、5 级无任何引用，可安全删除。
--
-- 本文件只做两件事：
--   1) 删除前把原始两行原样记录在此（含 id / 时间戳），供回滚
--   2) 提供注释掉的回滚 INSERT，需要时人工取消注释执行
--
-- 删除语句见 sql/44-delete-driver-level-4-5.sql。
-- 执行日期：2026-11-13

-- ============ 一、删除前原始数据（SELECT 结果，留档） ============
-- id   element_id  level  level_name  level_requirement  score  dimension_id  create_time              update_time
-- 555  113         4      4级          NULL               7      3             2026-11-10 09:22:37.380  2026-11-10 09:22:37.380
-- 556  113         5      5级          NULL               10     3             2026-11-10 09:22:37.380  2026-11-10 09:22:37.380

-- ============ 二、回滚脚本（默认注释，需要时取消注释执行） ============
-- 注意：回滚会重新引入两个空说明档位，页面上会出现两个空白选项，
--       除非同时补上 4/5 级的行为描述，否则不建议回滚。
/*
SET IDENTITY_INSERT dbo.uf_ability_element_level ON;

INSERT INTO dbo.uf_ability_element_level
    (id, element_id, level, level_name, level_requirement, score, dimension_id, create_time, update_time)
VALUES
    (555, 113, 4, N'4级', NULL, 7, 3, '2026-11-10 09:22:37.380', '2026-11-10 09:22:37.380'),
    (556, 113, 5, N'5级', NULL, 10, 3, '2026-11-10 09:22:37.380', '2026-11-10 09:22:37.380');

SET IDENTITY_INSERT dbo.uf_ability_element_level OFF;
*/
