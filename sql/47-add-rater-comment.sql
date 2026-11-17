-- ============================================================
-- 47 评价意见（网页填写通道）
-- ------------------------------------------------------------
-- 背景（2026-11-17 业务需求）：
--   「直接上级评价」「客户评价」两个填写页原本只有评级、没有文字评语，
--   个人测评报告的「评价意见」区只能靠泛微流程写入 uf_assess_comment。
--   现在要求评价人在网页上直接写评语，算分后带进报告的上级评价 / 客户评价两栏。
--
-- 设计决策：
--   1) 评语存 uf_assess_task_rater.comment，**不写 uf_assess_comment**。
--      理由：uf_assess_comment 的唯一键是 (sheet_id, comment_type)，每种类型只允许一行，
--      多个客户必然互相覆盖 —— 而需求要求「每个客户一行、拼接展示」。
--      存到评价人行上，一人一条，天然不冲突，也天然随「提交 / 撤回」的既有状态机走。
--   2) 报告读取时现拼：上级取 LEADER 行的 comment，客户按提交顺序把每个人的
--      comment 拼成多行「客户张三：……」。撤回的行 status=3，读取时过滤掉，
--      评语自动消失，不需要额外清理逻辑。
--   3) 泛微钩子通道保持不动：网页没产出评语时，报告仍回退读 uf_assess_comment。
--
-- 撤回变更（业务确认：允许撤回；选 A「一并删除评价意见」）：
--   原先 AssessTaskOpService 在页面存在 uf_assess_comment 行时**拒绝撤回**，
--   理由是撤回后评语仍留在报告里、与「回到评价初」矛盾。
--   现在改为：撤回时一并删除该页的 uf_assess_comment 行，闸门随之移除。
--   故本脚本同时重建 usp_assess_void（第 6b 步新增 DELETE uf_assess_comment）。
--   其余步骤与 sql/42 一字节不改。
--
-- 幂等：加列前判 COL_LENGTH；过程先 DROP 再 CREATE。
-- 回滚：
--   · 列：ALTER TABLE dbo.uf_assess_task_rater DROP COLUMN comment;（会丢已写评语）
--   · 过程：从 uf_assess_proc_backup 取 proc_name = 'usp_assess_void@47-pre' 的定义重放
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 1. 评价人评价意见列
-- ------------------------------------------------------------
IF COL_LENGTH('dbo.uf_assess_task_rater', 'comment') IS NULL
    ALTER TABLE dbo.uf_assess_task_rater ADD comment NVARCHAR(1000) NULL;
GO

PRINT '--- uf_assess_task_rater.comment ---';
SELECT c.column_id, c.name, TYPE_NAME(c.user_type_id) AS type, c.max_length, c.is_nullable
FROM sys.columns c WHERE c.object_id = OBJECT_ID('dbo.uf_assess_task_rater') ORDER BY c.column_id;
GO

-- ------------------------------------------------------------
-- 2. 备份 usp_assess_void 当前定义
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.uf_assess_proc_backup', 'U') IS NULL
CREATE TABLE dbo.uf_assess_proc_backup (
    proc_name   VARCHAR(128)  NOT NULL,
    definition  NVARCHAR(MAX) NOT NULL,
    backup_time DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_proc_backup PRIMARY KEY (proc_name)
);
GO

MERGE dbo.uf_assess_proc_backup AS t
USING (SELECT 'usp_assess_void@47-pre' AS proc_name,
              m.definition
       FROM sys.sql_modules m
       WHERE m.object_id = OBJECT_ID('dbo.usp_assess_void')) AS s
ON t.proc_name = s.proc_name
WHEN MATCHED THEN UPDATE SET t.definition = s.definition, t.backup_time = SYSDATETIME()
WHEN NOT MATCHED THEN INSERT (proc_name, definition) VALUES (s.proc_name, s.definition);
GO

-- ------------------------------------------------------------
-- 3. 重建 usp_assess_void：新增第 6b 步「删除该页评价意见」
-- ------------------------------------------------------------
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
    DECLARE @comment_count INT = 0;          -- 47 新增：本页被一并删除的评价意见条数
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
               0                 AS voidedCommentCount,
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

    -- 6b. 删除该页的评价意见（2026-11-17 新增）
    --     业务确认：撤回要「回到评价初」，评语不能留着继续出现在个人报告里。
    --     删的是整页意见而非仅本任务的：uf_assess_comment 没有 task_id，
    --     且其唯一键是 (sheet_id, comment_type)，本来就只有「当前这一份」。
    IF OBJECT_ID('dbo.uf_assess_comment', 'U') IS NOT NULL
    BEGIN
        SELECT @comment_count = COUNT(*) FROM dbo.uf_assess_comment WHERE sheet_id = @sheet_id;
        DELETE FROM dbo.uf_assess_comment WHERE sheet_id = @sheet_id;
    END

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
           @comment_count     AS voidedCommentCount,
           @snapshot_deleted  AS snapshotDeleted,
           @std_score         AS standardScore,
           0                  AS alreadyVoided;
END
GO

-- ------------------------------------------------------------
-- 4. 验证
-- ------------------------------------------------------------
PRINT '--- usp_assess_void 参数（应为 3 个）---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS typ
FROM sys.parameters p WHERE p.object_id = OBJECT_ID('dbo.usp_assess_void')
ORDER BY p.parameter_id;

PRINT '--- 关键片段 ---';
SELECT
    CASE WHEN definition LIKE N'%DELETE FROM dbo.uf_assess_comment%' THEN 'OK' ELSE 'MISSING' END AS delete_comment,
    CASE WHEN definition LIKE N'%voidedCommentCount%'                     THEN 'OK' ELSE 'MISSING' END AS return_column
FROM sys.sql_modules WHERE object_id = OBJECT_ID('dbo.usp_assess_void');
GO
