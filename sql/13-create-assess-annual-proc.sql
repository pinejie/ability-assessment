-- 13 年度分写入（泛微钩子入口）。年度分由钩子写入，测评侧只读。
-- 数据库：SQL Server 2016 RTM。使用 IF + DROP + CREATE。

IF OBJECT_ID('dbo.usp_assess_annual_score', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_annual_score;
GO
CREATE PROCEDURE dbo.usp_assess_annual_score
    @company_id          BIGINT,
    @year                INT,
    @resource_id         BIGINT,
    @annual_score        DECIMAL(6,2),
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

    -- 3. 年度分范围（50109）
    IF @annual_score IS NULL OR @annual_score < 0 OR @annual_score > 100
        THROW 50109, N'年度分应在 0–100 之间', 1;

    BEGIN TRAN;

    UPDATE dbo.uf_assess_sheet
    SET annual_score = @annual_score, update_time = SYSDATETIME()
    WHERE id = @sheet_id;

    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'ANNUAL', @resource_id,
            CONCAT(N'{"annualScore":', @annual_score, N',"workflowRequestId":', @workflow_request_id, N'}'),
            SYSDATETIME());

    COMMIT;

    SELECT id AS sheetId, annual_score FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
END
GO
