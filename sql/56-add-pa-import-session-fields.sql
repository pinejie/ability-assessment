-- ============================================================
-- 56 能力结构导入会话：补两列（overview / user_plan）
-- ------------------------------------------------------------
-- 55 建表时定了会话的骨架字段，跑通链路后发现还有两块状态必须落库：
--
--   overview  —— 文件总览（每张 sheet 是不是岗位模型、叫什么岗位）。
--                这一步的输出是「用户勾选哪些 sheet」的依据，页面上要能显示。
--                塞进 mapping 会破坏 mapping「模型认出的列映射」这个单一语义，
--                而且总览与列映射的失效时机不同（换 sheet 选择时总览不变、映射全变）。
--
--   user_plan —— 用户当前做出的决定：导出哪些 sheet、已确认的回答（slot→值）、
--                覆盖策略、是否重算封存报告、是否撤回进行中任务。
--                这些决定跨请求存在，且每改一个勾选就要重算预览，
--                不落库就无法支持「切页面回来接着调」。
--                列名不能叫 plan：PLAN 是 SQL Server 保留字（实测建列直接报 156 语法错），
--                写成 [plan] 能用，但那样每处原生 SQL 都要带方括号，迟早漏一个。
--
-- 两列都可空：老会话（55 之后、56 之前创建的）读出来是 NULL，走「重新上传」即可。
-- 幂等：列不存在才加。回滚：ALTER TABLE ... DROP COLUMN overview, user_plan。
-- ============================================================

USE ecology;
GO

IF COL_LENGTH('dbo.uf_pa_import_session', 'overview') IS NULL
    ALTER TABLE dbo.uf_pa_import_session ADD overview NVARCHAR(MAX) NULL;
GO

IF COL_LENGTH('dbo.uf_pa_import_session', 'user_plan') IS NULL
    ALTER TABLE dbo.uf_pa_import_session ADD user_plan NVARCHAR(MAX) NULL;
GO

-- 上次执行到一半时可能已经建了一个叫 plan 的列，这里清掉（幂等重跑）。
-- 关键字列必须带方括号，否则 DROP 同样报 156。
IF COL_LENGTH('dbo.uf_pa_import_session', 'plan') IS NOT NULL
    ALTER TABLE dbo.uf_pa_import_session DROP COLUMN [plan];
GO
