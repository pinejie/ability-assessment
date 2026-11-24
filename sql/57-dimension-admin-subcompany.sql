-- ============================================================
-- 57 维度补充两项配置：所属分部 + 维度管理员
-- ------------------------------------------------------------
-- 需求（2026-11-23）：维度设置页要能回答两个问题 ——
--   「这个维度属于哪个子公司」  → uf_assess_dimension.subcompany_id
--   「这个维度归谁管」          → uf_assess_dimension_admin
--
-- 为什么单独一张表而不复用 uf_assess_dimension_auth：
--   那张表的语义是「哪些人/分部**能用**这个维度」（决定顶栏能看到几个租户、
--   请求头维度的越权校验）。维度管理员是「谁能**管**这个维度」，两件事的
--   生命周期也不同（授权会随组织调整批量改，管理员是少数几个固定的人）。
--   混进同一张表后，authCount 这个列表上的「授权条目数」会把管理员算进去，
--   授权页保存时又是整体覆盖，一存就把管理员冲掉 —— 那是迟早要踩的坑。
--
-- subcompany_id 只是属性，不参与授权判定：
--   授权仍然只走 uf_assess_dimension_auth。若所属分部也顺带放行，
--   同一个效果就有两个入口，人事改了一个另一个没改，排查时无从下手。
--
-- 可空：存量维度（3 个）读出来是 NULL，页面上显示「—」，不影响任何逻辑。
-- 幂等：列/表不存在才建；索引同理。回滚：DROP 表 + DROP COLUMN subcompany_id。
-- ============================================================

USE ecology;
GO

IF COL_LENGTH('dbo.uf_assess_dimension', 'subcompany_id') IS NULL
    ALTER TABLE dbo.uf_assess_dimension ADD subcompany_id BIGINT NULL;
GO

IF OBJECT_ID('dbo.uf_assess_dimension_admin', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_assess_dimension_admin (
        id            BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id  BIGINT       NOT NULL,
        resource_id   BIGINT       NOT NULL,   -- HrmResource.id
        create_by     BIGINT       NULL,
        create_time   DATETIME2    NOT NULL CONSTRAINT DF_dim_admin_ct DEFAULT SYSDATETIME(),
        CONSTRAINT PK_uf_assess_dimension_admin PRIMARY KEY (id),
        CONSTRAINT UX_dim_admin UNIQUE (dimension_id, resource_id)
    );
END
GO

-- 按人反查「我是哪些维度的管理员」：报告页每打开一次要判一次权限
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'IX_dim_admin_res' AND object_id = OBJECT_ID('dbo.uf_assess_dimension_admin'))
    CREATE INDEX IX_dim_admin_res ON dbo.uf_assess_dimension_admin(resource_id);
GO

PRINT N'57-dimension-admin-subcompany 执行完成：uf_assess_dimension.subcompany_id / uf_assess_dimension_admin';
GO
