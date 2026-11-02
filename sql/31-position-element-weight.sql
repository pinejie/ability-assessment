-- ============================================================
-- 31 岗位要素权重（把全局能力类型权重带到岗位能力要求明细）
-- ------------------------------------------------------------
-- 【2026-11-02 口径修正】本脚本的「要素级、按类别要素数均分、归一到 100」口径已被
--   sql/32-category-weight.sql 修正为「权重只到能力类别、类别内每个要素用完整权重、
--   不要求合计 100」。新部署请按 31 → 32 顺序执行；31 只保留为加列与 v7 历史。
-- ------------------------------------------------------------
-- 背景：
--   原口径「要素分 = 等级分值 ÷ 10 × 全局类别权重 ÷ 类别要素数」只能按类别配权重，
--   同类别下所有要素均分，且无法按岗位差异化。本次改为：
--     要素分 = 等级分值 ÷ 10 × 岗位要素权重
--   岗位未配置某要素权重时回退旧口径（全局类别权重 ÷ 该类别计分要素数）。
--
-- 变更：
--   A. 备份两张受影响表。
--   B. uf_position_ability_req_item 增加 weight（岗位要素权重，%）。
--   C. uf_assess_sheet_row 增加 element_weight（计分时使用的要素权重快照）。
--   D. 存量岗位配置按全局类别权重带出并归一到 100（每份岗位配置合计=100）。
--   E. usp_assess_recalc v7：按页面头「部门 + 岗位 + 维度」匹配岗位权重；
--      写 element_weight；评级得分与标准分均按要素权重计算。
--   F. 对进行中年度已有页面执行一次重算（TRY/CATCH 单页失败不中断脚本）。
--
-- 回滚：
--   - 存储过程可通过重新执行 sql/30-level-score-dynamic.sql 的 E 段恢复 v6。
--   - 列可保留（旧代码不读），或执行文末注释中的 DROP COLUMN。
--
-- 注意：本脚本含 DDL，需在 ecology 库显式执行；执行前请确认无年度关闭操作在跑。
-- ============================================================
SET NOCOUNT ON;
SET XACT_ABORT ON;
GO

-- ------------------------------------------------------------
-- A. 备份（同日重复执行不会覆盖第一次的备份）
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.uf_position_ability_req_item_bak_20261102', 'U') IS NULL
    SELECT * INTO dbo.uf_position_ability_req_item_bak_20261102
    FROM dbo.uf_position_ability_req_item;
GO
IF OBJECT_ID('dbo.uf_assess_sheet_row_bak_20261102', 'U') IS NULL
    SELECT * INTO dbo.uf_assess_sheet_row_bak_20261102
    FROM dbo.uf_assess_sheet_row;
GO

-- 旧存储过程定义留档（便于人工比对；回滚仍建议直接重跑 sql/30 E 段）
IF OBJECT_ID('dbo.uf_assess_proc_bak_20261102', 'U') IS NULL
    SELECT OBJECT_NAME(m.object_id) AS proc_name, m.definition, SYSDATETIME() AS backup_time
    INTO dbo.uf_assess_proc_bak_20261102
    FROM sys.sql_modules m
    WHERE m.object_id = OBJECT_ID('dbo.usp_assess_recalc');
GO

-- ------------------------------------------------------------
-- B/C. 新增列
-- ------------------------------------------------------------
IF COL_LENGTH('dbo.uf_position_ability_req_item', 'weight') IS NULL
    ALTER TABLE dbo.uf_position_ability_req_item ADD weight DECIMAL(10,4) NULL;
GO
IF COL_LENGTH('dbo.uf_assess_sheet_row', 'element_weight') IS NULL
    ALTER TABLE dbo.uf_assess_sheet_row ADD element_weight DECIMAL(10,4) NULL;
GO

-- ------------------------------------------------------------
-- D. 存量岗位配置带出权重
--    1) 每个「岗位配置 + 类别」内按全局类别权重均分（与旧口径一致）
--    2) 整份岗位配置已选类别合计不足 100 时，按比例放大到 100（只处理全部明细都有权重的配置）
--    3) 四舍五入尾差补到最后一条明细，保证合计恰好 100
-- ------------------------------------------------------------
BEGIN TRAN;

UPDATE pi
SET pi.weight = cw.weight / cnt.c
FROM dbo.uf_position_ability_req_item pi
JOIN (
    SELECT req_id, category_id, dimension_id, COUNT(*) AS c
    FROM dbo.uf_position_ability_req_item
    WHERE weight IS NULL
    GROUP BY req_id, category_id, dimension_id
) cnt ON cnt.req_id = pi.req_id AND cnt.category_id = pi.category_id
     AND cnt.dimension_id = pi.dimension_id
