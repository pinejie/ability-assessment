-- 19 修正阻碍维度隔离的三个唯一约束（补 dimension_id）
-- 背景：uf_assess_weight_assessor / uf_assess_level_score / uf_assess_period 的旧唯一键
--   只按 company_id(+code/level/year)，导致第二个维度无法配置自己的对象权重、等级分值、年度
--   （sql/18 复制脚本曾因此被 2627 拦截）。
-- 其余 uk（weight_ability 按 category_id、element_level 按 element_id）天然随维度隔离，不动。
-- 本脚本幂等；末尾补做 18 号脚本中被约束阻断的三张表 + 年度的复制（NOT EXISTS 防重）。

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name='uk_assess_weight_assessor' AND object_id=OBJECT_ID('dbo.uf_assess_weight_assessor'))
BEGIN
    ALTER TABLE dbo.uf_assess_weight_assessor DROP CONSTRAINT uk_assess_weight_assessor;
    ALTER TABLE dbo.uf_assess_weight_assessor ADD CONSTRAINT uk_assess_weight_assessor UNIQUE (dimension_id, company_id, assessor_code);
END
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name='uk_assess_level_score' AND object_id=OBJECT_ID('dbo.uf_assess_level_score'))
BEGIN
    ALTER TABLE dbo.uf_assess_level_score DROP CONSTRAINT uk_assess_level_score;
    ALTER TABLE dbo.uf_assess_level_score ADD CONSTRAINT uk_assess_level_score UNIQUE (dimension_id, company_id, level_no);
END
GO

IF EXISTS (SELECT 1 FROM sys.indexes WHERE name='UQ_uf_assess_period_year' AND object_id=OBJECT_ID('dbo.uf_assess_period'))
BEGIN
    ALTER TABLE dbo.uf_assess_period DROP CONSTRAINT UQ_uf_assess_period_year;
    ALTER TABLE dbo.uf_assess_period ADD CONSTRAINT UQ_uf_assess_period_year UNIQUE (dimension_id, company_id, [year]);
END
GO

-- 补做 sql/18 中被上述约束阻断的复制（源=1，目标=3；已存在的行自动跳过）
SET NOCOUNT ON;
DECLARE @src_dim BIGINT = 1, @dst_dim BIGINT = 3;

INSERT INTO uf_assess_weight_assessor (company_id, assessor_code, weight, update_by, update_time, dimension_id)
SELECT a.company_id, a.assessor_code, a.weight, a.update_by, GETDATE(), @dst_dim
FROM uf_assess_weight_assessor a
WHERE a.dimension_id = @src_dim
  AND NOT EXISTS (SELECT 1 FROM uf_assess_weight_assessor t
                  WHERE t.dimension_id = @dst_dim AND t.company_id = a.company_id AND t.assessor_code = a.assessor_code);

INSERT INTO uf_assess_level_score (company_id, level_no, score, update_by, update_time, dimension_id)
SELECT s.company_id, s.level_no, s.score, s.update_by, GETDATE(), @dst_dim
FROM uf_assess_level_score s
WHERE s.dimension_id = @src_dim
  AND NOT EXISTS (SELECT 1 FROM uf_assess_level_score t
                  WHERE t.dimension_id = @dst_dim AND t.company_id = s.company_id AND t.level_no = s.level_no);

INSERT INTO uf_assess_grade_level (company_id, level_name, lower_bound, upper_bound, sort_order, update_by, update_time, dimension_id)
SELECT g.company_id, g.level_name, g.lower_bound, g.upper_bound, g.sort_order, g.update_by, GETDATE(), @dst_dim
FROM uf_assess_grade_level g
WHERE g.dimension_id = @src_dim
  AND NOT EXISTS (SELECT 1 FROM uf_assess_grade_level t
                  WHERE t.dimension_id = @dst_dim AND t.company_id = g.company_id AND t.level_name = g.level_name);

INSERT INTO uf_assess_period (company_id, [year], status, create_time, update_time, dimension_id)
SELECT p.company_id, p.[year], p.status, GETDATE(), GETDATE(), @dst_dim
FROM uf_assess_period p
WHERE p.dimension_id = @src_dim
  AND NOT EXISTS (SELECT 1 FROM uf_assess_period t
                  WHERE t.dimension_id = @dst_dim AND t.company_id = p.company_id AND t.[year] = p.[year]);
