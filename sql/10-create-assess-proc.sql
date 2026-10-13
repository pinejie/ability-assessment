-- 10 测评计算与写入存储过程（v2）
-- 数据库：SQL Server 2016 RTM。使用 IF OBJECT_ID + DROP + CREATE（2016 RTM 不支持 CREATE OR ALTER）。
-- 配置按 company_id 读取（uf_assess_weight_ability / uf_assess_weight_assessor / uf_assess_level_score / uf_assess_grade_level）。
-- usp_assess_rate  ：泛微钩子唯一写入入口（写评级 + 调用计算）
-- usp_assess_recalc：计算得分，只由 usp_assess_rate 调用

IF OBJECT_ID('dbo.usp_assess_recalc', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_recalc;
GO
CREATE PROCEDURE dbo.usp_assess_recalc
    @sheet_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @company_id BIGINT, @resource_id BIGINT;
    DECLARE @w_leader DECIMAL(10,4), @w_client DECIMAL(10,4), @w_self DECIMAL(10,4);
    DECLARE @n INT, @standard DECIMAL(10,4), @client_n INT;
    DECLARE @leader DECIMAL(10,4), @client DECIMAL(10,4), @self DECIMAL(10,4);
    DECLARE @actual DECIMAL(10,4), @rate DECIMAL(10,4), @grade NVARCHAR(20), @status INT;

    SELECT @company_id = s.company_id, @resource_id = s.resource_id
    FROM dbo.uf_assess_sheet s WHERE s.id = @sheet_id;

    -- 1. 配置完整性（50104）
    SELECT @w_leader = MAX(CASE WHEN assessor_code = 'LEADER' THEN weight END),
           @w_client = MAX(CASE WHEN assessor_code = 'CLIENT' THEN weight END),
           @w_self   = MAX(CASE WHEN assessor_code = 'SELF'   THEN weight END)
    FROM dbo.uf_assess_weight_assessor WHERE company_id = @company_id;

    IF @w_leader IS NULL OR @w_client IS NULL OR @w_self IS NULL
       OR @w_leader + @w_client + @w_self <> 100
       OR (SELECT COUNT(DISTINCT level_no) FROM dbo.uf_assess_level_score
           WHERE company_id = @company_id AND level_no BETWEEN 1 AND 5) <> 5
       OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_grade_level WHERE company_id = @company_id)
        THROW 50104, N'配置不完整（评分权重、等级分值或等级标准缺失）', 1;

    -- 2. 有效要素与类别要素数
    SELECT r.id AS row_id, r.element_id, r.category_id, r.required_level_no,
           cw.weight AS cat_weight,
           COUNT(*) OVER (PARTITION BY r.category_id) AS n_c
    INTO #act
    FROM dbo.uf_assess_sheet_row r
    LEFT JOIN dbo.uf_assess_weight_ability cw
           ON cw.company_id = @company_id AND cw.category_id = r.category_id
    WHERE r.sheet_id = @sheet_id AND r.active = 1;

    IF EXISTS (SELECT 1 FROM #act WHERE cat_weight IS NULL OR required_level_no IS NULL)
        THROW 50104, N'配置不完整（类别权重或要求等级缺失）', 1;

    SELECT @n = COUNT(*) FROM #act;
    IF @n = 0 THROW 50104, N'页面没有有效要素', 1;

    -- 5a. 标准分 S(e)
    UPDATE r
    SET r.standard_score  = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4),
        r.category_weight = a.cat_weight
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.level_no = a.required_level_no;

    -- 5b. 评级得分 G(e, 评价人)
    UPDATE rt
    SET rt.score = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4)
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.level_no = rt.level_no
    WHERE rt.sheet_id = @sheet_id;

    -- 6. 有效评级（有效要素上的全部评级；领导评级每要素只有一条）
    SELECT rt.*
    INTO #cur
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    WHERE rt.sheet_id = @sheet_id;

    -- 7. 各角色得分（完整性：评全 n 个要素，否则为空）
    SELECT @standard = SUM(r.standard_score)
    FROM dbo.uf_assess_sheet_row r JOIN #act a ON a.row_id = r.id;

    IF @standard IS NULL OR @standard <= 0
        THROW 50104, N'岗位标准分为 0，无法计算得分率', 1;

    SELECT @leader = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM #cur WHERE rater_type = 'LEADER';

    SELECT @self = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM #cur WHERE rater_type = 'SELF' AND rater_resource_id = @resource_id;

    ;WITH c AS (
        SELECT rater_resource_id, COUNT(*) AS cnt, SUM(score) AS total
        FROM #cur WHERE rater_type = 'CLIENT'
        GROUP BY rater_resource_id
    )
    SELECT @client_n = COUNT(*),
           @client   = CASE WHEN COUNT(*) > 0 AND MIN(cnt) = @n
                            THEN SUM(total) / COUNT(*) END
    FROM c;

    -- 8. 实际得分、得分率、综合评定
    IF @leader IS NOT NULL AND @client IS NOT NULL AND @self IS NOT NULL
        SET @actual = @leader * @w_leader / 100 + @client * @w_client / 100 + @self * @w_self / 100;

    IF @actual IS NOT NULL
    BEGIN
        SET @rate = @actual / @standard * 100;

        SELECT TOP (1) @grade = g.level_name
        FROM dbo.uf_assess_grade_level g
        WHERE g.company_id = @company_id
          AND (g.lower_bound IS NULL OR ROUND(@rate, 2) > g.lower_bound)
          AND (g.upper_bound IS NULL OR ROUND(@rate, 2) <= g.upper_bound)
        ORDER BY g.sort_order;
    END

    SET @status = CASE WHEN @actual IS NOT NULL THEN 2
                       WHEN EXISTS (SELECT 1 FROM #cur) THEN 1
                       ELSE 0 END;

    -- 9. 页面行冗余字段（只更新有效行）
    UPDATE r
    SET r.leader_level_no  = l.level_no,
        r.leader_score     = l.score,
        r.self_level_no    = s.level_no,
        r.self_score       = s.score,
        r.client_avg_score = c.avg_score
    FROM dbo.uf_assess_sheet_row r
    LEFT JOIN #cur l ON l.element_id = r.element_id AND l.rater_type = 'LEADER'
    LEFT JOIN #cur s ON s.element_id = r.element_id AND s.rater_type = 'SELF' AND s.rater_resource_id = @resource_id
    OUTER APPLY (
        SELECT AVG(x.score) AS avg_score
        FROM #cur x WHERE x.element_id = r.element_id AND x.rater_type = 'CLIENT'
    ) c
    WHERE r.sheet_id = @sheet_id AND r.active = 1;

    -- 10. 页面头
    UPDATE dbo.uf_assess_sheet
    SET standard_score = ROUND(@standard, 2),
        leader_score   = ROUND(@leader, 2),
        client_score   = ROUND(@client, 2),
        self_score     = ROUND(@self, 2),
        actual_score   = ROUND(@actual, 2),
        score_rate     = ROUND(@rate, 2),
        grade_name     = @grade,
        client_count   = ISNULL(@client_n, 0),
        status         = @status,
        need_recalc    = 0,
        update_time    = SYSDATETIME()
    WHERE id = @sheet_id;

    DROP TABLE #act;
    DROP TABLE #cur;
END
GO

IF OBJECT_ID('dbo.usp_assess_rate', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_rate;
GO
CREATE PROCEDURE dbo.usp_assess_rate
    @company_id          BIGINT,
    @year                INT,
    @resource_id         BIGINT,
    @rater_type          VARCHAR(10),
    @rater_resource_id   BIGINT,
    @workflow_request_id BIGINT,
    @ratings_json        NVARCHAR(MAX)
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

    -- 5. 等级必须为 1–5（50103）
    IF EXISTS (
        SELECT 1 FROM OPENJSON(@ratings_json)
             WITH (element_id BIGINT '$.elementId', level_no INT '$.levelNo') j
        WHERE j.level_no IS NULL OR j.level_no NOT BETWEEN 1 AND 5)
    BEGIN
        ROLLBACK;
        THROW 50103, N'等级值不合法', 1;
    END

    -- 6. 客户序号：同一客户沿用原序号，新客户取最大序号+1
    IF @rater_type = 'CLIENT'
    BEGIN
        SELECT @seq = MAX(client_seq) FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT' AND rater_resource_id = @rater_resource_id;

        IF @seq IS NULL
            SELECT @seq = ISNULL(MAX(client_seq), 0) + 1 FROM dbo.uf_assess_sheet_rating
            WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT';
    END

    -- 7a. 领导评级：同一要素只保留一条，新领导的评级覆盖旧评级（已确认）
    IF @rater_type = 'LEADER'
        DELETE FROM dbo.uf_assess_sheet_rating
        WHERE sheet_id = @sheet_id AND rater_type = 'LEADER'
          AND rater_resource_id <> @rater_resource_id
          AND element_id IN (SELECT j.element_id FROM OPENJSON(@ratings_json)
                             WITH (element_id BIGINT '$.elementId') j);

    -- 7b. 按唯一键 upsert 评级（覆盖式）
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
                   t.update_time = SYSDATETIME()
    WHEN NOT MATCHED THEN
        INSERT (sheet_id, element_id, rater_type, rater_resource_id, client_seq, level_no, workflow_request_id, update_time)
        VALUES (@sheet_id, s.element_id, @rater_type, @rater_resource_id,
                CASE WHEN @rater_type = 'CLIENT' THEN @seq END, s.level_no, @workflow_request_id, SYSDATETIME());

    -- 8. 计算得分（同一事务）
    EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    -- 9. 日志
    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'RATE', @rater_resource_id,
            CONCAT(N'{"raterType":"', @rater_type, N'","workflowRequestId":', @workflow_request_id,
                   N',"count":', (SELECT COUNT(*) FROM OPENJSON(@ratings_json)), N'}'), SYSDATETIME());

    COMMIT;

    -- 10. 返回页面当前状态
    SELECT id AS sheetId, status, need_recalc, client_count, standard_score, leader_score,
           client_score, self_score, actual_score, score_rate, grade_name
    FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
END
GO
