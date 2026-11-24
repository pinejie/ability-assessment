-- ============================================================
-- 54 清空能力测评的历史业务数据（2026-11-21）
-- ------------------------------------------------------------
-- 背景：
--   方案 17 的岗位能力模型刚落地，库里跑着的还是试跑期造的数据
--   （P1 冒烟 + 端到端验证留下的 12 个岗位、1 个年度任务、2 份报告）。
--   业务方要换成真实的两套岗位模型（技术人员 / 信息化管理人员），
--   要求「先清空、再导入」，避免新旧岗位混在一起看不出哪些是废数据。
--
-- 清什么 / 不清什么：
--   清：岗位模型（post / category / item / item_level / band）、
--       测评数据（sheet / sheet_item / rating / task / task_rater）、
--       测评相关的站内消息（uf_push_msg 的 msg_type 36 / 596）。
--   不清：uf_assess_period（年度）、uf_assess_dimension / _auth（维度与授权）、
--         组织架构表（HrmResource 等泛微原生表）、uf_assess_admin。
--         这些是配置，不是历史业务数据。
--
-- 备份策略：与 sql/51 / sql/53 同款 —— DELETE 前先 SELECT * INTO <表>_bak_20261121b。
--   备份表留在库里（不参与任何业务），确认无异议后可另行 DROP。
--   后缀用 b，避免和 sql/51 的 _bak_20261121 撞名。
--
-- 脚本幂等：备份只在备份表不存在时做一次；DELETE 再跑一次就是 0 行。
--
-- 执行：DB_SECRET_FILE=config/secret.yml node sql/run-ddl.js sql/54-wipe-pa-data.sql
-- 回滚：按下列顺序把备份表灌回（先父后子）：
--   uf_pa_post ← uf_pa_post_bak_20261121b … uf_pa_rating ← uf_pa_rating_bak_20261121b
-- ============================================================

USE ecology;
GO

PRINT '--- 清空前行数 ---';
GO
SELECT 'uf_pa_post' AS tbl, COUNT(*) AS rows_cnt FROM dbo.uf_pa_post
UNION ALL SELECT 'uf_pa_category', COUNT(*) FROM dbo.uf_pa_category
UNION ALL SELECT 'uf_pa_item', COUNT(*) FROM dbo.uf_pa_item
UNION ALL SELECT 'uf_pa_item_level', COUNT(*) FROM dbo.uf_pa_item_level
UNION ALL SELECT 'uf_pa_band', COUNT(*) FROM dbo.uf_pa_band
UNION ALL SELECT 'uf_pa_sheet', COUNT(*) FROM dbo.uf_pa_sheet
UNION ALL SELECT 'uf_pa_sheet_item', COUNT(*) FROM dbo.uf_pa_sheet_item
UNION ALL SELECT 'uf_pa_rating', COUNT(*) FROM dbo.uf_pa_rating
UNION ALL SELECT 'uf_assess_task', COUNT(*) FROM dbo.uf_assess_task
UNION ALL SELECT 'uf_assess_task_rater', COUNT(*) FROM dbo.uf_assess_task_rater
UNION ALL SELECT 'uf_push_msg(36,596)', COUNT(*) FROM dbo.uf_push_msg WHERE msg_type IN (36, 596);
GO

