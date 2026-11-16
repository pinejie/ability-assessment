-- ============================================================
-- 45 usp_assess_rate 评级校验收紧到「要素级」（评价网页化改造 §6.3.1 收尾）
-- ------------------------------------------------------------
-- 背景：
--   方案 §6.3.1 已把填页与网页提交改为「档位按要素配置」（uf_ability_element_level），
--   但本过程第 5 步仍只校验「评价号命中**维度**的 uf_assess_level_score.rate_value」。
--   维度 3 配了 5 档，而「司机专职能力」(element 113) 只有 3 档 ——
--   绕过 Web 前端的通道（流程钩子 AssessHookAction / 其它直调）仍能给它打 4、5 档
--   （7 分 / 10 分），等于按业务上不存在的档位给高分。
--
-- 变更（仅第 5 步，其余与 sql/36 一字节不改）：
--   5a. 保留原维度级校验（评级值必须能换算出分值，否则算分时无所适从）
--   5b. 新增要素级校验：要素在 uf_ability_element_level 配过档位时，
--       评级值必须等于该要素某一档经 level_no → rate_value 折算出的评价号
--
-- 为什么要素一行都没配时不拦（退回 5a 的维度口径）：
--   等级说明是导入/人工配置的产物，漏配属于配置缺口，不是越权评级。
--   此时填页会显示「该要素尚未配置等级，暂时无法评价」，网页侧本就进不来；
--   若这里也拦，流程钩子会因一条漏配要素整批写入失败，把矛盾放大到整条评价通道。
--   当前库内不存在这种要素（维度 3 = 46 个、维度 4 = 1 个，全部有等级行），
--   该分支只作防御。
--
-- 存量数据核查（2026-11-13，执行前）：
--   要素+评价号在「要素自己档位」里找不到的已有评级 = 0 行 → 收紧不会拒绝任何历史数据。
--
-- 依赖：sql/36-assess-rate-params.sql（过程当前基线，@skip_recalc / @source 已存在）
-- 回滚：见脚本末尾，从 uf_assess_proc_backup 取 proc_name = 'usp_assess_rate@45-pre' 的定义重放。
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 0. 备份当前定义（用带版本的名字，不覆盖 36 留下的 usp_assess_rate 备份）
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
USING (SELECT 'usp_assess_rate@45-pre' AS proc_name,
              m.definition
       FROM sys.sql_modules m
       WHERE m.object_id = OBJECT_ID('dbo.usp_assess_rate')) AS s
ON t.proc_name = s.proc_name
WHEN MATCHED THEN UPDATE SET t.definition = s.definition, t.backup_time = SYSDATETIME()
WHEN NOT MATCHED THEN INSERT (proc_name, definition) VALUES (s.proc_name, s.definition);
GO

-- ------------------------------------------------------------
-- 1. 重建过程
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

    -- 5a. 评级值必须能命中页面维度的等级分值配置「对应的评价」（50103）——
    --     这是换算链的底线：命中不了就换不出分值，算分时无处取值。
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

    -- 5b. 收紧到要素级（2026-11-13，sql/45）：要素自己配了几档，就只能打这几档。
    --     与填页渲染、AssessFillService.submit() 口径一致（方案 §6.3.1）。
    --     要素一行档位都没配时本条不成立，退回 5a 的维度口径（见文件头「为什么要素一行都没配时不拦」）。
    IF EXISTS (
        SELECT 1 FROM OPENJSON(@ratings_json)
             WITH (element_id BIGINT '$.elementId', level_no INT '$.levelNo') j
        WHERE EXISTS (SELECT 1 FROM dbo.uf_ability_element_level l WHERE l.element_id = j.element_id)
          AND NOT EXISTS (
                SELECT 1
                FROM dbo.uf_ability_element_level l
                JOIN dbo.uf_assess_level_score ls
                     ON ls.company_id = @company_id
                    AND ls.dimension_id = @dimension_id
                    AND ls.level_no = l.level
                WHERE l.element_id = j.element_id
                  AND ls.rate_value = j.level_no))
    BEGIN
        ROLLBACK;
        THROW 50103, N'评级值不是该要素已配置的档位', 1;
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
PRINT '--- 参数清单（应为 10 个，含 skip_recalc / source）---';
SELECT p.parameter_id, p.name, TYPE_NAME(p.user_type_id) AS type
FROM sys.parameters p
WHERE p.object_id = OBJECT_ID('dbo.usp_assess_rate')
ORDER BY p.parameter_id;

PRINT '--- 关键片段 ---';
SELECT
    CASE WHEN definition LIKE '%IF @skip_recalc = 0%' THEN 'OK' ELSE 'MISSING' END AS skip_guard,
    CASE WHEN definition LIKE '%t.source = @source%'  THEN 'OK' ELSE 'MISSING' END AS merge_update_source,
    CASE WHEN definition LIKE N'%评级值不是该要素已配置的档位%' THEN 'OK' ELSE 'MISSING' END AS element_level_gate
FROM sys.sql_modules
WHERE object_id = OBJECT_ID('dbo.usp_assess_rate');
GO

-- 回滚（手工执行）：
--   DECLARE @d NVARCHAR(MAX) = (SELECT definition FROM dbo.uf_assess_proc_backup
--                               WHERE proc_name = 'usp_assess_rate@45-pre');
--   EXEC sp_executesql @d;   -- 定义以 CREATE 开头，需先 DROP PROCEDURE dbo.usp_assess_rate
