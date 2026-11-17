-- ============================================================
-- 一次性数据修正：清除 sheet 14（王国栋 2505）上的历史流程评级
-- ------------------------------------------------------------
-- 背景：
--   泛微流程 1840（能力测评申请）的 AssessHookAction 钩子在 2026-11-16 12:30
--   仍向该页面写入了 19 条 source='FLOW' 的领导评级（流程 1431137，评级人 2504）。
--   这些评级不属于网页测评任务（task 9），但 usp_assess_recalc 不看 source，
--   撤回时删掉 WEB 评级后它们会重新算出分数，因此 voidTask 被
--   AssessTaskOpService.assertNoForeignData() 拦下（ERR_FOREIGN_RATING 40204）。
--
--   经确认：该页面为测试数据，流程 1840 后续不再使用，直接清除。
--
-- 影响范围：
--   · 只动 sheet_id = 14 且 source <> 'WEB' 的行（本次实测 19 行，全为 FLOW/LEADER）
--   · 不动 sheet 22（倪斌 3895）的 36 条 FLOW 评级
--   · 该页面无已完成任务、无报告快照（uf_assess_report_snapshot 0 行），删除不影响归档数据
--
-- 回滚：
--   INSERT INTO dbo.uf_assess_sheet_rating (sheet_id, element_id, rater_type, rater_resource_id,
--       client_seq, level_no, score, workflow_request_id, update_time, dimension_id, source, original_score)
--   SELECT sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no, score,
--          workflow_request_id, update_time, dimension_id, source, original_score
--   FROM dbo.uf_assess_sheet_rating_bak_20261118;
--   EXEC dbo.usp_assess_recalc @sheet_id = 14;
-- ============================================================

USE ecology;
GO

-- 1. 备份（可重复执行：表已存在则只补不存在的主键，避免二次运行覆盖已备份内容）
IF OBJECT_ID('dbo.uf_assess_sheet_rating_bak_20261118', 'U') IS NULL
BEGIN
    SELECT * INTO dbo.uf_assess_sheet_rating_bak_20261118
    FROM dbo.uf_assess_sheet_rating WHERE 1 = 0;

    ALTER TABLE dbo.uf_assess_sheet_rating_bak_20261118
        ADD CONSTRAINT PK_uf_assess_sheet_rating_bak_20261118 PRIMARY KEY (id);
END
GO

-- 备份表沿用原表结构，id 是 IDENTITY，写回必须显式打开 IDENTITY_INSERT 并列出列名
SET IDENTITY_INSERT dbo.uf_assess_sheet_rating_bak_20261118 ON;

INSERT INTO dbo.uf_assess_sheet_rating_bak_20261118
    (id, sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no,
     score, workflow_request_id, update_time, dimension_id, source, original_score)
SELECT r.id, r.sheet_id, r.element_id, r.rater_type, r.rater_resource_id, r.client_seq, r.level_no,
       r.score, r.workflow_request_id, r.update_time, r.dimension_id, r.source, r.original_score
FROM dbo.uf_assess_sheet_rating r
WHERE r.sheet_id = 14 AND r.source <> 'WEB'
  AND NOT EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating_bak_20261118 b WHERE b.id = r.id);

SET IDENTITY_INSERT dbo.uf_assess_sheet_rating_bak_20261118 OFF;
GO

-- 2. 备份校验：备份行数必须等于待删行数，不相等就不删
IF (SELECT COUNT(*) FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = 14 AND source <> 'WEB')
   <>
   (SELECT COUNT(*) FROM dbo.uf_assess_sheet_rating_bak_20261118 b
    JOIN dbo.uf_assess_sheet_rating r ON r.id = b.id
    WHERE r.sheet_id = 14 AND r.source <> 'WEB')
BEGIN
    THROW 51000, N'备份行数与待删行数不一致，已中止，未删除任何数据', 1;
END
GO

-- 3. 物理删除
DELETE FROM dbo.uf_assess_sheet_rating
WHERE sheet_id = 14 AND source <> 'WEB';
GO

-- 4. 重算：让页面分数回到真实状态（leader_score 归空、status 回到评价初）
EXEC dbo.usp_assess_recalc @sheet_id = 14;
GO

-- 5. 结果核对
SELECT id, status, standard_score, leader_score, client_score, self_score,
       actual_score, score_rate, grade_name, annual_score
FROM dbo.uf_assess_sheet WHERE id = 14;

SELECT source, rater_type, COUNT(*) AS remain_cnt
FROM dbo.uf_assess_sheet_rating WHERE sheet_id = 14
GROUP BY source, rater_type;
GO
