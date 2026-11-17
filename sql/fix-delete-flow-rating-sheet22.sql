-- ============================================================
-- 一次性数据修正：清除 sheet 22（倪斌 3895）上的历史流程评级
-- ------------------------------------------------------------
-- 背景：与 sql/fix-delete-flow-rating-sheet14.sql 同一根因。
--   泛微流程 1840（能力测评申请）的 AssessHookAction 钩子
--   于 2026-11-16 12:29 向该页面写入 36 条 source='FLOW' 评级
--   （流程 1431138：LEADER 18 条 + SELF 18 条）。
--   这些评级不属于网页测评任务，但 usp_assess_recalc 不看 source，
--   该页面日后发起测评任务并撤回时，会被
--   AssessTaskOpService.assertNoForeignData() 拦下（40204）。
--
--   经确认：该页面为测试数据，流程 1840 后续不再使用，直接清除。
--
-- 影响范围：
--   · 只动 sheet_id = 22 且 source <> 'WEB' 的行（本次实测 36 行，全为 FLOW）
--   · 该页面无任务、无报告快照、无评价意见、无外部导入日志（均已实测 0 行）
--
-- 回滚：
--   SET IDENTITY_INSERT dbo.uf_assess_sheet_rating ON;
--   INSERT INTO dbo.uf_assess_sheet_rating
--       (id, sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no,
--        score, workflow_request_id, update_time, dimension_id, source, original_score)
--   SELECT id, sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no,
--          score, workflow_request_id, update_time, dimension_id, source, original_score
--   FROM dbo.uf_assess_sheet_rating_bak_20261118_s22;
--   SET IDENTITY_INSERT dbo.uf_assess_sheet_rating OFF;
--   EXEC dbo.usp_assess_recalc @sheet_id = 22;
-- ============================================================

USE ecology;
GO

-- 1. 备份（可重复执行：表已存在则只补不存在的 id，二次运行不覆盖已备份内容）
IF OBJECT_ID('dbo.uf_assess_sheet_rating_bak_20261118_s22', 'U') IS NULL
BEGIN
    SELECT * INTO dbo.uf_assess_sheet_rating_bak_20261118_s22
    FROM dbo.uf_assess_sheet_rating WHERE 1 = 0;

    ALTER TABLE dbo.uf_assess_sheet_rating_bak_20261118_s22
        ADD CONSTRAINT PK_uf_assess_sheet_rating_bak_20261118_s22 PRIMARY KEY (id);
END
GO

-- 原表 id 是 IDENTITY，写回备份表须显式打开 IDENTITY_INSERT 并列出列名
SET IDENTITY_INSERT dbo.uf_assess_sheet_rating_bak_20261118_s22 ON;

INSERT INTO dbo.uf_assess_sheet_rating_bak_20261118_s22
    (id, sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no,
     score, workflow_request_id, update_time, dimension_id, source, original_score)
SELECT r.id, r.sheet_id, r.element_id, r.rater_type, r.rater_resource_id, r.client_seq, r.level_no,
       r.score, r.workflow_request_id, r.update_time, r.dimension_id, r.source, r.original_score
FROM dbo.uf_assess_sheet_rating r
WHERE r.sheet_id = 22 AND r.source <> 'WEB'
  AND NOT EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating_bak_20261118_s22 b WHERE b.id = r.id);

SET IDENTITY_INSERT dbo.uf_assess_sheet_rating_bak_20261118_s22 OFF;
GO

-- 2. 备份校验：备份行数必须等于待删行数，不相等就不删
IF (SELECT COUNT(*) FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = 22 AND source <> 'WEB')
   <>
   (SELECT COUNT(*) FROM dbo.uf_assess_sheet_rating_bak_20261118_s22 b
    JOIN dbo.uf_assess_sheet_rating r ON r.id = b.id
    WHERE r.sheet_id = 22 AND r.source <> 'WEB')
BEGIN
    THROW 51001, N'备份行数与待删行数不一致，已中止，未删除任何数据', 1;
END
GO

-- 3. 物理删除
DELETE FROM dbo.uf_assess_sheet_rating
WHERE sheet_id = 22 AND source <> 'WEB';
GO

-- 4. 重算：让页面分数回到真实状态
EXEC dbo.usp_assess_recalc @sheet_id = 22;
GO

-- 5. 结果核对
SELECT id, status, standard_score, leader_score, client_score, self_score,
       actual_score, score_rate, grade_name, annual_score
FROM dbo.uf_assess_sheet WHERE id = 22;

SELECT source, rater_type, COUNT(*) AS remain_cnt
FROM dbo.uf_assess_sheet_rating WHERE sheet_id = 22
GROUP BY source, rater_type;
GO
