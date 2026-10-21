-- 14 维度（类租户隔离）主数据
-- uf_assess_dimension：维度表；id=1 固定为「默认维度」，存量数据全部挂在该维度下。
-- uf_assess_dimension_auth：维度授权表，控制哪些分部/哪些人可使用哪些维度。
--   未配置任何授权行的维度 = 所有人可用（默认维度天然全员可用）。

IF OBJECT_ID('dbo.uf_assess_dimension', 'U') IS NULL
CREATE TABLE dbo.uf_assess_dimension (
    id             BIGINT IDENTITY(1,1) PRIMARY KEY,
    dimension_name NVARCHAR(100) NOT NULL,
    dimension_code NVARCHAR(50)  NULL,
    description    NVARCHAR(500) NULL,
    status         INT NOT NULL DEFAULT 1,          -- 1-启用 0-禁用
    sort           INT NOT NULL DEFAULT 0,
    create_by      BIGINT NULL,
    create_time    DATETIME2 DEFAULT GETDATE(),
    update_by      BIGINT NULL,
    update_time    DATETIME2 DEFAULT GETDATE(),
    deleted        INT NOT NULL DEFAULT 0
);
GO

-- 默认维度（id=1）
IF NOT EXISTS (SELECT 1 FROM dbo.uf_assess_dimension WHERE id = 1)
BEGIN
    SET IDENTITY_INSERT dbo.uf_assess_dimension ON;
    INSERT INTO dbo.uf_assess_dimension (id, dimension_name, dimension_code, description, status, sort)
    VALUES (1, N'默认维度', 'DEFAULT', N'存量数据默认归属维度', 1, 0);
    SET IDENTITY_INSERT dbo.uf_assess_dimension OFF;
END
GO

IF OBJECT_ID('dbo.uf_assess_dimension_auth', 'U') IS NULL
CREATE TABLE dbo.uf_assess_dimension_auth (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    dimension_id BIGINT NOT NULL,
    auth_type   INT NOT NULL,                       -- 1-分部(HrmSubCompany) 2-人员(HrmResource)
    auth_id     BIGINT NOT NULL,
    create_time DATETIME2 DEFAULT GETDATE(),
    CONSTRAINT uk_dimension_auth UNIQUE (dimension_id, auth_type, auth_id)
);
GO
