-- ============================================================
-- 51 存量清理：旧测评模型下线（方案 17 · P3）
-- ------------------------------------------------------------
-- 依据：《17-岗位能力模型重构方案》§六（2026-11-21 口径已确认）。
--
-- 为什么可以直接删：
--   P2 已把评价侧整条链路切到 uf_pa_*（岗位能力模型 / 人员岗位映射 / 测评页 /
--   评级 / Java 打分），旧模型的三套表已经没有任何读写方：
--     · 全局能力库      uf_ability_category / _element / _element_level
--     · 岗位与人员要求  uf_position_ability_req_* / uf_user_ability_req_* / uf_org_ability_req
--     · 旧测评页与评级  uf_assess_sheet / _sheet_row / _sheet_rating / _sheet_log
--     · 旧配置与权重    uf_assess_weight_ability / _weight_assessor / _grade_level / _level_score
--     · 外部导入与智能导入 uf_assess_external_import_log / _agent_draft / _import_*
--     · 归档与评语      uf_assess_report_snapshot / uf_assess_comment
--   代码侧对应删除见方案 §6.4（后端 40+ 类、前端 13 个页面/组件）。
--
-- 保留不动（本脚本一个字都不碰）：
--   uf_assess_dimension / uf_assess_dimension_auth   维度（类租户）
--   uf_assess_admin                                  系统管理员
--   uf_assess_period                                 年度
--   uf_push_msg                                      通用消息队列（PushMsgJob 轮询）
--   uf_assess_migration_log                          历史迁移台账
--   uf_pa_*                                          新模型 9 张表
--   uf_assess_task / uf_assess_task_rater            **只清数据、保留结构**
--       —— 新链路仍读写这两张表（任务、评价人、token、催办轮次），
--          清空是因为存量行里有 rater_type='CLIENT' 的旧枚举值和旧 sheet_id 指向，
--          留着会让新页面读到一批打不开的链接。
--
-- 备份策略：DROP 之前先用 SELECT * INTO <表>_bak_20261121 把有数据的表复制一份；
--   备份表保留在库里（不参与任何业务），确认无异议后可另行 DROP。
--   脚本幂等：备份只在备份表不存在时做一次，DROP 用 IF EXISTS。
--
-- 执行：node sql/run-ddl.js sql/51-pa-cleanup.sql
--       （需要 DB_SECRET_FILE=config/secret.yml 或 DB_USER/DB_PASSWORD）
-- 回滚：本脚本不提供自动回滚。备份表在，需要时 INSERT ... SELECT 回填重建的旧表。
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 1. 备份（复制后 drop 原表）
-- ------------------------------------------------------------
-- 顺序无关：备份表是纯数据副本，不带外键约束。
-- 只备份「当前存在且非空」的表 —— 全空的表备份出来只是一张空壳，没有回滚价值。
PRINT '--- 1. 备份旧表 ---';
GO