JOIN dbo.uf_assess_weight_ability cw
  ON cw.category_id = pi.category_id
 AND cw.company_id = 1
 AND cw.dimension_id = pi.dimension_id
WHERE pi.weight IS NULL;

-- 归一化：仅处理「整份配置全部明细已有权重」且合计偏离 100 的配置
IF OBJECT_ID('tempdb..#req_total') IS NOT NULL DROP TABLE #req_total;
SELECT pi.req_id,
       SUM(pi.weight) AS total,
       SUM(CASE WHEN pi.weight IS NULL THEN 1 ELSE 0 END) AS null_cnt,
       MAX(pi.id) AS max_id
INTO #req_total
FROM dbo.uf_position_ability_req_item pi
GROUP BY pi.req_id;

UPDATE pi
SET pi.weight = ROUND(pi.weight * 100.0 / t.total, 4)
FROM dbo.uf_position_ability_req_item pi
JOIN #req_total t ON t.req_id = pi.req_id
WHERE t.null_cnt = 0 AND t.total > 0 AND ABS(t.total - 100) > 0.01;

-- 尾差补最后一条
;WITH t AS (
    SELECT req_id, SUM(weight) AS total, MAX(id) AS max_id
    FROM dbo.uf_position_ability_req_item
    GROUP BY req_id
)
UPDATE pi
SET pi.weight = ROUND(pi.weight + (100 - t.total), 4)
FROM dbo.uf_position_ability_req_item pi
JOIN t ON t.req_id = pi.req_id AND pi.id = t.max_id
WHERE ABS(t.total - 100) > 0.0001 AND pi.weight + (100 - t.total) >= 0;

COMMIT;
GO

