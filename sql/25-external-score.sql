-- 25 外部评分导入（第一期：数据侧）
-- 数据库：SQL Server 2016 RTM。IF OBJECT_ID + DROP + CREATE；可重复执行（幂等）。
-- 方案依据：project/能力测评/10-外部评分导入与Agent交互方案.md（§7、§11 决议）。
--
-- A. uf_assess_sheet_rating 加 source / original_score 两列（FLOW 行恒用默认值，存量零影响）
-- B. 新表 uf_assess_external_import_log（按人保存每次成功提交的稿快照——"按上稿重填"数据源 + 审计）
-- C. usp_assess_external_score：外部评级要素级 upsert（每个要素展开到当前 领导/自评/全部客户）+ recalc
-- D. usp_assess_external_clear：显式清空某人全部 EXTERNAL 评级 + recalc
-- E. usp_assess_recalc v5：EXTERNAL 要素纳入计算；做法 B 置 0 与禁止客户评价豁免非流程要素；
--    完整性计数含 EXTERNAL。**无 EXTERNAL 评级的页面结果与 v4.1 逐位一致。**
--
-- 泛微钩子 usp_assess_rate 本次**不改**（评审结论，2026-10-22）：
--   客户名单覆盖删除保持"连同 EXTERNAL 行一起删"——被移出 cpkh 的客户若保留 EXTERNAL 行，
--   会持续污染客户均分（删了名单却没删分数，比缺行更危险）；保留 source 过滤反而引入脏计分。
--   名单变化的正确姿势：流程重触发后在测评端点一次"按上稿重填"，EXTERNAL 客户行收敛到最新名单。

-- ============================================================
-- A. rating 表加列（幂等）
-- ============================================================
IF COL_LENGTH('dbo.uf_assess_sheet_rating', 'source') IS NULL
    ALTER TABLE dbo.uf_assess_sheet_rating
        ADD source VARCHAR(10) NOT NULL
        CONSTRAINT DF_uf_assess_rating_source DEFAULT 'FLOW';
GO
IF COL_LENGTH('dbo.uf_assess_sheet_rating', 'original_score') IS NULL
    ALTER TABLE dbo.uf_assess_sheet_rating ADD original_score DECIMAL(10,2) NULL;
GO

-- ============================================================
-- B. 外部导入日志（稿快照）
-- ============================================================
IF OBJECT_ID('dbo.uf_assess_external_import_log', 'U') IS NULL
CREATE TABLE dbo.uf_assess_external_import_log (
    id                   BIGINT IDENTITY(1,1) NOT NULL,
    company_id           BIGINT NOT NULL,
    dimension_id         BIGINT NOT NULL,
    year                 INT    NOT NULL,
    resource_id          BIGINT NOT NULL,             -- 被测人
    sheet_id             BIGINT NOT NULL,
    action               VARCHAR(20) NOT NULL,         -- EXTERNAL_IMPORT / EXTERNAL_REFILL / EXTERNAL_CLEAR
    items_json           NVARCHAR(MAX) NULL,           -- 本次成功提交的稿（CLEAR 时为被清除的旧稿）
    operator_resource_id BIGINT NOT NULL,              -- 操作人（测评端登录人）
    create_time          DATETIME2 NOT NULL CONSTRAINT DF_external_log_ct DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_external_import_log PRIMARY KEY (id)
);
GO

