-- 15 全部业务表增加维度字段 dimension_id（类租户隔离）
-- 根表/结果表：NOT NULL DEFAULT 1（存量数据自动落入 id=1 的默认维度）
-- 泛微钩子直接写入的明细子表：可空（钩子不带维度，隔离通过父表 sheet 链路保证；本系统写入时冗余填充）
-- 脚本幂等：列/索引已存在则跳过；表不存在则跳过。

SET NOCOUNT ON;
GO

DECLARE @t NVARCHAR(128), @sql NVARCHAR(1000);

-- ============================================================
-- A. NOT NULL DEFAULT 1 的表（根配置 + 结果主链）
-- ============================================================
DECLARE dim_tables CURSOR LOCAL FAST_FORWARD FOR
SELECT t.name FROM sys.tables t WHERE t.schema_id = SCHEMA_ID('dbo') AND t.name IN (
    'uf_ability_category',
    'uf_ability_element',
    'uf_ability_element_level',
    'uf_org_ability_req',
    'uf_position_ability_req',
    'uf_position_ability_req_new',
    'uf_position_ability_req_item',
    'uf_user_ability_req',
    'uf_user_ability_req_new',
    'uf_user_ability_req_item',
    'uf_score_weight',
    'uf_assess_weight_ability',
    'uf_assess_weight_assessor',
    'uf_assess_grade_level',
    'uf_assess_level_score',
    'uf_assess_period',
    'uf_assess_sheet',
    'uf_assess_subject',
    'uf_assess_subject_item',
    'uf_assess_subject_client',
    'uf_assess_report_snapshot'
);
OPEN dim_tables;
FETCH NEXT FROM dim_tables INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF COL_LENGTH('dbo.' + @t, 'dimension_id') IS NULL
    BEGIN
        SET @sql = N'ALTER TABLE dbo.[' + @t + N'] ADD dimension_id BIGINT NOT NULL' +
                   N' CONSTRAINT [DF_' + @t + N'_dimension] DEFAULT (1);';
        EXEC (@sql);
        SET @sql = N'CREATE INDEX [IX_' + @t + N'_dimension] ON dbo.[' + @t + N'](dimension_id);';
        EXEC (@sql);
        PRINT 'dimension_id NOT NULL DEFAULT 1 -> ' + @t;
    END
    FETCH NEXT FROM dim_tables INTO @t;
END
CLOSE dim_tables;
DEALLOCATE dim_tables;
GO

-- ============================================================
-- B. 可空表（泛微钩子 usp_assess_rate 直接写入的明细子表）
-- ============================================================
DECLARE @t NVARCHAR(128);
DECLARE dim_tables2 CURSOR LOCAL FAST_FORWARD FOR
SELECT t.name FROM sys.tables t WHERE t.schema_id = SCHEMA_ID('dbo') AND t.name IN (
    'uf_assess_sheet_row',
    'uf_assess_sheet_rating',
    'uf_assess_sheet_log',
    'uf_assess_comment'
);
OPEN dim_tables2;
FETCH NEXT FROM dim_tables2 INTO @t;
WHILE @@FETCH_STATUS = 0
BEGIN
    IF COL_LENGTH('dbo.' + @t, 'dimension_id') IS NULL
    BEGIN
        EXEC (N'ALTER TABLE dbo.[' + @t + N'] ADD dimension_id BIGINT NULL;');
        EXEC (N'CREATE INDEX [IX_' + @t + N'_dimension] ON dbo.[' + @t + N'](dimension_id);');
        PRINT 'dimension_id NULL -> ' + @t;
    END
    FETCH NEXT FROM dim_tables2 INTO @t;
END
CLOSE dim_tables2;
DEALLOCATE dim_tables2;
GO