IF OBJECT_ID('dbo.uf_ability_category','U') IS NOT NULL AND OBJECT_ID('dbo.uf_ability_category_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_ability_category_bak_20261121 FROM dbo.uf_ability_category;
GO
IF OBJECT_ID('dbo.uf_ability_element','U') IS NOT NULL AND OBJECT_ID('dbo.uf_ability_element_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_ability_element_bak_20261121 FROM dbo.uf_ability_element;
GO
IF OBJECT_ID('dbo.uf_ability_element_level','U') IS NOT NULL AND OBJECT_ID('dbo.uf_ability_element_level_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_ability_element_level_bak_20261121 FROM dbo.uf_ability_element_level;
GO
IF OBJECT_ID('dbo.uf_org_ability_req','U') IS NOT NULL AND OBJECT_ID('dbo.uf_org_ability_req_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_org_ability_req_bak_20261121 FROM dbo.uf_org_ability_req;
GO
IF OBJECT_ID('dbo.uf_position_ability_req_new','U') IS NOT NULL AND OBJECT_ID('dbo.uf_position_ability_req_new_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_position_ability_req_new_bak_20261121 FROM dbo.uf_position_ability_req_new;
GO
IF OBJECT_ID('dbo.uf_position_ability_req_item','U') IS NOT NULL AND OBJECT_ID('dbo.uf_position_ability_req_item_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_position_ability_req_item_bak_20261121 FROM dbo.uf_position_ability_req_item;
GO
IF OBJECT_ID('dbo.uf_user_ability_req_new','U') IS NOT NULL AND OBJECT_ID('dbo.uf_user_ability_req_new_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_user_ability_req_new_bak_20261121 FROM dbo.uf_user_ability_req_new;
GO
IF OBJECT_ID('dbo.uf_user_ability_req_item','U') IS NOT NULL AND OBJECT_ID('dbo.uf_user_ability_req_item_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_user_ability_req_item_bak_20261121 FROM dbo.uf_user_ability_req_item;
GO
IF OBJECT_ID('dbo.uf_assess_weight_ability','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_weight_ability_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_weight_ability_bak_20261121 FROM dbo.uf_assess_weight_ability;
GO
IF OBJECT_ID('dbo.uf_assess_weight_assessor','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_weight_assessor_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_weight_assessor_bak_20261121 FROM dbo.uf_assess_weight_assessor;
GO
IF OBJECT_ID('dbo.uf_assess_grade_level','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_grade_level_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_grade_level_bak_20261121 FROM dbo.uf_assess_grade_level;
GO
IF OBJECT_ID('dbo.uf_assess_level_score','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_level_score_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_level_score_bak_20261121 FROM dbo.uf_assess_level_score;
GO
IF OBJECT_ID('dbo.uf_assess_sheet','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_sheet_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_sheet_bak_20261121 FROM dbo.uf_assess_sheet;
GO
IF OBJECT_ID('dbo.uf_assess_sheet_row','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_sheet_row_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_sheet_row_bak_20261121 FROM dbo.uf_assess_sheet_row;
GO
IF OBJECT_ID('dbo.uf_assess_sheet_rating','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_sheet_rating_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_sheet_rating_bak_20261121 FROM dbo.uf_assess_sheet_rating;
GO
IF OBJECT_ID('dbo.uf_assess_sheet_log','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_sheet_log_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_sheet_log_bak_20261121 FROM dbo.uf_assess_sheet_log;
GO
IF OBJECT_ID('dbo.uf_assess_external_import_log','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_external_import_log_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_external_import_log_bak_20261121 FROM dbo.uf_assess_external_import_log;
GO
IF OBJECT_ID('dbo.uf_assess_agent_draft','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_agent_draft_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_agent_draft_bak_20261121 FROM dbo.uf_assess_agent_draft;
GO
IF OBJECT_ID('dbo.uf_assess_import_run','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_import_run_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_import_run_bak_20261121 FROM dbo.uf_assess_import_run;
GO
IF OBJECT_ID('dbo.uf_assess_import_batch_item','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_import_batch_item_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_import_batch_item_bak_20261121 FROM dbo.uf_assess_import_batch_item;
GO
IF OBJECT_ID('dbo.uf_assess_import_alias','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_import_alias_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_import_alias_bak_20261121 FROM dbo.uf_assess_import_alias;
GO
IF OBJECT_ID('dbo.uf_assess_comment','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_comment_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_comment_bak_20261121 FROM dbo.uf_assess_comment;
GO
IF OBJECT_ID('dbo.uf_assess_report_snapshot','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_report_snapshot_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_report_snapshot_bak_20261121 FROM dbo.uf_assess_report_snapshot;
GO
-- 保留结构的两张表也要备份行（清空后要回填只能靠它）
IF OBJECT_ID('dbo.uf_assess_task','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_task_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_task_bak_20261121 FROM dbo.uf_assess_task;
GO
IF OBJECT_ID('dbo.uf_assess_task_rater','U') IS NOT NULL AND OBJECT_ID('dbo.uf_assess_task_rater_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_assess_task_rater_bak_20261121 FROM dbo.uf_assess_task_rater;
GO

-- ------------------------------------------------------------
-- 2. DROP 旧表
-- ------------------------------------------------------------
-- 顺序有讲究：先删引用别人的子表，再删被引用的父表。现存外键：
--   uf_assess_sheet_row/rating/log -> uf_assess_sheet
--   uf_ability_element -> uf_ability_category
--   uf_ability_element_level -> uf_ability_element
PRINT '--- 2. DROP 旧表 ---';
GO

-- 2.1 旧测评页的子表（外键指向 uf_assess_sheet）
DROP TABLE IF EXISTS dbo.uf_assess_sheet_row;
GO
DROP TABLE IF EXISTS dbo.uf_assess_sheet_rating;
GO
DROP TABLE IF EXISTS dbo.uf_assess_sheet_log;
GO
DROP TABLE IF EXISTS dbo.uf_assess_comment;
GO

-- 2.2 旧测评页主表（上面三张的原外键目标）
DROP TABLE IF EXISTS dbo.uf_assess_sheet;
GO

-- 2.3 全局能力库（先子后父：level -> element -> category）
DROP TABLE IF EXISTS dbo.uf_ability_element_level;
GO
DROP TABLE IF EXISTS dbo.uf_ability_element;
GO
DROP TABLE IF EXISTS dbo.uf_ability_category;
GO

-- 2.4 组织 / 岗位 / 人员能力要求
--     旧库另有 uf_position_ability_req / uf_user_ability_req / uf_score_weight 三张
--     v1 表，本环境早已不存在，这里用 IF EXISTS 兜住别的环境。
DROP TABLE IF EXISTS dbo.uf_position_ability_req_item;
GO
DROP TABLE IF EXISTS dbo.uf_position_ability_req_new;
GO
DROP TABLE IF EXISTS dbo.uf_position_ability_req;
GO
DROP TABLE IF EXISTS dbo.uf_user_ability_req_item;
GO
DROP TABLE IF EXISTS dbo.uf_user_ability_req_new;
GO
DROP TABLE IF EXISTS dbo.uf_user_ability_req;
GO
DROP TABLE IF EXISTS dbo.uf_org_ability_req;
GO
DROP TABLE IF EXISTS dbo.uf_score_weight;
GO

-- 2.5 旧配置：类别权重 / 测评人权重 / 等级标准 / 全局等级分值
DROP TABLE IF EXISTS dbo.uf_assess_weight_ability;
GO
DROP TABLE IF EXISTS dbo.uf_assess_weight_assessor;
GO
DROP TABLE IF EXISTS dbo.uf_assess_grade_level;
GO
DROP TABLE IF EXISTS dbo.uf_assess_level_score;
GO

-- 2.6 外部评分导入 / 智能导入 / 归档
DROP TABLE IF EXISTS dbo.uf_assess_external_import_log;
GO
DROP TABLE IF EXISTS dbo.uf_assess_agent_draft;
GO
DROP TABLE IF EXISTS dbo.uf_assess_import_batch_item;
GO
DROP TABLE IF EXISTS dbo.uf_assess_import_run;
GO
DROP TABLE IF EXISTS dbo.uf_assess_import_alias;
GO
DROP TABLE IF EXISTS dbo.uf_assess_report_snapshot;
GO

-- 2.7 历史备份表（前几轮清理留下的快照，一并清掉；本轮 _bak_20261121 不在其中）
DROP TABLE IF EXISTS dbo.uf_ability_element_bak_35;
GO
DROP TABLE IF EXISTS dbo.uf_position_ability_req_item_bak_20261102;
GO
DROP TABLE IF EXISTS dbo.uf_position_ability_req_item_bak_20261102b;
GO
DROP TABLE IF EXISTS dbo.uf_assess_sheet_rating_bak_20261118;
GO
DROP TABLE IF EXISTS dbo.uf_assess_sheet_rating_bak_20261118_s22;
GO
DROP TABLE IF EXISTS dbo.uf_assess_sheet_row_bak_20261102;
GO
DROP TABLE IF EXISTS dbo.uf_assess_proc_backup;
GO
DROP TABLE IF EXISTS dbo.uf_assess_proc_bak_20261102;
GO
DROP TABLE IF EXISTS dbo.uf_assess_proc_bak_20261102b;
GO

-- ------------------------------------------------------------
-- 3. DROP 旧存储过程
-- ------------------------------------------------------------
-- 这 8 个过程是旧模型的计算核心（600 行 usp_assess_recalc 等），
-- 打分逻辑已在 P2 搬到 Java（PaScoreService）并按口径 6 重写，
-- 保留过程只会让以后有人「顺手调一下」把新口径覆盖回旧口径。
PRINT '--- 3. DROP 旧存储过程 ---';
GO

DROP PROCEDURE IF EXISTS dbo.usp_assess_recalc;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_rate;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_annual_score;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_comment;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_external_score;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_external_clear;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_task_finish;
GO
DROP PROCEDURE IF EXISTS dbo.usp_assess_void;
GO

-- ------------------------------------------------------------
-- 4. 清空任务表（保留结构）
-- ------------------------------------------------------------
-- 新链路继续用这两张表，只是不认旧行：旧行带 rater_type='CLIENT'、
-- 指向已 DROP 的旧测评页，留着会在「我的评价」里变成一批死链接。
-- 先删评价人（逻辑子表），再删任务。
PRINT '--- 4. 清空 uf_assess_task* ---';
GO

DELETE FROM dbo.uf_assess_task_rater;
GO
DELETE FROM dbo.uf_assess_task;
GO

-- ------------------------------------------------------------
-- 5. 校验：旧对象应全部消失，保留对象仍在
-- ------------------------------------------------------------
PRINT '--- 5. 校验 ---';
GO

-- SQL Server 2016 没有 STRING_AGG（2017 才有），用 FOR XML PATH 拼名字。
-- 这一节只做人工核对，结果里 cnt = 0 / 空名字串 才算干净。
-- 注意每个 COUNT 都要带 FROM：漏了 FROM 的 COUNT(*) 恒为 1，CHECK 会假装「有 1 张残留」。
SELECT '残留旧表' AS chk, (SELECT COUNT(*) FROM sys.tables WHERE name IN (
                  'uf_ability_category','uf_ability_element','uf_ability_element_level',
                  'uf_org_ability_req','uf_position_ability_req','uf_position_ability_req_new',
                  'uf_position_ability_req_item','uf_user_ability_req','uf_user_ability_req_new',
                  'uf_user_ability_req_item','uf_score_weight','uf_assess_weight_ability',
                  'uf_assess_weight_assessor','uf_assess_grade_level','uf_assess_level_score',
                  'uf_assess_sheet','uf_assess_sheet_row','uf_assess_sheet_rating',
                  'uf_assess_sheet_log','uf_assess_comment','uf_assess_report_snapshot',
                  'uf_assess_external_import_log','uf_assess_agent_draft','uf_assess_import_run',
                  'uf_assess_import_batch_item','uf_assess_import_alias')) AS cnt,
       ISNULL(STUFF((SELECT ', ' + t.name FROM sys.tables t WHERE t.name IN (
                  'uf_ability_category','uf_ability_element','uf_ability_element_level',
                  'uf_org_ability_req','uf_position_ability_req','uf_position_ability_req_new',
                  'uf_position_ability_req_item','uf_user_ability_req','uf_user_ability_req_new',
                  'uf_user_ability_req_item','uf_score_weight','uf_assess_weight_ability',
                  'uf_assess_weight_assessor','uf_assess_grade_level','uf_assess_level_score',
                  'uf_assess_sheet','uf_assess_sheet_row','uf_assess_sheet_rating',
                  'uf_assess_sheet_log','uf_assess_comment','uf_assess_report_snapshot',
                  'uf_assess_external_import_log','uf_assess_agent_draft','uf_assess_import_run',
                  'uf_assess_import_batch_item','uf_assess_import_alias')
                FOR XML PATH('')), 1, 2, ''), '（无）') AS detail
UNION ALL
SELECT '残留旧过程', (SELECT COUNT(*) FROM sys.objects WHERE type = 'P' AND name IN (
                  'usp_assess_recalc','usp_assess_rate','usp_assess_annual_score',
                  'usp_assess_comment','usp_assess_external_score','usp_assess_external_clear',
                  'usp_assess_task_finish','usp_assess_void')),
       ISNULL(STUFF((SELECT ', ' + o.name FROM sys.objects o WHERE o.type = 'P' AND o.name IN (
                  'usp_assess_recalc','usp_assess_rate','usp_assess_annual_score',
                  'usp_assess_comment','usp_assess_external_score','usp_assess_external_clear',
                  'usp_assess_task_finish','usp_assess_void')
                FOR XML PATH('')), 1, 2, ''), '（无）')
UNION ALL
SELECT '任务表行数',
       (SELECT COUNT(*) FROM dbo.uf_assess_task) + (SELECT COUNT(*) FROM dbo.uf_assess_task_rater),
       'uf_assess_task + uf_assess_task_rater（应为 0）'
UNION ALL
SELECT '保留对象（应 15 张）', (SELECT COUNT(*) FROM sys.tables WHERE name IN (
                  'uf_assess_dimension','uf_assess_dimension_auth','uf_assess_admin',
                  'uf_assess_period','uf_push_msg','uf_assess_migration_log',
                  'uf_pa_post','uf_pa_category','uf_pa_item','uf_pa_item_level','uf_pa_band',
                  'uf_pa_person_post','uf_pa_sheet','uf_pa_sheet_item','uf_pa_rating')),
       ISNULL(STUFF((SELECT ', ' + t.name FROM sys.tables t WHERE t.name IN (
                  'uf_assess_dimension','uf_assess_dimension_auth','uf_assess_admin',
                  'uf_assess_period','uf_push_msg','uf_assess_migration_log',
                  'uf_pa_post','uf_pa_category','uf_pa_item','uf_pa_item_level','uf_pa_band',
                  'uf_pa_person_post','uf_pa_sheet','uf_pa_sheet_item','uf_pa_rating')
                FOR XML PATH('')), 1, 2, ''), '（无）')
UNION ALL
SELECT '本轮备份表（应 25 张）', (SELECT COUNT(*) FROM sys.tables WHERE name LIKE '%[_]bak_20261121'),
       ISNULL(STUFF((SELECT ', ' + t.name FROM sys.tables t WHERE t.name LIKE '%[_]bak_20261121'
                FOR XML PATH('')), 1, 2, ''), '（无）');
GO
