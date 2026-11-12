-- 33 能力结构智能导入（方案 14 §五）：交付稿/批次区分类型 + 别名记忆 + 批次写入台账
-- 数据库：SQL Server 2016 RTM。幂等，可重复执行。
-- 兼容：新增列均带默认值 EXTERNAL_SCORE，现有评分导入行为不变（DEFAULT 作用于存量行）。

-- 1) 交付稿类型：EXTERNAL_SCORE（评分导入）/ ABILITY_STRUCTURE（能力结构导入）
IF COL_LENGTH('dbo.uf_assess_agent_draft', 'kind') IS NULL
    ALTER TABLE dbo.uf_assess_agent_draft ADD kind VARCHAR(20) NOT NULL
        CONSTRAINT DF_agent_draft_kind DEFAULT 'EXTERNAL_SCORE';
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE name = 'CK_agent_draft_kind' AND parent_object_id = OBJECT_ID('dbo.uf_assess_agent_draft'))
    ALTER TABLE dbo.uf_assess_agent_draft ADD CONSTRAINT CK_agent_draft_kind
        CHECK (kind IN ('EXTERNAL_SCORE', 'ABILITY_STRUCTURE'));
GO

-- 2) 结构导入的年度在 payload 内（人员要求 assessYear），draft 行本身可空
IF EXISTS (SELECT 1 FROM sys.columns WHERE object_id = OBJECT_ID('dbo.uf_assess_agent_draft')
           AND name = 'year' AND is_nullable = 0)
    ALTER TABLE dbo.uf_assess_agent_draft ALTER COLUMN year INT NULL;
GO

-- 3) 批次类型（决定提示词模板与校验路径）
IF COL_LENGTH('dbo.uf_assess_import_run', 'kind') IS NULL
    ALTER TABLE dbo.uf_assess_import_run ADD kind VARCHAR(20) NOT NULL
        CONSTRAINT DF_import_run_kind DEFAULT 'EXTERNAL_SCORE';
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE name = 'CK_import_run_kind' AND parent_object_id = OBJECT_ID('dbo.uf_assess_import_run'))
    ALTER TABLE dbo.uf_assess_import_run ADD CONSTRAINT CK_import_run_kind
        CHECK (kind IN ('EXTERNAL_SCORE', 'ABILITY_STRUCTURE'));
GO

-- 4) 导入映射记忆表（方案 11 §10.4）：第一次问，之后自动套用；按维度隔离
IF OBJECT_ID('dbo.uf_assess_import_alias', 'U') IS NULL
CREATE TABLE dbo.uf_assess_import_alias (
    id            BIGINT IDENTITY(1,1) NOT NULL,
    company_id    BIGINT        NOT NULL,
    dimension_id  BIGINT        NOT NULL,
    kind          VARCHAR(20)   NOT NULL,              -- ELEMENT/CATEGORY/DEPARTMENT/JOB_TITLE/RESOURCE
    source_name   NVARCHAR(200) NOT NULL,              -- 文件写法（已归一化：去空格/全半角/标点）
    target_id     BIGINT        NOT NULL,              -- 系统实体 ID
    target_name   NVARCHAR(200) NOT NULL,              -- 确认时的目标名（展示/审计用）
    hit_count     INT           NOT NULL CONSTRAINT DF_import_alias_hit DEFAULT 0,
    confirmed_by  BIGINT        NOT NULL,
    confirmed_at  DATETIME2     NOT NULL CONSTRAINT DF_import_alias_ct DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_import_alias PRIMARY KEY (id),
    CONSTRAINT UQ_import_alias UNIQUE (company_id, dimension_id, kind, source_name)
);
GO

-- 5) 批次写入台账（方案 14 §七 撤销的依据；只记 CREATE，覆盖记 UPDATE 需人工确认）
IF OBJECT_ID('dbo.uf_assess_import_batch_item', 'U') IS NULL
CREATE TABLE dbo.uf_assess_import_batch_item (
    id          BIGINT IDENTITY(1,1) NOT NULL,
    batch_id    BIGINT        NOT NULL,                -- = uf_assess_import_run.id
    entity_type VARCHAR(20)   NOT NULL,                -- CATEGORY/ELEMENT/LEVEL/POSITION_REQ/USER_REQ/SHEET
    entity_id   BIGINT        NOT NULL,
    natural_key NVARCHAR(300) NULL,                    -- 部门|岗位|要素 等，便于人工核对
    action      VARCHAR(10)   NOT NULL,                -- CREATE / UPDATE
    created_at  DATETIME2     NOT NULL CONSTRAINT DF_import_batch_item_ct DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_import_batch_item PRIMARY KEY (id)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_import_batch_item_batch'
               AND object_id = OBJECT_ID('dbo.uf_assess_import_batch_item'))
    CREATE INDEX IX_import_batch_item_batch ON dbo.uf_assess_import_batch_item (batch_id, entity_type);
GO
