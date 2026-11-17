-- ============================================================
-- 49 撤回时清空该页所有分数来源（业务确认：都清）
-- ------------------------------------------------------------
-- 背景（2026-11-18 业务确认）：
--   撤回一张测评页后报 ERR_FOREIGN_RATING（40204）：
--   「检测到该页面存在流程评级 / 外部导入评级 N 条……」。
--   第 49 号之前的口径是「先检测、有就拒绝撤回」，把数据不一致推给用户
--   去手工清空外部导入。业务认为这个方向不对：撤回本来就应该回到「评价初」，
--   页面上任何来源的分数都该随撤回一起清掉。
--
-- 一张测评页的分数有 3 个来源（都会进 usp_assess_recalc，它不看 source）：
--   · WEB      —— 网页填写（上级 / 自评 / 客户），本轮之前就会删
--   · FLOW     —— 泛微流程 1840 的 AssessHookAction 钩子写入
--   · EXTERNAL —— 「外部评分导入」通道手工 / agent 导入
--   另外 uf_assess_sheet.annual_score（报告顶部的「年度分」）是单独录入的数字，
--   不参与得分率计算，业务要求一并清空。
--
-- 变更（相对 47）：
--   1) 5a 之后新增 5a2：物理删除该页所有 source <> 'WEB' 的评级
--      （FLOW + EXTERNAL 一起清）。EXTERNAL 被删时补一条
--      uf_assess_external_import_log(action='EXTERNAL_CLEAR')，与
--      usp_assess_external_clear 同口径，外部导入页的「重新导入」仍能取到稿。
--   2) @full_rollback 判定放宽：本任务 WEB 评级 > 0 **或** 页面存在非 WEB 评级
--      → 也算完整回退。否则「只有外部评级、没有网页评级」的页面不会走重置分支，
--      分数会残留在页面上。
--   3) 5b 增加 annual_score = NULL（年度分一并清空）。
--   4) 返回列新增 voidedExternalCount / voidedFlowCount，幂等分支同样返回 0。
--   5) 审计日志 detail_json 增加 external / flow 条数。
--
-- 连带：AssessTaskOpService.assertNoForeignData 已删除，不再拦截撤回。
--
-- 幂等：先备份、再 DROP + CREATE。
-- 回滚：
--   从 dbo.uf_assess_proc_backup 取 proc_name = 'usp_assess_void@49-pre' 的定义
--   重放 CREATE PROCEDURE，或直接重跑 sql/47-add-rater-comment.sql 第 3 节。
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 1. 备份 usp_assess_void 当前定义（47 版）
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
USING (SELECT 'usp_assess_void@49-pre' AS proc_name,
              m.definition
       FROM sys.sql_modules m
       WHERE m.object_id = OBJECT_ID('dbo.usp_assess_void')) AS s
ON t.proc_name = s.proc_name
WHEN MATCHED THEN UPDATE SET t.definition = s.definition, t.backup_time = SYSDATETIME()
WHEN NOT MATCHED THEN INSERT (proc_name, definition) VALUES (s.proc_name, s.definition);
GO

