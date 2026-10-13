-- 12 个人测评报告：年度分、上级/客户评价（泛微钩子写入）、年度关闭冻结快照
-- 数据库：SQL Server 2016 RTM。使用 IF + DROP + CREATE（不支持 CREATE OR ALTER）。

IF COL_LENGTH('dbo.uf_assess_sheet', 'annual_score') IS NULL
    ALTER TABLE dbo.uf_assess_sheet ADD annual_score DECIMAL(6,2) NULL;
GO

-- 意见表：每个页面每种意见一条，重复写入覆盖
IF OBJECT_ID('dbo.uf_assess_comment', 'U') IS NULL
CREATE TABLE dbo.uf_assess_comment (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    company_id          BIGINT NOT NULL,
    sheet_id            BIGINT NOT NULL,
    comment_type        VARCHAR(10) NOT NULL
        CONSTRAINT ck_assess_comment_type CHECK (comment_type IN ('LEADER', 'CLIENT')),
    content             NVARCHAR(2000) NOT NULL,
    workflow_request_id BIGINT NULL,
    update_time         DATETIME2 NOT NULL DEFAULT SYSDATETIME(),
    CONSTRAINT UQ_assess_comment UNIQUE (sheet_id, comment_type)
);
GO

-- 冻结快照：年度关闭时写入，重新开启时删除
IF OBJECT_ID('dbo.uf_assess_report_snapshot', 'U') IS NULL
CREATE TABLE dbo.uf_assess_report_snapshot (
    sheet_id    BIGINT NOT NULL PRIMARY KEY,
    report_json NVARCHAR(MAX) NOT NULL,
    freeze_time DATETIME2 NOT NULL DEFAULT SYSDATETIME()
);
GO

-- 意见写入（泛微钩子入口）。年度已关闭拒绝写入；同一类型覆盖。
IF OBJECT_ID('dbo.usp_assess_comment', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_comment;
GO
CREATE PROCEDURE dbo.usp_assess_comment
    @company_id          BIGINT,
    @year                INT,
    @resource_id         BIGINT,
    @comment_type        VARCHAR(10),
    @content             NVARCHAR(2000),
    @workflow_request_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @sheet_id BIGINT, @period_id BIGINT, @period_status INT;

    -- 1. 定位页面（50101）
    SELECT @sheet_id = s.id, @period_id = s.period_id
    FROM dbo.uf_assess_sheet s
    JOIN dbo.uf_assess_period p ON p.id = s.period_id
    WHERE s.company_id = @company_id AND p.year = @year AND s.resource_id = @resource_id;

    IF @sheet_id IS NULL
        THROW 50101, N'未找到页面（被测人未配备人员能力要求）', 1;

    -- 2. 年度状态（50105）
    SELECT @period_status = status FROM dbo.uf_assess_period WHERE id = @period_id;
    IF @period_status = 1
        THROW 50105, N'年度已关闭', 1;

    -- 3. 意见类型与内容（50107 / 50108）
    IF @comment_type NOT IN ('LEADER', 'CLIENT')
        THROW 50107, N'意见类型不合法', 1;
    IF @content IS NULL OR LEN(LTRIM(RTRIM(@content))) = 0
        THROW 50108, N'意见内容不能为空', 1;

    BEGIN TRAN;

    MERGE dbo.uf_assess_comment WITH (HOLDLOCK) AS t
    USING (SELECT @sheet_id AS sheet_id, @comment_type AS comment_type) AS s
       ON t.sheet_id = s.sheet_id AND t.comment_type = s.comment_type
    WHEN MATCHED THEN
        UPDATE SET t.content = @content, t.workflow_request_id = @workflow_request_id, t.update_time = SYSDATETIME()
    WHEN NOT MATCHED THEN
        INSERT (company_id, sheet_id, comment_type, content, workflow_request_id, update_time)
        VALUES (@company_id, @sheet_id, @comment_type, @content, @workflow_request_id, SYSDATETIME());

    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'COMMENT', @resource_id,
            CONCAT(N'{"commentType":"', @comment_type, N'","workflowRequestId":', @workflow_request_id, N'}'),
            SYSDATETIME());

    COMMIT;

    SELECT @sheet_id AS sheetId, @comment_type AS commentType;
END
GO