PRINT '--- 1. 备份（每张表只备一次） ---';
GO
IF OBJECT_ID('dbo.uf_pa_post_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_post_bak_20261121b FROM dbo.uf_pa_post;
IF OBJECT_ID('dbo.uf_pa_category_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_category_bak_20261121b FROM dbo.uf_pa_category;
IF OBJECT_ID('dbo.uf_pa_item_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_item_bak_20261121b FROM dbo.uf_pa_item;
IF OBJECT_ID('dbo.uf_pa_item_level_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_item_level_bak_20261121b FROM dbo.uf_pa_item_level;
IF OBJECT_ID('dbo.uf_pa_band_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_band_bak_20261121b FROM dbo.uf_pa_band;
IF OBJECT_ID('dbo.uf_pa_sheet_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_sheet_bak_20261121b FROM dbo.uf_pa_sheet;
IF OBJECT_ID('dbo.uf_pa_sheet_item_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_sheet_item_bak_20261121b FROM dbo.uf_pa_sheet_item;
IF OBJECT_ID('dbo.uf_pa_rating_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_pa_rating_bak_20261121b FROM dbo.uf_pa_rating;
IF OBJECT_ID('dbo.uf_assess_task_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_assess_task_bak_20261121b FROM dbo.uf_assess_task;
IF OBJECT_ID('dbo.uf_assess_task_rater_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_assess_task_rater_bak_20261121b FROM dbo.uf_assess_task_rater;
IF OBJECT_ID('dbo.uf_push_msg_bak_20261121b','U') IS NULL
    SELECT * INTO dbo.uf_push_msg_bak_20261121b FROM dbo.uf_push_msg WHERE msg_type IN (36, 596);
GO

PRINT '--- 2. 删除（子表 → 父表，避免外键/脏引用） ---';
GO
-- 2.1 评价明细 → 测评页 → 任务
DELETE FROM dbo.uf_pa_rating;
DELETE FROM dbo.uf_pa_sheet_item;
DELETE FROM dbo.uf_assess_task_rater;
DELETE FROM dbo.uf_assess_task;
DELETE FROM dbo.uf_pa_sheet;
GO

-- 2.2 岗位模型：区间 → 等级 → 能力项 → 类别 → 岗位
DELETE FROM dbo.uf_pa_band;
DELETE FROM dbo.uf_pa_item_level;
DELETE FROM dbo.uf_pa_item;
DELETE FROM dbo.uf_pa_category;
DELETE FROM dbo.uf_pa_post;
GO

-- 2.3 测评站内消息（36 = 测评通知，596 = 测评待办），其他类型的消息不动
DELETE FROM dbo.uf_push_msg WHERE msg_type IN (36, 596);
GO

PRINT '--- 3. 清空后自检：业务表应全为 0，配置表保持不变 ---';
GO
SELECT 'uf_pa_post' AS tbl, COUNT(*) AS rows_cnt FROM dbo.uf_pa_post
UNION ALL SELECT 'uf_pa_category', COUNT(*) FROM dbo.uf_pa_category
UNION ALL SELECT 'uf_pa_item', COUNT(*) FROM dbo.uf_pa_item
UNION ALL SELECT 'uf_pa_item_level', COUNT(*) FROM dbo.uf_pa_item_level
UNION ALL SELECT 'uf_pa_band', COUNT(*) FROM dbo.uf_pa_band
UNION ALL SELECT 'uf_pa_sheet', COUNT(*) FROM dbo.uf_pa_sheet
UNION ALL SELECT 'uf_pa_sheet_item', COUNT(*) FROM dbo.uf_pa_sheet_item
UNION ALL SELECT 'uf_pa_rating', COUNT(*) FROM dbo.uf_pa_rating
UNION ALL SELECT 'uf_assess_task', COUNT(*) FROM dbo.uf_assess_task
UNION ALL SELECT 'uf_assess_task_rater', COUNT(*) FROM dbo.uf_assess_task_rater
UNION ALL SELECT 'uf_push_msg(36,596)', COUNT(*) FROM dbo.uf_push_msg WHERE msg_type IN (36, 596)
UNION ALL SELECT '-- uf_assess_period（配置，应保留 2）', COUNT(*) FROM dbo.uf_assess_period
UNION ALL SELECT '-- uf_assess_dimension（配置，应保留 3）', COUNT(*) FROM dbo.uf_assess_dimension;
GO

PRINT '--- 4. 备份表行数（应与清空前一致） ---';
GO
SELECT 'uf_pa_post_bak' AS tbl, COUNT(*) AS rows_cnt FROM dbo.uf_pa_post_bak_20261121b
UNION ALL SELECT 'uf_pa_category_bak', COUNT(*) FROM dbo.uf_pa_category_bak_20261121b
UNION ALL SELECT 'uf_pa_item_bak', COUNT(*) FROM dbo.uf_pa_item_bak_20261121b
UNION ALL SELECT 'uf_pa_item_level_bak', COUNT(*) FROM dbo.uf_pa_item_level_bak_20261121b
UNION ALL SELECT 'uf_pa_band_bak', COUNT(*) FROM dbo.uf_pa_band_bak_20261121b
UNION ALL SELECT 'uf_pa_sheet_bak', COUNT(*) FROM dbo.uf_pa_sheet_bak_20261121b
UNION ALL SELECT 'uf_pa_sheet_item_bak', COUNT(*) FROM dbo.uf_pa_sheet_item_bak_20261121b
UNION ALL SELECT 'uf_pa_rating_bak', COUNT(*) FROM dbo.uf_pa_rating_bak_20261121b
UNION ALL SELECT 'uf_assess_task_bak', COUNT(*) FROM dbo.uf_assess_task_bak_20261121b
UNION ALL SELECT 'uf_assess_task_rater_bak', COUNT(*) FROM dbo.uf_assess_task_rater_bak_20261121b
UNION ALL SELECT 'uf_push_msg_bak', COUNT(*) FROM dbo.uf_push_msg_bak_20261121b;
GO
