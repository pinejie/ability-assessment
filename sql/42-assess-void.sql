-- ============================================================
-- 42 新建存储过程：usp_assess_void（撤回任务 → 回到「评价初」）
-- ------------------------------------------------------------
-- 依据：《15-评价网页化改造方案》§4.3 撤回、§4.3.4 执行步骤、§3.2 决策 Q10
--
-- 为什么封装成存储过程（Q10 决策=方案 B）：
--   本项目既定架构是「业务表只由存储过程写」（见 ExternalScoreService.java:32、
--   AssessSheetSyncService.java:20）。撤回要一次改 6 张表、任何一步漏掉都会导致
--   状态不一致，正是存储过程擅长的场景；且六步在同一 BEGIN TRAN 内，
--   原子性不依赖 Java 事务传播行为。
--
-- 为什么不用 usp_assess_recalc 做重置（§4.3.4 已论证）：
--   recalc 有四处 THROW 50104（权重缺失/标准分为 0/无有效要素）。若配置期间被改动，
--   recalc 抛错会连带回滚整个撤回事务 —— 用户看到「撤回失败」。所以这里显式重置，
--   结果确定、不依赖存储过程，代价是重置语句要自己写一遍。
--
-- 关键：standard_score 必须保留（§4.3.1）
--   它由岗位能力要求与等级分值配置算定，不是评价结果。若一并清零，
--   汇总页会显示「标准分为空」，看起来像配置缺失而非撤回。
--
-- 返回值：撤回前后的现场快照（供接口组装响应）
-- 回滚：DROP PROCEDURE dbo.usp_assess_void;
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.usp_assess_void', 'P') IS NOT NULL
    DROP PROCEDURE dbo.usp_assess_void;
GO