-- ------------------------------------------------------------
-- E. usp_assess_recalc v7
--    基于 v6（sql/30 E 段），3 处变化：
--      1) #act 增加 elem_weight：岗位明细 weight 优先，缺失时回退 cat_weight / n_c
--      2) 标准分、评级得分均改为 ls.score / 10 * elem_weight（不再除类别要素数）
--      3) 回写行上 element_weight，供测评汇总/报告按要素显示与汇总类别权重
--    保留 v6 的动态等级（level_no / rate_value）与 EXTERNAL 口径。
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.usp_assess_recalc', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_recalc;
GO
CREATE PROCEDURE dbo.usp_assess_recalc
    @sheet_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;

    DECLARE @company_id BIGINT, @resource_id BIGINT, @dimension_id BIGINT;
    DECLARE @department_id BIGINT, @job_title_id BIGINT;
    DECLARE @w_leader DECIMAL(10,4), @w_client DECIMAL(10,4), @w_self DECIMAL(10,4);
    DECLARE @n INT, @n_client INT, @standard DECIMAL(10,4), @client_n INT;
    DECLARE @leader DECIMAL(10,4), @client DECIMAL(10,4), @self DECIMAL(10,4);
    DECLARE @actual DECIMAL(10,4), @rate DECIMAL(10,4), @grade NVARCHAR(20), @status INT;

    SELECT @company_id = s.company_id, @resource_id = s.resource_id,
           @dimension_id = ISNULL(s.dimension_id, 1),
           @department_id = s.department_id, @job_title_id = s.job_title_id
    FROM dbo.uf_assess_sheet s WHERE s.id = @sheet_id;

    -- 1. 配置完整性（50104）——按 company_id + dimension_id 读取
    SELECT @w_leader = MAX(CASE WHEN assessor_code = 'LEADER' THEN weight END),
           @w_client = MAX(CASE WHEN assessor_code = 'CLIENT' THEN weight END),
           @w_self   = MAX(CASE WHEN assessor_code = 'SELF'   THEN weight END)
    FROM dbo.uf_assess_weight_assessor
    WHERE company_id = @company_id AND dimension_id = @dimension_id;

    IF @w_leader IS NULL OR @w_client IS NULL OR @w_self IS NULL
       OR @w_leader + @w_client + @w_self <> 100
       OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_level_score
                      WHERE company_id = @company_id AND dimension_id = @dimension_id)
       OR EXISTS (SELECT 1 FROM dbo.uf_assess_level_score
                  WHERE company_id = @company_id AND dimension_id = @dimension_id
                    AND (level_no IS NULL OR rate_value IS NULL OR score IS NULL))
       OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_grade_level
                      WHERE company_id = @company_id AND dimension_id = @dimension_id)
        THROW 50104, N'配置不完整（评分权重、等级分值或等级标准缺失，请检查当前维度下的测评配置）', 1;

    -- 2. 有效要素 = 流程有效行（active=1）∪ 有 EXTERNAL 评级的行；is_ext 标记非流程纳入行
    --    elem_weight：岗位明细 weight 优先；未配置时回退全局类别权重均分（v6 旧口径）
    SELECT r.id AS row_id, r.element_id, r.category_id, r.required_level_no,
           cw.weight AS cat_weight,
           ISNULL(pw.pos_weight,
                  cw.weight / NULLIF(CAST(COUNT(*) OVER (PARTITION BY r.category_id) AS DECIMAL(10,4)), 0)
           ) AS elem_weight,
           ISNULL(e.allow_client_assess, 1) AS allow_client,
           CASE WHEN ISNULL(e.assess_in_flow, 1) = 0 AND EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating x2
                    WHERE x2.sheet_id = r.sheet_id AND x2.element_id = r.element_id
                      AND x2.source = 'EXTERNAL') THEN 1 ELSE 0 END AS is_ext,
           COUNT(*) OVER (PARTITION BY r.category_id) AS n_c
    INTO #act
    FROM dbo.uf_assess_sheet s
    JOIN dbo.uf_assess_sheet_row r ON r.sheet_id = s.id
    LEFT JOIN dbo.uf_assess_weight_ability cw
           ON cw.company_id = @company_id AND cw.dimension_id = @dimension_id
          AND cw.category_id = r.category_id
    LEFT JOIN dbo.uf_ability_element e ON e.id = r.element_id
    OUTER APPLY (
        SELECT TOP 1 pi.weight AS pos_weight
        FROM dbo.uf_position_ability_req_new pr
        JOIN dbo.uf_position_ability_req_item pi
          ON pi.req_id = pr.id AND pi.dimension_id = @dimension_id
        WHERE pr.department_id = s.department_id
          AND pr.job_title_id = s.job_title_id
          AND pr.dimension_id = @dimension_id
          AND pi.element_id = r.element_id
          AND pi.weight IS NOT NULL
        ORDER BY pr.update_time DESC, pr.id DESC, pi.id DESC
    ) pw
    WHERE r.sheet_id = @sheet_id
      AND (r.active = 1
           OR EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating x
                      WHERE x.sheet_id = r.sheet_id AND x.element_id = r.element_id
                        AND x.source = 'EXTERNAL'));

    IF EXISTS (SELECT 1 FROM #act WHERE elem_weight IS NULL OR required_level_no IS NULL)
        THROW 50104, N'配置不完整（岗位/类别权重或要求等级缺失，请检查岗位能力要求与能力测评配置）', 1;

    SELECT @n = COUNT(*) FROM #act;
    IF @n = 0 THROW 50104, N'页面没有有效要素', 1;

    SELECT @n_client = COUNT(*) FROM #act WHERE allow_client = 1 OR is_ext = 1;

    -- v6 前置匹配校验（等级动态后，join 不上即静默错算，必须显式报错）：
    --   a) 要求等级必须命中等级分值的等级号
    IF EXISTS (SELECT 1 FROM #act a
               WHERE NOT EXISTS (SELECT 1 FROM dbo.uf_assess_level_score ls
                                 WHERE ls.company_id = @company_id AND ls.dimension_id = @dimension_id
                                   AND ls.level_no = a.required_level_no))
        THROW 50104, N'存在要求等级在当前维度等级分值配置中无对应等级，请检查人员能力要求与等级分值', 1;

    --   b) 已录入评级值必须命中等级分值的「对应的评价」
    IF EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating rt
               WHERE rt.sheet_id = @sheet_id
                 AND NOT EXISTS (SELECT 1 FROM dbo.uf_assess_level_score ls
                                 WHERE ls.company_id = @company_id AND ls.dimension_id = @dimension_id
                                   AND ls.rate_value = rt.level_no))
        THROW 50103, N'存在评级值未命中当前维度等级分值配置（对应的评价），请检查等级分值配置', 1;

    -- 5a. 标准分 S(e)（要求等级按等级号取分；v7 按要素权重）
    UPDATE r
    SET r.standard_score  = ROUND(ls.score / 10.0 * a.elem_weight, 4),
        r.category_weight = a.cat_weight,
        r.element_weight  = a.elem_weight
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.level_no = a.required_level_no;

    -- 5b. 评级得分 G(e, 评价人)（评价值按 rate_value 匹配；v7 按要素权重）
    UPDATE rt
    SET rt.score = ROUND(ls.score / 10.0 * a.elem_weight, 4)
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.rate_value = rt.level_no
    WHERE rt.sheet_id = @sheet_id;

    -- 5c. 做法 B：流程内不允许客户评价的要素，客户评级得分置 0；
    --     EXTERNAL 要素（is_ext=1）豁免——外部导入的客户评级正常计分
    UPDATE rt
    SET rt.score = 0
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    WHERE rt.sheet_id = @sheet_id AND rt.rater_type = 'CLIENT'
      AND a.allow_client = 0 AND a.is_ext = 0;

    -- 6. 有效评级
    SELECT rt.*
    INTO #cur
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    WHERE rt.sheet_id = @sheet_id;

    -- 7. 各角色得分（领导/自评完整性：评全 n 个要素；客户：评全 @n_client 个允许评价+外部要素）
    SELECT @standard = SUM(r.standard_score)
    FROM dbo.uf_assess_sheet_row r JOIN #act a ON a.row_id = r.id;

    IF @standard IS NULL OR @standard <= 0
        THROW 50104, N'岗位标准分为 0，无法计算得分率', 1;

    ;WITH ld AS (
        SELECT score, ROW_NUMBER() OVER (PARTITION BY element_id ORDER BY update_time DESC, id DESC) AS rn
        FROM #cur WHERE rater_type = 'LEADER')
    SELECT @leader = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM ld WHERE rn = 1;

    SELECT @self = CASE WHEN COUNT(*) = @n THEN SUM(score) END
    FROM #cur WHERE rater_type = 'SELF' AND rater_resource_id = @resource_id;

    ;WITH c AS (
        SELECT x.rater_resource_id,
               SUM(CASE WHEN a.allow_client = 1 OR a.is_ext = 1 THEN 1 ELSE 0 END) AS cnt,
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

    IF @n_client = 0 SET @client = 0;

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

    -- 9. 页面行冗余字段（范围=纳入计算的行：流程有效行 + EXTERNAL 行）
    UPDATE r
    SET r.leader_level_no  = l.level_no,
        r.leader_score     = l.score,
        r.self_level_no    = s.level_no,
        r.self_score       = s.score,
        r.client_avg_score = CASE WHEN a.allow_client = 1 OR a.is_ext = 1 THEN c.avg_score ELSE 0 END
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    LEFT JOIN #cur l ON l.element_id = r.element_id AND l.rater_type = 'LEADER'
    LEFT JOIN #cur s ON s.element_id = r.element_id AND s.rater_type = 'SELF' AND s.rater_resource_id = @resource_id
    OUTER APPLY (
        SELECT AVG(x.score) AS avg_score
        FROM #cur x WHERE x.element_id = r.element_id AND x.rater_type = 'CLIENT'
    ) c
    WHERE r.sheet_id = @sheet_id;

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

-- ------------------------------------------------------------
-- F. 进行中年度存量页面重算（逐页 TRY/CATCH；失败页仍保留旧分，需人工排查）
-- ------------------------------------------------------------
DECLARE @sid BIGINT, @failed INT = 0;
DECLARE sheet_cur CURSOR LOCAL FAST_FORWARD FOR
    SELECT s.id
    FROM dbo.uf_assess_sheet s
    JOIN dbo.uf_assess_period p ON p.id = s.period_id
    WHERE p.status = 0
    ORDER BY s.id;
OPEN sheet_cur;
FETCH NEXT FROM sheet_cur INTO @sid;
WHILE @@FETCH_STATUS = 0
BEGIN
    BEGIN TRY
        EXEC dbo.usp_assess_recalc @sheet_id = @sid;
    END TRY
    BEGIN CATCH
        SET @failed = @failed + 1;
        PRINT CONCAT(N'重算失败 sheetId=', @sid, N'：', ERROR_MESSAGE());
    END CATCH
    FETCH NEXT FROM sheet_cur INTO @sid;
END
CLOSE sheet_cur;
DEALLOCATE sheet_cur;
IF @failed > 0
    PRINT CONCAT(N'存在 ', @failed, N' 个页面重算失败，请逐页检查配置');
GO

-- ------------------------------------------------------------
-- 校验：
--   1) 每份岗位配置权重合计应=100（允许 0.01 尾差）
--   2) 页面行 element_weight 应非空
-- ------------------------------------------------------------
-- SELECT pr.id, pr.department_id, pr.job_title_id, SUM(pi.weight) AS total
-- FROM dbo.uf_position_ability_req_new pr
-- JOIN dbo.uf_position_ability_req_item pi ON pi.req_id = pr.id
-- GROUP BY pr.id, pr.department_id, pr.job_title_id
-- HAVING ABS(SUM(pi.weight) - 100) > 0.01;
--
-- SELECT COUNT(*) AS missing_element_weight
-- FROM dbo.uf_assess_sheet_row WHERE active = 1 AND element_weight IS NULL;

-- ------------------------------------------------------------
-- 回滚（仅在需要时执行）：
--   DROP PROCEDURE dbo.usp_assess_recalc;
--   -- 重新执行 sql/30-level-score-dynamic.sql 的 E 段恢复 v6
--   ALTER TABLE dbo.uf_assess_sheet_row DROP COLUMN element_weight;
--   ALTER TABLE dbo.uf_position_ability_req_item DROP COLUMN weight;
-- ------------------------------------------------------------
