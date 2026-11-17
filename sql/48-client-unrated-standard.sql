-- ============================================================
-- 48 客户不评即达标：不参与客户评价的要素按标准分计入客户得分（usp_assess_recalc v9）
-- ------------------------------------------------------------
-- 背景（2026-11-18 业务确认）：
--   原「做法 B」（sql/17、sql/20、sql/25、sql/30、sql/31、sql/32 一路沿用）规定：
--   要素 allow_client_assess = 0 时，其客户评级得分按 0 计入加权总分。
--
--   实测后果：客户评价页本就过滤掉这些要素（AssessFillService.elements 对 CLIENT 加
--   allow_client_assess = 1 条件），客户根本看不到、也评不了；但加权时这 20% 权重
--   仍按全额扣，等于凭空丢掉这部分权重。
--     例 sheet 14：客户能评要素标准分 72 / 全部 202.5，
--     客户的 20% 权重只有 7.11 分能用上，12.89 分权重凭空蒸发；
--     即使全员每项满分，得分率上限也只有 87.11%，永远到不了「胜任」（95%）。
--   36 张页面里得分率天花板在 87.11% ~ 100% 之间不等，取决于该岗位有多少要素不需客户评。
--
-- 新口径：「客户不评就是达标」
--   不参与客户评价的要素，客户得分按其【标准分】计入。
--   即：客户得分 = Σ(客户实际评级分) + Σ(不需客户评要素的标准分)
--   全员满分时客户得分 = 岗位标准分，得分率上限回到 100%。
--
-- 变更点（相对 v8，共 4 处）：
--   1) 删除原 5c「做法 B：不允许客户评价的要素，客户评级得分置 0」
--      —— 不再改写 rt.score，保留原始评级得分便于审计
--   2) 新增 @client_bonus：不参与客户评价要素的标准分合计
--   3) 客户得分公式加回 @client_bonus；@n_client = 0 时客户得分 = @standard（原为 0）
--   4) 页面行 client_avg_score：不参与客户评价的行显示标准分（原为 0）
--
-- 已知副作用（业务已接受）：
--   客户评价要素越少的岗位越占便宜 —— sheet 14 那种岗位客户只能评 36% 的要素，
--   等于白送 64% 的客户分。若日后发现被用于抬分，需改回「按比例折算」口径。
--
-- 依赖：sql/32-category-weight.sql（v8 基线）
-- 回滚：
--   DROP PROCEDURE dbo.usp_assess_recalc;
--   从 dbo.uf_assess_proc_backup 取回 v8 定义（本脚本第 0 段会备份），或重跑 sql/32 的 C 段。
--   回滚后需对进行中年度全部页面重新 EXEC usp_assess_recalc。
-- ============================================================

SET NOCOUNT ON;
SET XACT_ABORT ON;
GO

-- ------------------------------------------------------------
-- 0. 备份 v8 定义（可重复执行；同名覆盖，保留最近一次）
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
USING (SELECT 'usp_assess_recalc' AS proc_name, m.definition
       FROM sys.sql_modules m WHERE m.object_id = OBJECT_ID('dbo.usp_assess_recalc')) AS s
   ON t.proc_name = s.proc_name
WHEN MATCHED THEN UPDATE SET t.definition = s.definition, t.backup_time = SYSDATETIME()
WHEN NOT MATCHED THEN INSERT (proc_name, definition) VALUES (s.proc_name, s.definition);
GO

-- ------------------------------------------------------------
-- 1. 重建 usp_assess_recalc（v9）
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
    DECLARE @client_bonus DECIMAL(10,4);   -- 不参与客户评价的要素标准分合计（v9）
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
    --    elem_weight：岗位类别权重优先（按 category_id 匹配）；未配置时回退全局类别权重。
    --    同一类别下的每个要素都使用完整类别权重，不按要素数均分。
    SELECT r.id AS row_id, r.element_id, r.category_id, r.required_level_no,
           cw.weight AS cat_weight,
           ISNULL(pw.pos_weight, cw.weight) AS elem_weight,
           ISNULL(e.allow_client_assess, 1) AS allow_client,
           CASE WHEN ISNULL(e.assess_in_flow, 1) = 0 AND EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating x2
                    WHERE x2.sheet_id = r.sheet_id AND x2.element_id = r.element_id
                      AND x2.source = 'EXTERNAL') THEN 1 ELSE 0 END AS is_ext
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
          AND pi.category_id = r.category_id
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

    -- 5a. 标准分 S(e)（要求等级按等级号取分；v8 按岗位类别权重）
    UPDATE r
    SET r.standard_score  = ROUND(ls.score / 10.0 * a.elem_weight, 4),
        r.category_weight = a.cat_weight,
        r.element_weight  = a.elem_weight
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.level_no = a.required_level_no;

    -- 5b. 评级得分 G(e, 评价人)（评价值按 rate_value 匹配；v8 按岗位类别权重）
    UPDATE rt
    SET rt.score = ROUND(ls.score / 10.0 * a.elem_weight, 4)
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.rate_value = rt.level_no
    WHERE rt.sheet_id = @sheet_id;

    -- 5c. 【v9 已删除「做法 B」置 0】
    --     原做法 B：流程内不允许客户评价的要素，客户评级得分置 0。
    --     新口径（2026-11-18 业务确认）：「客户不评就是达标」——不参与客户评价的要素
    --     按标准分计入客户得分，不再置 0。补偿在第 7 步客户得分处一次加回（见 @client_bonus），
    --     此处不再改写 rt.score，便于审计追溯原始评级得分。

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

    -- 「客户不评就是达标」：不参与客户评价的要素（allow_client=0 且非 EXTERNAL）
    -- 按标准分计入客户得分。只补偿一次，与评过几位客户无关（各客户同一口径）。
    SELECT @client_bonus = SUM(r.standard_score)
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    WHERE a.allow_client = 0 AND a.is_ext = 0;

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
                            THEN SUM(total) / COUNT(*) + ISNULL(@client_bonus, 0) END
    FROM c;

    -- 全部要素都不需客户评价：客户权重视为达标，直接给岗位标准分（原为置 0）
    IF @n_client = 0 SET @client = @standard;

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
        -- v9：不参与客户评价的要素，客户视同达标，展示标准分（原为 0）
        r.client_avg_score = CASE WHEN a.allow_client = 1 OR a.is_ext = 1 THEN c.avg_score
                                  ELSE r.standard_score END
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
-- 2. 存量重算：进行中年度全部页面逐页重算（单页失败不影响其他页）
-- ------------------------------------------------------------
DECLARE @sid BIGINT, @failed INT = 0, @done INT = 0;
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
        SET @done = @done + 1;
    END TRY
    BEGIN CATCH
        SET @failed = @failed + 1;
        PRINT CONCAT(N'重算失败 sheetId=', @sid, N'：', ERROR_MESSAGE());
    END CATCH
    FETCH NEXT FROM sheet_cur INTO @sid;
END
CLOSE sheet_cur;
DEALLOCATE sheet_cur;
PRINT CONCAT(N'重算完成：成功 ', @done, N' 页，失败 ', @failed, N' 页');
GO

-- ------------------------------------------------------------
-- 3. 核对：有分数的页面，得分率不应再被客户缺评压住
-- ------------------------------------------------------------
SELECT id, status, standard_score, leader_score, client_score, self_score,
       actual_score, score_rate, grade_name
FROM dbo.uf_assess_sheet
WHERE actual_score IS NOT NULL
ORDER BY id;
GO
