-- ============================================================
-- 64 测评报告封存
-- ------------------------------------------------------------
-- 背景：原来「全员评完 → 自动算分 → 本人立刻能看报告」是一条硬链路，
-- 中间没有人工确认。HR 的诉求是：算完分先由自己核对一遍，确认无误后
-- 点「确认封存」，被测评人、直接上级、下属这些自助视角才看得到。
--
-- 口径：
--   · 封存是**可见性开关 + 内容快照**两件事，不是单纯的状态位。
--     只开关不冻内容的话，封存之后只要有人重算 / 重新导入评级，
--     本人看到的报告就悄悄变了 —— 「已封存」这个标签等于撒谎。
--     所以封存时把整份报告（含等级名、刻度名、评语分段）存进 sealed_json，
--     已封存的页面读快照，解除封存后回到实时数据。
--   · 管理端（测评报告页）不受封存影响，任何时候都能看实时数据。
--   · 未算出分的页面（status <> 2）不允许封存 —— 封一份空报告没有意义。
--   · 已封存的页面不允许撤回 / 重算 / 重新发起，要先解除封存。
--     这三个动作都会改写分数或清空页面，放行等于让快照与库里的数据分叉。
--
-- 存量数据：所有已有页面 seal_status = 0（未封存），即**上线后本人暂时看不到报告**，
-- 需要 HR 逐份确认封存。这是刻意的：批量置为已封存等于「没人核对过也盖章」。
--
-- 幂等：列已存在时跳过。
-- 回滚：
--   ALTER TABLE dbo.uf_pa_sheet DROP CONSTRAINT DF_pa_sheet_seal_status;
--   ALTER TABLE dbo.uf_pa_sheet DROP COLUMN seal_status, seal_time,
--         seal_by_loginid, seal_by_name, sealed_json;
-- ============================================================

USE ecology;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet') AND name = 'seal_status')
BEGIN
    ALTER TABLE dbo.uf_pa_sheet ADD seal_status INT NOT NULL
        CONSTRAINT DF_pa_sheet_seal_status DEFAULT 0;
    PRINT 'uf_pa_sheet.seal_status 已添加（0 未封存 / 1 已封存）';
END
ELSE
BEGIN
    PRINT 'uf_pa_sheet.seal_status 已存在，跳过';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet') AND name = 'seal_time')
    ALTER TABLE dbo.uf_pa_sheet ADD seal_time DATETIME2 NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet') AND name = 'seal_by_loginid')
    ALTER TABLE dbo.uf_pa_sheet ADD seal_by_loginid NVARCHAR(50) NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet') AND name = 'seal_by_name')
    ALTER TABLE dbo.uf_pa_sheet ADD seal_by_name NVARCHAR(50) NULL;
GO

-- 封存快照：整份报告（PaReportVO.Detail）的 JSON。
-- 为什么存整份而不是只存版本号：报告里的等级名、刻度名、评语都是算分那一刻的快照，
-- 重新拼一遍就等于把「当时的解释」换成「现在的配置」，封存的意义就没了。
IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_pa_sheet') AND name = 'sealed_json')
    ALTER TABLE dbo.uf_pa_sheet ADD sealed_json NVARCHAR(MAX) NULL;
GO

-- 自查页只读已封存的报告，按 (resource_id, seal_status) 走的是现有
-- UX_pa_sheet_period_res 的唯一索引前缀之外，加一个窄索引让「我的报告」不必扫全表。
-- 表量级是「人数 × 年度」，几万行，加索引代价很小。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_sheet_seal' AND object_id = OBJECT_ID('dbo.uf_pa_sheet'))
    CREATE INDEX IX_pa_sheet_seal ON dbo.uf_pa_sheet(resource_id, seal_status);
GO

PRINT '64 完成：测评报告支持封存（可见性开关 + 内容快照）';
GO