-- ============================================================
-- C. usp_assess_external_score
--   @items_json: [{"elementId":8,"levelNo":3,"originalScore":65}, ...]（originalScore 可缺省）
--   语义：要素级 upsert（决议 1）——只写本批（人×要素），未提及要素的 EXTERNAL 行保持原样；
--        每个要素展开为 领导/自评/全部客户 三类评级（当前页面上实际存在的评价人，无则不造）。
--   展开后统一交给 usp_assess_recalc；返回页面状态 + 外部要素覆盖 x/y。
-- ============================================================
IF OBJECT_ID('dbo.usp_assess_external_score', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_external_score;
GO
CREATE PROCEDURE dbo.usp_assess_external_score
    @company_id           BIGINT,
    @year                 INT,
    @resource_id          BIGINT,
    @dimension_id         BIGINT,
    @operator_resource_id BIGINT,
    @items_json           NVARCHAR(MAX),
    @action               VARCHAR(20) = 'EXTERNAL_IMPORT'   -- 重填由调用方传 EXTERNAL_REFILL
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;

    DECLARE @sheet_id BIGINT, @period_id BIGINT, @period_status INT;

    -- 1. 定位页面（带维度；50101）
    SELECT @sheet_id = s.id, @period_id = s.period_id
    FROM dbo.uf_assess_sheet s WITH (UPDLOCK, ROWLOCK)
    JOIN dbo.uf_assess_period p ON p.id = s.period_id
    WHERE s.company_id = @company_id AND p.year = @year AND s.resource_id = @resource_id
      AND s.dimension_id = @dimension_id;

    IF @sheet_id IS NULL
    BEGIN
        ROLLBACK;
        THROW 50101, N'未找到页面（被测人未配备人员能力要求或维度不符）', 1;
    END

    -- 2. 年度状态（50105）
    SELECT @period_status = status FROM dbo.uf_assess_period WHERE id = @period_id;
    IF @period_status = 1
    BEGIN
        ROLLBACK;
        THROW 50105, N'年度已关闭', 1;
    END

    -- 3. 载荷非空
    IF @items_json IS NULL OR NOT EXISTS (SELECT 1 FROM OPENJSON(@items_json))
    BEGIN
        ROLLBACK;
        THROW 50112, N'导入内容为空', 1;
    END

    -- 4. 明细校验：等级 1–5（50103）；要素必须在页面上且为非流程要素（50110）
    SELECT j.element_id, j.level_no, j.original_score
    INTO #items
    FROM OPENJSON(@items_json)
         WITH (element_id BIGINT '$.elementId',
               level_no INT '$.levelNo',
               original_score DECIMAL(10,2) '$.originalScore') j;

    IF EXISTS (SELECT 1 FROM #items WHERE level_no IS NULL OR level_no NOT BETWEEN 1 AND 5)
    BEGIN
        ROLLBACK;
        THROW 50103, N'等级值不合法', 1;
    END

    IF EXISTS (
        SELECT 1 FROM #items j
        WHERE NOT EXISTS (SELECT 1
                          FROM dbo.uf_assess_sheet_row r
                          JOIN dbo.uf_ability_element e ON e.id = r.element_id
                          WHERE r.sheet_id = @sheet_id AND r.element_id = j.element_id
                            AND ISNULL(e.assess_in_flow, 1) = 0 AND e.deleted = 0))
    BEGIN
        ROLLBACK;
        THROW 50110, N'要素不在该页面中，或属于流程评价要素（应由泛微流程钩子写入）', 1;
    END

    -- 5. 构建本批目标评价人：领导（页面最新 LEADER 行，其次 sheet.leader_resource_id）、自评、全部现有客户
    DECLARE @leader_res BIGINT;
    SELECT TOP 1 @leader_res = rater_resource_id
    FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND rater_type = 'LEADER'
    ORDER BY update_time DESC, id DESC;
    IF @leader_res IS NULL
        SELECT @leader_res = leader_resource_id FROM dbo.uf_assess_sheet WHERE id = @sheet_id;

    CREATE TABLE #raters (rater_type VARCHAR(10), rater_resource_id BIGINT, client_seq INT);
    IF @leader_res IS NOT NULL
        INSERT #raters VALUES ('LEADER', @leader_res, NULL);
    INSERT #raters VALUES ('SELF', @resource_id, NULL);
    INSERT #raters
    SELECT 'CLIENT', rt.rater_resource_id, ISNULL(MAX(rt.client_seq), 0)
    FROM dbo.uf_assess_sheet_rating rt
    WHERE rt.sheet_id = @sheet_id AND rt.rater_type = 'CLIENT'
    GROUP BY rt.rater_resource_id;

    -- 新客户（在名单但从未评级——理论上不存在，客户行由钩子先建）沿用 rate 的序号规则兜底
    DECLARE @next_seq INT = (SELECT ISNULL(MAX(client_seq), 0) + 1 FROM dbo.uf_assess_sheet_rating
                             WHERE sheet_id = @sheet_id AND rater_type = 'CLIENT');
    UPDATE #raters SET client_seq = @next_seq WHERE rater_type = 'CLIENT' AND client_seq = 0;

    -- 6. 同要素去重（防 MERGE 多匹配；重复行保留其一，一致性由调用方 preview 保证）
    ;WITH d AS (
        SELECT element_id, level_no, original_score,
               ROW_NUMBER() OVER (PARTITION BY element_id ORDER BY (SELECT 1)) AS rn
        FROM #items)
    DELETE FROM d WHERE rn > 1;

    -- 7. 要素 × 评价人 upsert（EXTERNAL 语义：覆盖值与来源，不清其它要素）
    MERGE dbo.uf_assess_sheet_rating AS t
    USING (
        SELECT i.element_id, i.level_no, i.original_score, r.rater_type, r.rater_resource_id, r.client_seq
        FROM #items i
        CROSS JOIN #raters r
    ) AS s
    ON  t.sheet_id = @sheet_id AND t.element_id = s.element_id
    AND t.rater_type = s.rater_type AND t.rater_resource_id = s.rater_resource_id
    WHEN MATCHED THEN
        UPDATE SET t.level_no = s.level_no,
                   t.source = 'EXTERNAL',
                   t.original_score = s.original_score,
                   t.workflow_request_id = NULL,
                   t.client_seq = CASE WHEN s.rater_type = 'CLIENT' AND t.client_seq IS NULL THEN s.client_seq ELSE t.client_seq END,
                   t.update_time = SYSDATETIME()
    WHEN NOT MATCHED THEN
        INSERT (sheet_id, element_id, rater_type, rater_resource_id, client_seq,
                level_no, workflow_request_id, source, original_score, update_time)
        VALUES (@sheet_id, s.element_id, s.rater_type, s.rater_resource_id,
                CASE WHEN s.rater_type = 'CLIENT' THEN s.client_seq END,
                s.level_no, NULL, 'EXTERNAL', s.original_score, SYSDATETIME());

    -- 8. 页面操作日志 + 稿快照日志（重填/清空由 Java 传 action；快照即本批稿）
    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, @action, @operator_resource_id,
            CONCAT(N'{"source":"EXTERNAL","operatorId":', @operator_resource_id,
                   N',"items":', @items_json, N'}'), SYSDATETIME());

    INSERT dbo.uf_assess_external_import_log
        (company_id, dimension_id, year, resource_id, sheet_id, action, items_json, operator_resource_id)
    VALUES (@company_id, @dimension_id, @year, @resource_id, @sheet_id, @action, @items_json, @operator_resource_id);

    -- 9. 重算（同事务）
    EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    COMMIT;

    -- 10. 返回页面状态与外部要素覆盖（决议 2：x/y 供稿面展示）
    DECLARE @ext_total INT, @ext_covered INT;
    SELECT @ext_total = COUNT(*)
    FROM dbo.uf_assess_sheet_row r
    JOIN dbo.uf_ability_element e ON e.id = r.element_id AND e.deleted = 0
    WHERE r.sheet_id = @sheet_id AND ISNULL(e.assess_in_flow, 1) = 0;

    SELECT @ext_covered = COUNT(DISTINCT rt.element_id)
    FROM dbo.uf_assess_sheet_rating rt
    WHERE rt.sheet_id = @sheet_id AND rt.source = 'EXTERNAL';

    SELECT id AS sheetId, status, need_recalc, client_count, standard_score, leader_score,
           client_score, self_score, actual_score, score_rate, grade_name, annual_score,
           @ext_total AS externalTotal, @ext_covered AS externalCovered
    FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
END
GO

-- ============================================================
-- D. usp_assess_external_clear：显式清空某人全部 EXTERNAL 评级（决议 1 配套）
-- ============================================================
IF OBJECT_ID('dbo.usp_assess_external_clear', 'P') IS NOT NULL DROP PROCEDURE dbo.usp_assess_external_clear;
GO
CREATE PROCEDURE dbo.usp_assess_external_clear
    @company_id           BIGINT,
    @year                 INT,
    @resource_id          BIGINT,
    @dimension_id         BIGINT,
    @operator_resource_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;
    BEGIN TRAN;

    DECLARE @sheet_id BIGINT, @period_id BIGINT, @period_status INT, @old_items NVARCHAR(MAX);

    SELECT @sheet_id = s.id, @period_id = s.period_id
    FROM dbo.uf_assess_sheet s WITH (UPDLOCK, ROWLOCK)
    JOIN dbo.uf_assess_period p ON p.id = s.period_id
    WHERE s.company_id = @company_id AND p.year = @year AND s.resource_id = @resource_id
      AND s.dimension_id = @dimension_id;

    IF @sheet_id IS NULL
    BEGIN
        ROLLBACK;
        THROW 50101, N'未找到页面（被测人未配备人员能力要求或维度不符）', 1;
    END

    SELECT @period_status = status FROM dbo.uf_assess_period WHERE id = @period_id;
    IF @period_status = 1
    BEGIN
        ROLLBACK;
        THROW 50105, N'年度已关闭', 1;
    END

    -- 被清除的稿快照留档（取最近一次提交稿，仅审计用）
    SELECT TOP 1 @old_items = items_json
    FROM dbo.uf_assess_external_import_log
    WHERE company_id = @company_id AND dimension_id = @dimension_id
      AND year = @year AND resource_id = @resource_id
      AND action <> 'EXTERNAL_CLEAR'
    ORDER BY id DESC;

    DELETE FROM dbo.uf_assess_sheet_rating
    WHERE sheet_id = @sheet_id AND source = 'EXTERNAL';

    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'EXTERNAL_CLEAR', @operator_resource_id,
            CONCAT(N'{"cleared":true,"lastItemsJson":',
                   CASE WHEN @old_items IS NULL THEN N'null' ELSE @old_items END, N'}'),
            SYSDATETIME());

    INSERT dbo.uf_assess_external_import_log
        (company_id, dimension_id, year, resource_id, sheet_id, action, items_json, operator_resource_id)
    VALUES (@company_id, @dimension_id, @year, @resource_id, @sheet_id, 'EXTERNAL_CLEAR', @old_items, @operator_resource_id);

    EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    COMMIT;

    SELECT id AS sheetId, status, need_recalc, client_count, standard_score, leader_score,
           client_score, self_score, actual_score, score_rate, grade_name, annual_score,
           (SELECT COUNT(*) FROM dbo.uf_assess_sheet_row r2
            JOIN dbo.uf_ability_element e2 ON e2.id = r2.element_id AND e2.deleted = 0
            WHERE r2.sheet_id = @sheet_id AND ISNULL(e2.assess_in_flow, 1) = 0) AS externalTotal,
           0 AS externalCovered
    FROM dbo.uf_assess_sheet WHERE id = @sheet_id;
END
GO

-- ============================================================
-- E. usp_assess_recalc v5：基于 v4.1（sql/20）全量重建，5 处变化：
--   1) #act 纳入「存在 EXTERNAL 评级」的页面行（含 active=0 未进流程的、
--      以及标志改为不需评价但同步未重跑的 active=1 行）；is_ext=非流程标记且有 EXTERNAL 评级。
--      无 EXTERNAL 评级的行集合与 v4.1 完全相同——存量页面逐位回归 v4.1；
--   2) 做法 B 置 0 仅作用于流程内要素（a.is_ext=0），EXTERNAL 要素的客户评级正常计分；
--   3) 客户完整性计数 @n_client 与每客户计数包含 EXTERNAL 要素；
--   4) 页面行冗余字段更新范围跟随 #act（不再限定 r.active=1），EXTERNAL 行同样展示三类结果；
--   5) 冗余字段客户均分对 EXTERNAL 要素正常显示（不强制 0）。
--   其余逻辑与 v4.1 完全一致（配置完整性、标准分、领导去重取最新、综合评定、状态）。
-- ============================================================
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

    -- 2. 有效要素 = 流程有效行（active=1）∪ 有 EXTERNAL 评级的行；is_ext 标记非流程纳入行
    SELECT r.id AS row_id, r.element_id, r.category_id, r.required_level_no,
           cw.weight AS cat_weight,
           ISNULL(e.allow_client_assess, 1) AS allow_client,
           -- is_ext：非流程要素且实际存在 EXTERNAL 评级才豁免做法 B / 计入客户完整性。
           -- 不用 active=0 判断：标志改动后同步未重跑的页面行仍是 active=1（实测存在），
           -- 以"标记+外部评级"双条件为准，与外部导入 proc 校验口径一致。
           CASE WHEN ISNULL(e.assess_in_flow, 1) = 0 AND EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating x2
                    WHERE x2.sheet_id = r.sheet_id AND x2.element_id = r.element_id
                      AND x2.source = 'EXTERNAL') THEN 1 ELSE 0 END AS is_ext,
           COUNT(*) OVER (PARTITION BY r.category_id) AS n_c
    INTO #act
    FROM dbo.uf_assess_sheet_row r
    LEFT JOIN dbo.uf_assess_weight_ability cw
           ON cw.company_id = @company_id AND cw.dimension_id = @dimension_id
          AND cw.category_id = r.category_id
    LEFT JOIN dbo.uf_ability_element e ON e.id = r.element_id
    WHERE r.sheet_id = @sheet_id
      AND (r.active = 1
           OR EXISTS (SELECT 1 FROM dbo.uf_assess_sheet_rating x
                      WHERE x.sheet_id = r.sheet_id AND x.element_id = r.element_id
                        AND x.source = 'EXTERNAL'));

    IF EXISTS (SELECT 1 FROM #act WHERE cat_weight IS NULL OR required_level_no IS NULL)
        THROW 50104, N'配置不完整（类别权重或要求等级缺失，请检查当前维度下的测评配置）', 1;

    SELECT @n = COUNT(*) FROM #act;
    IF @n = 0 THROW 50104, N'页面没有有效要素', 1;

    SELECT @n_client = COUNT(*) FROM #act WHERE allow_client = 1 OR is_ext = 1;

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

    -- 5c. 做法 B：流程内不允许客户评价的要素，客户评级得分置 0；
    --     EXTERNAL 要素（is_ext=1）豁免——外部导入的客户评级正常计分（方案 §11）
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

    -- v4.1 起：领导评级按要素去重取最新（EXTERNAL 领导行同规则，无冲突）
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

    IF @n_client = 0 SET @client = 0;   -- 全部要素禁止客户评价且无外部要素：客户分按 0 计（做法 B）

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
