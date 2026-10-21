-- 17 usp_assess_recalc v4：维度隔离 + 禁止客户评价要素按 0 计入（做法 B）
-- 数据库：SQL Server 2016 RTM。IF + DROP + CREATE。
-- 相对 v3 的变化：
--   1) 从页面头（uf_assess_sheet.dimension_id）取维度，配置读取（权重/等级分值/等级标准）按
--      company_id + dimension_id 过滤；
--   2) 有效要素带出 uf_ability_element.allow_client_assess；
--   3) 做法 B：不允许客户评价的要素，其 CLIENT 评级得分置 0；
--      客户完整性校验只数「允许客户评价」的要素个数（客户不必对禁止评价的要素出分，禁止项按 0 计）；
--   4) usp_assess_rate 不变（评级按 sheet_id 读写，维度经页面头传导）。

IF OBJECT_ID('dbo.usp_assess_recalc', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_recalc;
GO
CREATE PROCEDURE dbo.usp_assess_recalc
    @sheet_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @company_id BIGINT, @resource_id BIGINT, @dimension_id BIGINT;
    DECLARE @w_leader DECIMAL(10,4), @w_client DECIMAL(10,4), @w_self DECIMAL(10,4);
    DECLARE @n INT, @n_client INT, @standard DECIMAL(10,4), @client_n INT;
    DECLARE @leader DECIMAL(10,4), @client DECIMAL(10,4), @self DECIMAL(10,4);
    DECLARE @actual DECIMAL(10,4), @rate DECIMAL(10,4), @grade NVARCHAR(20), @status INT;

    SELECT @company_id = s.company_id, @resource_id = s.resource_id,
           @dimension_id = ISNULL(s.dimension_id, 1)
    FROM dbo.uf_assess_sheet s WHERE s.id = @sheet_id;

    -- 1. 配置完整性（50104）——按 company_id + dimension_id 读取
    SELECT @w_leader = MAX(CASE WHEN assessor_code = 'LEADER' THEN weight END),
           @w_client = MAX(CASE WHEN assessor_code = 'CLIENT' THEN weight END),
           @w_self   = MAX(CASE WHEN assessor_code = 'SELF'   THEN weight END)
    FROM dbo.uf_assess_weight_assessor
    WHERE company_id = @company_id AND dimension_id = @dimension_id;

    IF @w_leader IS NULL OR @w_client IS NULL OR @w_self IS NULL
       OR @w_leader + @w_client + @w_self <> 100
       OR (SELECT COUNT(DISTINCT level_no) FROM dbo.uf_assess_level_score
           WHERE company_id = @company_id AND dimension_id = @dimension_id
             AND level_no BETWEEN 1 AND 5) <> 5
       OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_grade_level
                      WHERE company_id = @company_id AND dimension_id = @dimension_id)
        THROW 50104, N'配置不完整（评分权重、等级分值或等级标准缺失，请检查当前维度下的测评配置）', 1;

    -- 2. 有效要素、类别要素数、是否允许客户评价
    SELECT r.id AS row_id, r.element_id, r.category_id, r.required_level_no,
           cw.weight AS cat_weight,
           ISNULL(e.allow_client_assess, 1) AS allow_client,
           COUNT(*) OVER (PARTITION BY r.category_id) AS n_c
    INTO #act
    FROM dbo.uf_assess_sheet_row r
    LEFT JOIN dbo.uf_assess_weight_ability cw
           ON cw.company_id = @company_id AND cw.dimension_id = @dimension_id
          AND cw.category_id = r.category_id
    LEFT JOIN dbo.uf_ability_element e ON e.id = r.element_id
    WHERE r.sheet_id = @sheet_id AND r.active = 1;

    IF EXISTS (SELECT 1 FROM #act WHERE cat_weight IS NULL OR required_level_no IS NULL)
        THROW 50104, N'配置不完整（类别权重或要求等级缺失，请检查当前维度下的测评配置）', 1;

    SELECT @n = COUNT(*) FROM #act;
    IF @n = 0 THROW 50104, N'页面没有有效要素', 1;

    SELECT @n_client = COUNT(*) FROM #act WHERE allow_client = 1;

    -- 5a. 标准分 S(e)
    UPDATE r
    SET r.standard_score  = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4),
        r.category_weight = a.cat_weight
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.level_no = a.required_level_no;

    -- 5b. 评级得分 G(e, 评价人)
    UPDATE rt
    SET rt.score = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4)
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.level_no = rt.level_no
    WHERE rt.sheet_id = @sheet_id;

    -- 5c. 做法 B：不允许客户评价的要素，客户评级得分置 0（领导/自评不受影响）
    UPDATE rt
    SET rt.score = 0
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    WHERE rt.sheet_id = @sheet_id AND rt.rater_type = 'CLIENT' AND a.allow_client = 0;

    -- 6. 有效评级
    SELECT rt.*
    INTO #cur
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    WHERE rt.sheet_id = @sheet_id;

    -- 7. 各角色得分（领导/自评完整性：评全 n 个要素；客户：评全 @n_client 个允许评价的要素）
    SELECT @standard = SUM(r.standard_score)
    FROM dbo.uf_assess_sheet_row r JOIN #act a ON a.row_id = r.id;

    IF @standard IS NULL OR @standard <= 0
        THROW 50104, N'岗位标准分为 0，无法计算得分率', 1;

    SELECT @leader = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM #cur WHERE rater_type = 'LEADER';

    SELECT @self = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM #cur WHERE rater_type = 'SELF' AND rater_resource_id = @resource_id;

    ;WITH c AS (
        SELECT x.rater_resource_id,
               SUM(CASE WHEN a.allow_client = 1 THEN 1 ELSE 0 END) AS cnt,
               SUM(x.score) AS total
        FROM #cur x
        JOIN #act a ON a.element_id = x.element_id
        WHERE x.rater_type = 'CLIENT'
        GROUP BY x.rater_resource_id
    )
    SELECT @client_n = COUNT(*),
           @client   = CASE WHEN COUNT(*) > 0 AND MIN(cnt) = @n_client
                            THEN SUM(total) / COUNT(*) END
    FROM c;

    IF @n_client = 0 SET @client = 0;   -- 全部要素禁止客户评价：客户分按 0 计（做法 B）

    -- 8. 实际得分、得分率、综合评定
    IF @leader IS NOT NULL AND @client IS NOT NULL AND @self IS NOT NULL
        SET @actual = @leader * @w_leader / 100 + @client * @w_client / 100 + @self * @w_self / 100;

    IF @actual IS NOT NULL
    BEGIN
        SET @rate = @actual / @standard * 100;

        SELECT TOP (1) @grade = g.level_name
        FROM dbo.uf_assess_grade_level g
        WHERE g.company_id = @company_id AND g.dimension_id = @dimension_id
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
        r.client_avg_score = CASE WHEN a.allow_client = 1 THEN c.avg_score ELSE 0 END
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
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
