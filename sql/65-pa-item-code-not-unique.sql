-- ============================================================
-- 65 能力项编码不再要求岗位内唯一
-- ------------------------------------------------------------
-- 背景：能力的唯一标识是「部门 + 岗位 + 能力项」，编码只是给人看的参考号。
-- 但 UX_pa_item_code (dimension_id, post_id, item_code) 把编码当成了键，
-- 于是「同一张表里编码分两段用」的文件导不进来 —— 实测 2026-11-23 的
-- 「综合服务部.xlsx」：每张 sheet 前 7 行是专业能力（A01…G01），
-- 第 11 行起是职业素养/个性特质/发展潜力，编码又从 E01 重来，
-- 于是同一岗位里 E01 / F01 / G01 各出现两次，导入直接撞唯一索引失败。
-- 这是文件的正常写法（两段各自编号），不是数据错误。
--
-- 口径：编码可以为空、可以重复，重复时靠 item_name 区分。
--   · 所有关联（评级、填写、报告）走 item_id，从不按编码查 —— 放开无歧义
--   · 页面展示重复编码没问题，同岗位内能力项名称本身是唯一的
--
-- 唯一标识不变：仍是 (dimension_id, department_id, post_name) 的岗位，
-- 加该岗位下的能力项本身。
--
-- 存量数据：本脚本只删索引，不改任何一行。当前库里没有同岗位同编码的数据
-- （2026-11-23 核对：0 条），删索引不影响已有能力项。
--
-- 相关代码改动（同一个需求）：
--   · PaImportValidator.checkPost      —— 删掉「编码重复」这条阻断
--   · PaImportApplier.writeContent     —— 匹配从「编码优先」改成「编码+名称」三级匹配，
--     否则两个都叫 E01 的项会互相覆盖（重复编码被静默吞掉）
--   · PaImportValidator.diff / keyOf   —— 预览比对同一套三级匹配
--   · PaModelServiceImpl.assertItemCodeUnique —— 手工编辑不再拦重复编码
--
-- 幂等：重复执行不会报错（索引已不存在时跳过）。
-- 回滚：
--   -- 先清掉重复编码，否则建索引会失败
--   --   SELECT dimension_id, post_id, item_code, COUNT(*) FROM uf_pa_item
--   --   WHERE deleted = 0 AND item_code IS NOT NULL
--   --   GROUP BY dimension_id, post_id, item_code HAVING COUNT(*) > 1;
--   CREATE UNIQUE INDEX UX_pa_item_code ON dbo.uf_pa_item(dimension_id, post_id, item_code)
--       WHERE deleted = 0 AND item_code IS NOT NULL;
-- ============================================================

USE ecology;
GO

IF EXISTS (SELECT 1 FROM sys.indexes
           WHERE name = 'UX_pa_item_code' AND object_id = OBJECT_ID('dbo.uf_pa_item'))
BEGIN
    DROP INDEX UX_pa_item_code ON dbo.uf_pa_item;
    PRINT '已删除 UX_pa_item_code：能力项编码不再要求岗位内唯一';
END
ELSE
    PRINT 'UX_pa_item_code 已不存在，跳过';
GO

PRINT '65 完成：能力项编码允许重复（唯一标识仍是部门+岗位+能力项）';
