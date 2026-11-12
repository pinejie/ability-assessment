-- 能力结构导入：多 sheet 行对账（方案 14 §四 / §六）
-- parse_structure_file 解析后上报每张纳入解析的结构表数据行块，deliver 时后端据此校验
-- coveredRows / skipped 是否交代了每一张表的每一行（行号写法：表名!行号）。
-- 评分导入沿用 file_rows（单表，物理行号 2..n+1），两者互不影响。

IF COL_LENGTH('uf_assess_import_run', 'structure_file_stats') IS NULL
BEGIN
    ALTER TABLE uf_assess_import_run ADD structure_file_stats NVARCHAR(MAX) NULL;
END
GO
