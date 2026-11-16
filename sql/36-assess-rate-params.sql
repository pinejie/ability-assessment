-- ============================================================
-- 36 usp_assess_rate 新增两个参数（评价网页化改造 S1 前置）
-- ------------------------------------------------------------
-- 背景：
--   《15-评价网页化改造方案》要求网页提交「只写评级、不算分」，
--   并让评级带上正确来源以便撤回时精确删除。原过程做不到：
--     · 第 9 步写死 EXEC usp_assess_recalc，每次写入必算分
--     · MERGE 不写 source 列，网页写入会落成默认值 'FLOW'
--
-- 变更：
--   @skip_recalc BIT = 0     —— 1=只写评级不调 recalc（网页提交用）
--   @source      VARCHAR(10) = 'FLOW'  —— 评级来源，写入 MERGE 的 UPDATE/INSERT 两支
--
-- 为什么默认值必须是 0 / 'FLOW'：
--   泛微钩子全部用具名参数调用，不传这两个新参数。
--   默认 0 → IF 0 = 0 成立 → 仍算分，与旧行为完全一致；
--   默认 'FLOW' → 与列的既有默认值一致。
--   若误写成 = NULL：@skip_recalc 为 NULL 时 IF NULL = 0 是 UNKNOWN，
--   算分被静默跳过（评级入库但分数永远为空、不报错）；
--   @source 为 NULL 会因该列 NOT NULL 让 MERGE 直接失败。
--
-- 对泛微钩子的影响：无。钩子已部署的 .class 无需重编译、无需重新部署。
--
-- 依赖：sql/32-category-weight.sql（过程 v8 基线）
-- 回滚：
--   ALTER PROCEDURE 去掉两个参数与 MERGE 中的 source、恢复第 9 步无条件调用即可。
--   本脚本末尾备份了改动前的原始定义到 uf_assess_proc_backup（若表不存在则先建）。
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 0. 备份改动前的原始定义（可重复执行；同名覆盖）
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.uf_assess_proc_backup', 'U') IS NULL
CREATE TABLE dbo.uf_assess_proc_backup (
    proc_name   VARCHAR(128)  NOT NULL,
    definition  NVARCHAR(MAX) NOT NULL,
    backup_time DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_proc_backup PRIMARY KEY (proc_name)
);
GO

IF EXISTS (SELECT 1 FROM sys.sql_modules WHERE object_id = OBJECT_ID('dbo.usp_assess_rate'))
    MERGE dbo.uf_assess_proc_backup AS t
    USING (SELECT 'usp_assess_rate' AS proc_name,
                  m.definition
           FROM sys.sql_modules m
           WHERE m.object_id = OBJECT_ID('dbo.usp_assess_rate')) AS s
    ON t.proc_name = s.proc_name
    WHEN MATCHED THEN UPDATE SET t.definition = s.definition, t.backup_time = SYSDATETIME()
    WHEN NOT MATCHED THEN INSERT (proc_name, definition) VALUES (s.proc_name, s.definition);
GO

