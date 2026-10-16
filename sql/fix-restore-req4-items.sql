-- 修复脚本：2026-10-13 集成测试误提交（测试连接为自动提交）后的还原
-- 1) 恢复人员 2505 的能力要求明细（req_id=4，原 id 21–24），值与测试前查询结果一致
SET IDENTITY_INSERT dbo.uf_user_ability_req_item ON;
IF NOT EXISTS (SELECT 1 FROM dbo.uf_user_ability_req_item WHERE id IN (21,22,23,24))
BEGIN
    INSERT INTO dbo.uf_user_ability_req_item (id, req_id, element_id, level_id, score, create_time, category_id) VALUES
    (21, 4, 5,  16, 1.00, '2026-10-10 15:12:05.1633333', 6),
    (22, 4, 6,  22, 3.00, '2026-10-10 15:12:05.1633333', 7),
    (23, 4, 7,  29, 7.00, '2026-10-10 15:12:05.1666667', 8),
    (24, 4, 12, 54, 7.00, '2026-10-10 15:12:05.1700000', 9);
END
SET IDENTITY_INSERT dbo.uf_user_ability_req_item OFF;
GO
-- 2) 删除测试产生的测评数据（年度 2026 的页面与日志，均为测试写入）
DELETE FROM dbo.uf_assess_sheet_log WHERE sheet_id IN (SELECT s.id FROM dbo.uf_assess_sheet s JOIN dbo.uf_assess_period p ON p.id = s.period_id WHERE p.year = 2026);
DELETE FROM dbo.uf_assess_sheet_row WHERE sheet_id IN (SELECT s.id FROM dbo.uf_assess_sheet s JOIN dbo.uf_assess_period p ON p.id = s.period_id WHERE p.year = 2026);
DELETE FROM dbo.uf_assess_sheet WHERE period_id IN (SELECT id FROM dbo.uf_assess_period WHERE year = 2026);
DELETE FROM dbo.uf_assess_period WHERE year = 2026;
GO
