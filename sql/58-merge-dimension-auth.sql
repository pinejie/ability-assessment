-- ============================================================
-- 58 维度配置合并：所属分部 / 维度管理员 并入「授权配置」
-- ------------------------------------------------------------
-- 需求（2026-11-23 第二版）：维度设置页上三处配置语义重叠，页面上分不清 ——
--   「所属分部」   编辑弹窗里单选，只作归属标识
--   「维度管理员」 编辑弹窗里多选，决定报告里 360 姓名露不露
--   「授权配置」   另一个弹窗，分部/人员决定谁能用这个维度
-- 合并后只剩「授权配置」一处：
--   分部（可多选）= 这个维度归属哪几家 + 这些分部的人可用
--   人员（可多选）= 这些人可用，同时是维度管理员（能看到 360 测评人姓名）
--
-- 数据怎么处理：
--   1. uf_assess_dimension.subcompany_id → uf_assess_dimension_auth (auth_type=1)
--      存量维度 3「中达集团」配的是 subcompany_id=1，转成一条分部授权。
--      ⚠ 语义会放大：授权是子树语义（勾一级分部覆盖全部下级分支），
--      维度 3 的可访问范围从「3 个管理员」变成「中达集团整棵树的人员」。
--      这是「所属分部=授权分部」合并后的必然结果，执行前请确认。
--   2. uf_assess_dimension_admin → uf_assess_dimension_auth (auth_type=2)
--      ⚠ 这些人在合并后同时成为维度管理员，即能看到 360 测评人真实姓名。
--
--   3. 两张来源都清掉：DROP 列 + DROP 表。
--      只保留 uf_assess_dimension_auth 一个真相源。留着冗余列，下次谁改了一边没改另一边，
--      排查起来比现在三处配置还乱。
--
-- 可重入：每一步都先判断是否存在；迁移用 NOT EXISTS 去重。
-- 回滚：数据不丢 —— auth 表的 type=1 就是 subcompany_id，type=2 就是管理员名单，
--       需要旧结构时按这两条 INSERT ... SELECT 反向重建即可。
-- ============================================================

USE ecology;
GO

-- 1. 所属分部 → 分部授权
IF COL_LENGTH('dbo.uf_assess_dimension', 'subcompany_id') IS NOT NULL
BEGIN
    INSERT INTO dbo.uf_assess_dimension_auth (dimension_id, auth_type, auth_id, create_time)
    SELECT d.id, 1, d.subcompany_id, SYSDATETIME()
    FROM dbo.uf_assess_dimension d
    WHERE d.subcompany_id IS NOT NULL
      AND NOT EXISTS (
            SELECT 1 FROM dbo.uf_assess_dimension_auth a
            WHERE a.dimension_id = d.id AND a.auth_type = 1 AND a.auth_id = d.subcompany_id);
    PRINT N'58-1 所属分部转为分部授权完成';
END
GO

-- 2. 维度管理员 → 人员授权
IF OBJECT_ID('dbo.uf_assess_dimension_admin', 'U') IS NOT NULL
BEGIN
    INSERT INTO dbo.uf_assess_dimension_auth (dimension_id, auth_type, auth_id, create_time)
    SELECT m.dimension_id, 2, m.resource_id, SYSDATETIME()
    FROM dbo.uf_assess_dimension_admin m
    WHERE NOT EXISTS (
            SELECT 1 FROM dbo.uf_assess_dimension_auth a
            WHERE a.dimension_id = m.dimension_id AND a.auth_type = 2 AND a.auth_id = m.resource_id);
    PRINT N'58-2 维度管理员转为人员授权完成';
END
GO

-- 3. 清掉冗余结构（此时数据已全部落在 auth 表）
IF OBJECT_ID('dbo.uf_assess_dimension_admin', 'U') IS NOT NULL
    DROP TABLE dbo.uf_assess_dimension_admin;
GO

IF COL_LENGTH('dbo.uf_assess_dimension', 'subcompany_id') IS NOT NULL
    ALTER TABLE dbo.uf_assess_dimension DROP COLUMN subcompany_id;
GO

PRINT N'58-merge-dimension-auth 执行完成：所属分部 / 维度管理员 已并入 uf_assess_dimension_auth';
GO