CREATE PROCEDURE dbo.usp_assess_void
    @task_id       BIGINT,
    @operator_id   BIGINT       = NULL,
    @reason        NVARCHAR(400) = NULL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;

    DECLARE @sheet_id BIGINT, @task_status TINYINT, @period_id BIGINT;
    DECLARE @rating_count INT = 0, @rater_count INT = 0;
    DECLARE @submitted_count INT = 0;
    DECLARE @full_rollback BIT = 0;
    DECLARE @snapshot_deleted BIT = 0;
    DECLARE @std_score DECIMAL(10,4), @old_actual DECIMAL(10,4);

    -- 1. 定位任务并加锁（串行化同一任务的并发撤回）
    SELECT @sheet_id = sheet_id, @task_status = status, @period_id = period_id
    FROM dbo.uf_assess_task WITH (UPDLOCK, ROWLOCK)
    WHERE id = @task_id;

    IF @sheet_id IS NULL
    BEGIN
        ROLLBACK;
        THROW 50211, N'任务不存在或未绑定页面', 1;
    END

    -- 2. 已撤回/已取消：幂等返回，不重复执行（避免二次删除与二次通知）
    --    返回列必须与下方主路径完全一致，否则 Java 侧 Map 取不到值会静默变 0
    IF @task_status IN (3, 4)
    BEGIN
        ROLLBACK;
        SELECT @task_id          AS taskId,
               @sheet_id         AS sheetId,
               0                 AS fullRollback,
               0                 AS voidedRatingCount,
               0                 AS voidedRaterCount,
               0                 AS submittedRaterCount,
               0                 AS snapshotDeleted,
               NULL              AS standardScore,
               1                 AS alreadyVoided;
        RETURN;
    END

    -- 3. 冻结现场：评级数、评价人数（审计用，§4.3.4 ①）
    SELECT @rating_count = COUNT(*)
    FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND source = 'WEB' AND workflow_request_id = @task_id;

    SELECT @rater_count   = COUNT(*),
           @submitted_count = SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END)
    FROM dbo.uf_assess_task_rater
    WHERE task_id = @task_id;

    -- 是否需要完整回退（§4.3.2）：有本任务评级 → 清数据；没有 → 只作废任务
    SET @full_rollback = CASE WHEN @rating_count > 0 THEN 1 ELSE 0 END;

    SELECT @std_score = standard_score, @old_actual = actual_score
    FROM dbo.uf_assess_sheet WHERE id = @sheet_id;

    -- 4. 写审计日志（先写，保证即使后续失败也有痕迹；fail 则整体回滚）
    INSERT dbo.uf_assess_sheet_log
        (sheet_id, action, operator_resource_id, detail_json, create_time,
         dimension_id)
    SELECT @sheet_id, 'VOID', @operator_id,
           CONCAT(N'{"taskId":', @task_id,
                  N',"reason":', ISNULL(N'"' + STRING_ESCAPE(LEFT(@reason, 400), 'json') + N'"', N'null'),
                  N',"voidBy":', ISNULL(CAST(@operator_id AS NVARCHAR(20)), N'null'),
                  N',"fullRollback":', @full_rollback,
                  N',"ratings":', @rating_count,
                  N',"raters":', @rater_count,
                  N',"sheetBefore":{"status":0,"actualScore":', ISNULL(CAST(@old_actual AS NVARCHAR(30)), N'null'),
                  N',"standardScore":', ISNULL(CAST(@std_score AS NVARCHAR(30)), N'null'), N'}}'),
           SYSDATETIME(),
           (SELECT dimension_id FROM dbo.uf_assess_task WHERE id = @task_id);

    -- 5. 完整回退（仅当有本任务评级时）
    IF @full_rollback = 1
    BEGIN
        -- 5a. 物理删除本任务评级（§4.3.5：recalc 不看 source，软删会污染分数）
        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND source = 'WEB' AND workflow_request_id = @task_id;

        -- 5b. 重置页面到评价初（§4.3.1：standard_score 保留）
        UPDATE dbo.uf_assess_sheet
        SET status         = 0,
            leader_score   = NULL,
            client_score   = NULL,
            self_score     = NULL,
            actual_score   = NULL,
            score_rate     = NULL,
            grade_name     = NULL,
            client_count   = 0,
            need_recalc    = 0,
            update_time    = SYSDATETIME()
        WHERE id = @sheet_id;

        -- 5c. 重置页面行上的冗余评级列（category_weight/element_weight/standard_score 保留）
        UPDATE dbo.uf_assess_sheet_row
        SET leader_level_no = NULL,
            leader_score    = NULL,
            client_avg_score = NULL,
            self_level_no   = NULL,
            self_score      = NULL
        WHERE sheet_id = @sheet_id;

        -- 5d. 删除报告快照（否则个人报告仍读冻结的旧分数）
        IF OBJECT_ID('dbo.uf_assess_report_snapshot', 'U') IS NOT NULL
        BEGIN
            DELETE FROM dbo.uf_assess_report_snapshot WHERE sheet_id = @sheet_id;
            SET @snapshot_deleted = 1;
        END
    END

    -- 6. 作废评价人与草稿（旧链接立即失效；draft_json 清空避免残留）
    UPDATE dbo.uf_assess_task_rater
    SET status = 3, draft_json = NULL, draft_time = NULL
    WHERE task_id = @task_id AND status IN (0, 1);

    -- 7. 标记任务（replaced_by_task_id 留给「重新发起」时回填）
    UPDATE dbo.uf_assess_task
    SET status      = 4,
        void_by     = @operator_id,
        void_time   = SYSDATETIME(),
        void_reason = LEFT(@reason, 400),
        update_time = SYSDATETIME()
    WHERE id = @task_id;

    COMMIT;

    -- 8. 返回现场（TaskService 据此组装响应与撤回通知）
    SELECT @task_id           AS taskId,
           @sheet_id          AS sheetId,
           @full_rollback     AS fullRollback,
           @rating_count      AS voidedRatingCount,
           @rater_count       AS voidedRaterCount,
           @submitted_count   AS submittedRaterCount,
           @snapshot_deleted  AS snapshotDeleted,
           @std_score         AS standardScore,
           0                  AS alreadyVoided;
END
GO

PRINT '--- usp_assess_void 参数 ---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS typ
FROM sys.parameters p WHERE p.object_id = OBJECT_ID('dbo.usp_assess_void')
ORDER BY p.parameter_id;
GO