-- ------------------------------------------------------------
-- 1. 重建过程（ALTER 不保留旧参数，改用 DROP + CREATE 以显式控制签名）
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.usp_assess_rate', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_rate;
GO
CREATE PROCEDURE dbo.usp_assess_rate
    @company_id          BIGINT,
    @year                INT,
    @resource_id         BIGINT,
    @rater_type          VARCHAR(10),
    @rater_resource_id   BIGINT,
    @workflow_request_id BIGINT,
    @ratings_json        NVARCHAR(MAX),
    @client_ids_json     NVARCHAR(MAX) = NULL,   -- 本次客户名单（流程 cpkh），如 [3785,2381]；NULL 不做客户名单覆盖
    @skip_recalc         BIT           = 0,      -- 1=只写评级不算分（网页提交用）
    @source              VARCHAR(10)   = 'FLOW'  -- 评级来源 FLOW/WEB/EXTERNAL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;

    DECLARE @sheet_id BIGINT, @period_id BIGINT, @period_status INT, @seq INT;

    -- 1. 定位页面并加锁（50101）
    SELECT @sheet_id = s.id, @period_id = s.period_id
    FROM dbo.uf_assess_sheet s WITH (UPDLOCK, ROWLOCK)
    JOIN dbo.uf_assess_period p ON p.id = s.period_id
    WHERE s.company_id = @company_id AND p.year = @year AND s.resource_id = @resource_id;

    IF @sheet_id IS NULL
    BEGIN
        ROLLBACK;
        THROW 50101, N'未找到页面（被测人未配备人员能力要求）', 1;
    END

    -- 2. 年度状态（50105）
    SELECT @period_status = status FROM dbo.uf_assess_period WHERE id = @period_id;
    IF @period_status = 1
    BEGIN
        ROLLBACK;
        THROW 50105, N'年度已关闭', 1;
    END

    -- 3. 评价人身份（50106）
    IF @rater_type NOT IN ('LEADER', 'CLIENT', 'SELF')
       OR (@rater_type = 'SELF' AND @rater_resource_id <> @resource_id)
       OR (@rater_type = 'CLIENT' AND @rater_resource_id = @resource_id)
    BEGIN
        ROLLBACK;
        THROW 50106, N'评价人身份不符', 1;
    END

    -- 4. 要素必须是页面中的有效要素（50102）
    IF EXISTS (
        SELECT 1 FROM OPENJSON(@ratings_json)
             WITH (element_id BIGINT '$.elementId', level_no INT '$.levelNo') j
        WHERE NOT EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_row r
                          WHERE r.sheet_id = @sheet_id AND r.element_id = j.element_id AND r.active = 1))
    BEGIN
        ROLLBACK;
        THROW 50102, N'要素不在页面中', 1;
    END

    -- 5. 评级必须命中页面维度的等级分值配置「对应的评价」（50103）
    --    （原校验"等级必须 1-5"废弃：等级数量动态，以配置为准）
    DECLARE @dimension_id BIGINT;
    SELECT @dimension_id = ISNULL(dimension_id, 1) FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
    IF EXISTS (
        SELECT 1 FROM OPENJSON(@ratings_json)
             WITH (element_id BIGINT '$.elementId', level_no INT '$.levelNo') j
        WHERE j.level_no IS NULL
           OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_level_score ls
                          WHERE ls.company_id = @company_id AND ls.dimension_id = @dimension_id
                            AND ls.rate_value = j.level_no))
    BEGIN
        ROLLBACK;
        THROW 50103, N'评级值不在当前维度等级分值配置（对应的评价）中', 1;
    END

    -- 6. 客户名单覆盖：传入名单时，删除不在本次名单中的历史客户评级。
    --    名单来自流程 cpkh，同批次每个评价人的调用传同一份，重复执行幂等；
    --    传空数组 [] 表示本次未选任何客户，清空该页面全部客户评级。
    IF @client_ids_json IS NOT NULL
        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT'
          AND rater_resource_id NOT IN (SELECT client_id FROM OPENJSON(@client_ids_json)
                                        WITH (client_id BIGINT '$'));

    -- 7. 客户序号：同一客户沿用原序号，新客户取最大序号+1
    IF @rater_type = 'CLIENT'
    BEGIN
        SELECT @seq = MAX(client_seq) FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT' AND rater_resource_id = @rater_resource_id;

        IF @seq IS NULL
            SELECT @seq = ISNULL(MAX(client_seq), 0) + 1 FROM dbo.uf_assess_sheet_rating
            WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT';
    END

    -- 8a. 领导评级：同一要素只保留一条，新领导的评级覆盖旧评级（已确认）
    IF @rater_type = 'LEADER'
        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND rater_type = 'LEADER'
          AND rater_resource_id <> @rater_resource_id
          AND element_id IN (SELECT j.element_id FROM OPENJSON(@ratings_json)
                             WITH (element_id BIGINT '$.elementId') j);

    -- 8b. 按唯一键 upsert 评级（覆盖式）；source 由参数决定，UPDATE 支同步覆盖
    MERGE dbo.uf_assess_sheet_rating AS t
    USING (
        SELECT j.element_id, j.level_no
        FROM OPENJSON(@ratings_json) WITH (element_id BIGINT '$.elementId', level_no INT '$.levelNo') j
    ) AS s
    ON  t.sheet_id = @sheet_id AND t.element_id = s.element_id
    AND t.rater_type = @rater_type AND t.rater_resource_id = @rater_resource_id
    WHEN MATCHED THEN
        UPDATE SET t.level_no = s.level_no,
                   t.workflow_request_id = @workflow_request_id,
                   t.source = @source,
                   t.update_time = SYSDATETIME()
    WHEN NOT MATCHED THEN
        INSERT (sheet_id, element_id, rater_type, rater_resource_id, client_seq,
                level_no, workflow_request_id, source, update_time)
        VALUES (@sheet_id, s.element_id, @rater_type, @rater_resource_id,
                CASE WHEN @rater_type = 'CLIENT' THEN @seq END, s.level_no,
                @workflow_request_id, @source, SYSDATETIME());

    -- 9. 计算得分（同一事务）；网页单人提交时传 @skip_recalc = 1 跳过
    IF @skip_recalc = 0
        EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    -- 10. 日志（记录本次客户名单与来源，便于追溯覆盖情况）
    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'RATE', @rater_resource_id,
            CONCAT(N'{"raterType":"', @rater_type, N'","workflowRequestId":', @workflow_request_id,
                   N',"source":"', @source, N'",',
                   N'"count":', (SELECT COUNT(*) FROM OPENJSON(@ratings_json)),
                   CASE WHEN @client_ids_json IS NOT NULL
                        THEN CONCAT(N',"clientList":', @client_ids_json) ELSE N'' END,
                   N'}'), SYSDATETIME());

    COMMIT;

    -- 11. 返回页面当前状态
    SELECT id AS sheetId, status, need_recalc, client_count, standard_score, leader_score,
           client_score, self_score, actual_score, score_rate, grade_name
    FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
END
GO

-- ------------------------------------------------------------
-- 2. 验证
-- ------------------------------------------------------------
PRINT '--- 参数清单 ---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS type
FROM sys.parameters p
WHERE p.object_id = OBJECT_ID('dbo.usp_assess_rate')
ORDER BY p.parameter_id;

PRINT '--- 关键片段 ---';
SELECT
    CASE WHEN definition LIKE '%IF @skip_recalc = 0%' THEN 'OK' ELSE 'MISSING' END AS skip_guard,
    CASE WHEN definition LIKE '%t.source = @source%'  THEN 'OK' ELSE 'MISSING' END AS merge_update_source,
    CASE WHEN definition LIKE '%workflow_request_id, source, update_time%' THEN 'OK' ELSE 'MISSING' END AS merge_insert_source
FROM sys.sql_modules
WHERE object_id = OBJECT_ID('dbo.usp_assess_rate');
GO
