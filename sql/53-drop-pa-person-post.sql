-- ============================================================
-- 53 下线「人员岗位映射」表（方案 17 增补 · 2026-11-21）
-- ------------------------------------------------------------
-- 背景：
--   原设计里，发起测评前必须先在「人员岗位映射」页把「人 + 年度 + 岗位」配一遍，
--   发起页只能从这张表里挑人 —— 换句话说，这张表只是发起动作的一个前置手续，
--   没有任何其他读方（报告、任务管理、算分全部读 uf_pa_sheet 上的 post_name 快照）。
--
--   现在岗位在发起页当场选择（PaTaskDTO.CreateRequest.postId），
--   这张表连同它的页面、接口、实体一起下线。
--
-- 口径变化（唯一一处，需要知道）：
--   「一年一人一岗唯一」不再由 uf_pa_person_post 的 (period_id, resource_id) 唯一键表达，
--   改由 uf_pa_sheet 的 (period_id, resource_id) 唯一索引保证 —— 一人一年只有一页，
--   重新发起会复用同一页并把 post_id / post_name 换成新岗位。
--   因此「同一人同一年换岗位重新测评」是**覆盖**，不是并存两份报告。
--
-- 影响面：
--   · 存量 uf_pa_sheet.post_id 全部保留，历史报告不受影响；
--   · 存量 uf_pa_person_post 只是配置，删掉不改变任何已发生的评价；
--   · 代码侧已同步删除：PaPersonPostController / Service / Impl / Entity / Mapper /
--     VO / DTO、MyBatisPlusConfig 的租户表项、维度数据检查清单项、
--     EcologyDataController 的 requireYear 过滤。
--
-- 备份策略：与 sql/51 同款 —— DROP 前先 SELECT * INTO <表>_bak_20261121。
--   备份表留在库里（不参与任何业务），确认无异议后可另行 DROP。
--   脚本幂等：备份只在备份表不存在时做一次，DROP 用 IF EXISTS。
--
-- 执行：DB_SECRET_FILE=config/secret.yml node sql/run-ddl.js sql/53-drop-pa-person-post.sql
-- 回滚：备份表在，需要时 SELECT * INTO dbo.uf_pa_person_post FROM dbo.uf_pa_person_post_bak_20261121;
--       但请注意：即使把数据还原回来，代码里也已经没有读它的地方了。
-- ============================================================

USE ecology;
GO

PRINT '--- 删除前的行数（应为存量配置行，不含业务评价） ---';
GO
IF OBJECT_ID('dbo.uf_pa_person_post','U') IS NOT NULL
    SELECT COUNT(*) AS person_post_rows FROM dbo.uf_pa_person_post;
GO

PRINT '--- 1. 备份 ---';
GO
IF OBJECT_ID('dbo.uf_pa_person_post','U') IS NOT NULL
   AND OBJECT_ID('dbo.uf_pa_person_post_bak_20261121','U') IS NULL
    SELECT * INTO dbo.uf_pa_person_post_bak_20261121 FROM dbo.uf_pa_person_post;
GO

PRINT '--- 2. 删除表 ---';
GO
IF OBJECT_ID('dbo.uf_pa_person_post','U') IS NOT NULL
    DROP TABLE dbo.uf_pa_person_post;
GO

PRINT '--- 删除后自检：原表应为 NULL，备份表应有行 ---';
GO
SELECT
    OBJECT_ID('dbo.uf_pa_person_post','U')          AS person_post_object_id,   -- 应为 NULL
    OBJECT_ID('dbo.uf_pa_person_post_bak_20261121','U') AS backup_object_id;   -- 应为非 NULL
GO