-- ------------------------------------------------------------
-- 2. 重建 usp_assess_void
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
    DECLARE @comment_count INT = 0;          -- 47：本页被一并删除的评价意见条数
    DECLARE @external_count INT = 0;         -- 49：被一并删除的外部导入评级条数
    DECLARE @flow_count INT = 0;             -- 49：被一并删除的流程评级条数
    DECLARE @full_rollback BIT = 0;
    DECLARE @snapshot_deleted BIT = 0;
    DECLARE @std_score DECIMAL(10,4), @old_actual DECIMAL(10,4);
    DECLARE @company_id BIGINT, @dimension_id BIGINT, @resource_id BIGINT, @year INT;

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
               0                 AS voidedExternalCount,
               0                 AS voidedFlowCount,
               0                 AS snapshotDeleted,
               NULL              AS standardScore,
               1                 AS alreadyVoided;
        RETURN;
    END

    -- 3. 冻结现场：评级数、评价人数（审计用，§4.3.4 ①）
    SELECT @rating_count = COUNT(*)
    FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND source = 'WEB' AND workflow_request_id = @task_id;

    -- 49：非网页来源的评级也要清，先数清楚（外部导入 / 流程）
    SELECT @external_count = COUNT(*)
    FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND source = 'EXTERNAL';

    SELECT @flow_count = COUNT(*)
    FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND source = 'FLOW';

    SELECT @rater_count   = COUNT(*),
           @submitted_count = SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END)
    FROM dbo.uf_assess_task_rater
    WHERE task_id = @task_id;

    -- 是否需要完整回退（§4.3.2）：
    --   有本任务网页评级，或有任何非网页评级 → 都要重置页面。
    --   49 放宽：只有外部导入评级、没有网页评级的页面也必须走重置，
    --   否则删了外部评级但页面分数列还留着旧值。
    SET @full_rollback = CASE
        WHEN @rating_count > 0 OR @external_count > 0 OR @flow_count > 0 THEN 1
        ELSE 0
    END;

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
                  N',"external":', @external_count,
                  N',"flow":', @flow_count,
                  N',"raters":', @rater_count,
                  N',"sheetBefore":{"status":0,"actualScore":', ISNULL(CAST(@old_actual AS NVARCHAR(30)), N'null'),
                  N',"standardScore":', ISNULL(CAST(@std_score AS NVARCHAR(30)), N'null'), N'}}'),
           SYSDATETIME(),
           (SELECT dimension_id FROM dbo.uf_assess_task WHERE id = @task_id);

    -- 5. 完整回退
    IF @full_rollback = 1
    BEGIN
        -- 5a. 物理删除本任务评级（§4.3.5：recalc 不看 source，软删会污染分数）
        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND source = 'WEB' AND workflow_request_id = @task_id;

        -- 5a2. 删除该页所有非网页来源评级（49 新增；流程 + 外部导入一起清）
        --      业务确认：撤回要「回到评价初」，外部导入和流程写进来的分不留。
        IF @external_count > 0
        BEGIN
            -- 外部导入页的「重新导入」靠这张日志表取最后成功稿，
            -- 补一条 CLEAR 记录与 usp_assess_external_clear 保持同口径。
            SELECT @company_id = s.company_id, @dimension_id = s.dimension_id, @resource_id = s.resource_id,
                   @year = p.year
            FROM dbo.uf_assess_sheet s
            JOIN dbo.uf_assess_period p ON p.id = s.period_id
            WHERE s.id = @sheet_id;

            INSERT dbo.uf_assess_external_import_log
                (company_id, dimension_id, year, resource_id, sheet_id, action, items_json, operator_resource_id)
            VALUES (@company_id, @dimension_id, @year, @resource_id, @sheet_id, 'EXTERNAL_CLEAR', NULL, @operator_id);
        END

        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND source <> 'WEB';

        -- 5b. 重置页面到评价初（§4.3.1：standard_score 保留）
        --     49：annual_score 一并清空（业务确认「都清」）
        UPDATE dbo.uf_assess_sheet
        SET status         = 0,
            leader_score   = NULL,
            client_score   = NULL,
            self_score     = NULL,
            actual_score   = NULL,
            score_rate     = NULL,
            grade_name     = NULL,
            annual_score   = NULL,
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

    -- 6b. 删除该页的评价意见（47 新增）
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
           @external_count    AS voidedExternalCount,
           @flow_count        AS voidedFlowCount,
           @snapshot_deleted  AS snapshotDeleted,
           @std_score         AS standardScore,
           0                  AS alreadyVoided;
END
GO

-- ------------------------------------------------------------
-- 3. 验证
-- ------------------------------------------------------------
PRINT '--- usp_assess_void 参数（应为 3 个）---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS typ
FROM sys.parameters p WHERE p.object_id = OBJECT_ID('dbo.usp_assess_void')
ORDER BY p.parameter_id;

PRINT '--- 关键片段（应全部 OK）---';
SELECT
    CASE WHEN definition LIKE N'%source <> ''WEB''%'      THEN 'OK' ELSE 'MISSING' END AS delete_foreign,
    CASE WHEN definition LIKE N'%annual_score%NULL%'      THEN 'OK' ELSE 'MISSING' END AS clear_annual,
    CASE WHEN definition LIKE N'%EXTERNAL_CLEAR%'         THEN 'OK' ELSE 'MISSING' END AS log_clear,
    CASE WHEN definition LIKE N'%voidedExternalCount%'    THEN 'OK' ELSE 'MISSING' END AS ret_external,
    CASE WHEN definition LIKE N'%voidedFlowCount%'        THEN 'OK' ELSE 'MISSING' END AS ret_flow
FROM sys.sql_modules WHERE object_id = OBJECT_ID('dbo.usp_assess_void');
GO
