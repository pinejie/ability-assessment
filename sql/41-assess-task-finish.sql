-- ============================================================
-- 41 新建存储过程：usp_assess_task_finish（全员齐备 → 算分 + 任务完成）
-- ------------------------------------------------------------
-- 依据：《15-评价网页化改造方案》§3.2 决策 2、§7.2 校验第 7 条、§9.1
--
-- 为什么需要这个过程：
--   usp_assess_rate 加了 @skip_recalc=1 后只写评级不算分。算分时机被推迟到
--   「本任务全部评价人提交完毕」这一刻，需要有一个地方：
--     ① 再确认一次 pending = 0（防并发：两个评价人几乎同时提交，
--        两边都以为自己不是最后一个，或都以为自己是）；
--     ② 调 usp_assess_recalc；
--     ③ 把任务状态推到 2（已完成）。
--   三步必须在同一事务内，否则会出现「分数算了任务没完成」或反之。
--
-- 为什么闸门用 status = 0 而不是 <> 1：
--   撤回会把评价人置为 3（已作废）。用 <> 1 会把作废者算成未提交，
--   pending 永不为 0，对已撤回任务调本过程永远报「还有 N 人未提交」——
--   而这些人其实早已作废，提示完全误导。只数 0 对两种情况都正确。
--
-- 与 usp_assess_rate 的关系：
--   本过程内 EXEC usp_assess_recalc，与 usp_assess_rate 内部第 9 步同样的
--   嵌套事务写法（内层 COMMIT 只递减 @@TRANCOUNT），沿用既有已验证模式。
--
-- 返回值：任务 + 页面终态（供接口直接回给前端）
-- 回滚：DROP PROCEDURE dbo.usp_assess_task_finish;
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.usp_assess_task_finish', 'P') IS NOT NULL
    DROP PROCEDURE dbo.usp_assess_task_finish;
GO

CREATE PROCEDURE dbo.usp_assess_task_finish
    @task_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;

    DECLARE @sheet_id BIGINT, @status TINYINT, @pending INT;

    -- 1. 定位任务并加锁（串行化「最后一个提交」的判定）
    SELECT @sheet_id = sheet_id, @status = status
    FROM dbo.uf_assess_task WITH (UPDLOCK, ROWLOCK)
    WHERE id = @task_id;

    IF @sheet_id IS NULL
    BEGIN
        ROLLBACK;
        THROW 50111, N'任务不存在或未绑定页面', 1;
    END

    -- 2. 状态必须是进行中（50112）
    --    已完成（2）重复调用直接拒绝：重算请走 /retry 接口，不要借本过程。
    --    已撤回（4）/已取消（3）也拒绝 —— 此时评级已被删除或停止收集，算分无意义。
    IF @status <> 0
    BEGIN
        ROLLBACK;
        THROW 50112, N'任务不在进行中，不能触发算分', 1;
    END

    -- 3. 闸门：仍有待评则不进入算分（50113）
    SELECT @pending = COUNT(*) FROM dbo.uf_assess_task_rater
    WHERE task_id = @task_id AND status = 0;

    IF @pending > 0
    BEGIN
        ROLLBACK;
        THROW 50113, N'仍有评价人未提交，暂不计算', 1;
    END

    -- 4. 算分（recalc 内部自带事务，与本事务嵌套）
    EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    -- 5. 任务置完成
    UPDATE dbo.uf_assess_task
    SET status = 2, update_time = SYSDATETIME()
    WHERE id = @task_id;

    COMMIT;

    -- 6. 返回终态
    SELECT t.id            AS taskId,
           t.status        AS taskStatus,
           s.id            AS sheetId,
           s.status        AS sheetStatus,
           s.need_recalc,
           s.client_count,
           s.standard_score,
           s.leader_score,
           s.client_score,
           s.self_score,
           s.actual_score,
           s.score_rate,
           s.grade_name
    FROM dbo.uf_assess_task t
    JOIN dbo.uf_assess_sheet s ON s.id = t.sheet_id
    WHERE t.id = @task_id;
END
GO

PRINT '--- usp_assess_task_finish 参数 ---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS typ
FROM sys.parameters p WHERE p.object_id = OBJECT_ID('dbo.usp_assess_task_finish')
ORDER BY p.parameter_id;
GO
