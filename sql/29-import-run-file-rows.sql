-- 29 一键导入：记录解析文件的数据总行数（deliver 行对账的锚点）
-- parse_import_file 解析后由 cp_server 上报（POST /api/v1/agent/parse-stats，内部令牌）；
-- AgentBridgeService.submitDraft 交付时校验 coveredRows ∪ skipped 覆盖物理行 2..file_rows+1，
-- 任一文件行去向不明即拒稿并回纠错指令——异常数据（超范围列/查无此人/无要求/冲突取一）必须在
-- 确认页与最终回执中逐行交代，不允许无痕丢弃。
-- 列缺失时后端自动跳过对账（warn 日志），不阻断导入。
IF COL_LENGTH('dbo.uf_assess_import_run', 'file_rows') IS NULL
    ALTER TABLE dbo.uf_assess_import_run ADD file_rows INT NULL;
GO
