-- 11 人员能力要求按年 + 测评同步标记（additive，不改变已有列）
-- 1) 主表 uf_user_ability_req_new 增加年份
-- 2) 测评汇总页面头增加 req_changed：编辑人员能力要求时选择"不同步"，页面标记为与人员能力要求不一致

IF COL_LENGTH('dbo.uf_user_ability_req_new', 'assess_year') IS NULL
    ALTER TABLE dbo.uf_user_ability_req_new ADD assess_year INT NULL;
GO
-- 现有记录均为 2026 年创建
UPDATE dbo.uf_user_ability_req_new SET assess_year = 2026 WHERE assess_year IS NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UQ_user_ability_req_new_res_year' AND object_id = OBJECT_ID('dbo.uf_user_ability_req_new'))
    CREATE UNIQUE INDEX UQ_user_ability_req_new_res_year ON dbo.uf_user_ability_req_new (resource_id, assess_year);
GO
IF COL_LENGTH('dbo.uf_assess_sheet', 'req_changed') IS NULL
    ALTER TABLE dbo.uf_assess_sheet ADD req_changed INT NOT NULL CONSTRAINT DF_assess_sheet_req_changed DEFAULT 0;
GO
