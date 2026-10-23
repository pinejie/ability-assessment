-- 30 等级分值动态化：uf_assess_level_score 升级为"等级唯一定义源"
-- 数据库：SQL Server。IF 判断 + DROP/CREATE 过程；加列/回填幂等，可重复执行。
--
-- 背景：等级数量不再固定 1-5 级（可能是 3/5/6/7 个），等级由「能力测评配置-等级分值」定义，
--       要素"配置等级"弹窗与测评算分均跟随该配置。
--
-- A. uf_assess_level_score 加两列：level_desc（等级描述）、rate_value（对应的评价），存量回填：
--    描述按旧前端硬编码名称（1 知识级/2 规范级/3 技巧级/4 技能级/5 变通级），rate_value=level_no
--    （保持现行"等级=评价"恒等映射，行为与升级前逐位一致）。
-- B. 维度内评价值唯一索引（一个评价值只能对应一个等级；等级号唯一已有 uk_assess_level_score）。
-- C. uf_assess_sheet_rating 评级 CHECK 由 BETWEEN 1 AND 5 放宽为 >= 1（上限改由配置驱动）。
-- D. usp_assess_rate：评级值校验由"必须 1-5"改为"必须命中页面维度的等级分值配置（rate_value）"。
-- E. usp_assess_recalc v6：基于 v5（sql/25）升级，3 处变化：
--    1) 配置完整性：不再要求"恰好覆盖 1-5"，改为"当前维度至少配置一个等级且各行
--       level_no/rate_value/score 齐备"；
--    2) 评级取分改按 rate_value 连接（要求等级取分仍按等级号 level_no，口径不变）；
--    3) 新增两个前置校验：要求等级无对应分值（50104）、存在评级值未命中配置（50103），
--       避免 join 不上导致的静默旧值/漏算。其余逻辑与 v5 一致。
--    **评价口径说明**：分值÷10 的公式不变，"最高档分值=10"才有"满分=权重 100%"语义。
-- F. usp_assess_external_score：外部导入评级校验同样改为命中当前维度 rate_value 集合。
--
-- 幂等/兼容：Java 侧（AssessConfigServiceImpl / AssessArchiveServiceImpl /
--   AssessSheetSyncService / ExternalScoreService）与前端两页同批发布（见本轮改造）。

-- ============================================================
-- A. 加列 + 存量回填（幂等）
-- ============================================================
IF COL_LENGTH('dbo.uf_assess_level_score', 'level_desc') IS NULL
    ALTER TABLE dbo.uf_assess_level_score ADD level_desc NVARCHAR(100) NULL;
GO
IF COL_LENGTH('dbo.uf_assess_level_score', 'rate_value') IS NULL
    ALTER TABLE dbo.uf_assess_level_score ADD rate_value INT NULL;
GO

-- 评价值回填：维持现行恒等映射（评价 N ↔ 等级 N）
UPDATE dbo.uf_assess_level_score SET rate_value = level_no WHERE rate_value IS NULL;
GO

-- 描述回填：沿用升级前"配置等级"弹窗的硬编码等级名
UPDATE dbo.uf_assess_level_score
SET level_desc = CASE level_no
                     WHEN 1 THEN N'知识级'
                     WHEN 2 THEN N'规范级'
                     WHEN 3 THEN N'技巧级'
                     WHEN 4 THEN N'技能级'
                     WHEN 5 THEN N'变通级'
                     ELSE N'等级' + CAST(level_no AS NVARCHAR(10))
                 END
WHERE level_desc IS NULL;
GO

-- ============================================================
-- B. 维度内「对应的评价」唯一（重复键会先被服务层拦下，这里做库层兜底）
-- ============================================================
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'uk_assess_level_rate' AND object_id = OBJECT_ID('dbo.uf_assess_level_score'))
    CREATE UNIQUE INDEX uk_assess_level_rate
        ON dbo.uf_assess_level_score (company_id, dimension_id, rate_value);
GO

-- ============================================================
-- C. 评级明细 CHECK 放宽（1-5 → >=1，合法性由配置匹配把关）
--    注意：不能用 definition LIKE '%BETWEEN%' 判断旧约束——SQL Server 落库时把
--    BETWEEN 规范化成 [level_no]>=(1) AND [level_no]<=(5)（2026-10-23 首跑实测），
--    因此直接按约束名先删后加；重跑时删除旧版再建同版，幂等无害。
-- ============================================================
IF EXISTS (SELECT 1 FROM sys.check_constraints
           WHERE name = 'CK_uf_assess_rating_level'
             AND parent_object_id = OBJECT_ID('dbo.uf_assess_sheet_rating'))
    ALTER TABLE dbo.uf_assess_sheet_rating DROP CONSTRAINT CK_uf_assess_rating_level;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints
               WHERE name = 'CK_uf_assess_rating_level'
                 AND parent_object_id = OBJECT_ID('dbo.uf_assess_sheet_rating'))
    ALTER TABLE dbo.uf_assess_sheet_rating
        ADD CONSTRAINT CK_uf_assess_rating_level CHECK (level_no >= 1);
GO

-- ============================================================
-- D. usp_assess_rate：仅"等级 1-5"校验改为命中配置的「对应的评价」，其余与 sql/10 一致
-- ============================================================
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
    @client_ids_json     NVARCHAR(MAX) = NULL   -- 本次客户名单（流程 cpkh），如 [3785,2381]；NULL 不做客户名单覆盖
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

    -- 8b. 按唯一键 upsert 评级（覆盖式）
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

    -- 9. 计算得分（同一事务）
    EXEC dbo.usp_assess_recalc @sheet_id = @sheet_id;

    -- 10. 日志（记录本次客户名单，便于追溯覆盖情况）
    INSERT dbo.uf_assess_sheet_log (sheet_id, action, operator_resource_id, detail_json, create_time)
    VALUES (@sheet_id, 'RATE', @rater_resource_id,
            CONCAT(N'{"raterType":"', @rater_type, N'","workflowRequestId":', @workflow_request_id,
                   N',"count":', (SELECT COUNT(*) FROM OPENJSON(@ratings_json)),
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

-- ============================================================
-- E. usp_assess_recalc v6（基于 v5，见文件头说明；无 EXTERNAL 且配置为恒等映射的存量页面结果与 v5 逐位一致）
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
    --    v6：等级分值不再要求"恰好覆盖 1-5"，要求"至少一个等级，且各行等级/评价/分值齐备"
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

    -- 5a. 标准分 S(e)（要求等级按等级号取分，口径不变）
    UPDATE r
    SET r.standard_score  = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4),
        r.category_weight = a.cat_weight
    FROM dbo.uf_assess_sheet_row r
    JOIN #act a ON a.row_id = r.id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.level_no = a.required_level_no;

    -- 5b. 评级得分 G(e, 评价人)（v6：评价值改按 rate_value 匹配等级分值）
    UPDATE rt
    SET rt.score = ROUND(ls.score / 10.0 * a.cat_weight / a.n_c, 4)
    FROM dbo.uf_assess_sheet_rating rt
    JOIN #act a ON a.element_id = rt.element_id
    JOIN dbo.uf_assess_level_score ls
         ON ls.company_id = @company_id AND ls.dimension_id = @dimension_id
        AND ls.rate_value = rt.level_no
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

-- ============================================================
-- F. usp_assess_external_score：明细校验由"等级 1-5"改为命中当前维度 rate_value，其余与 sql/25 一致
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

    -- 4. 明细校验：评级必须命中当前维度等级分值配置的「对应的评价」（50103）；
    --    要素必须在页面上且为非流程要素（50110）
    SELECT j.element_id, j.level_no, j.original_score
    INTO #items
    FROM OPENJSON(@items_json)
         WITH (element_id BIGINT '$.elementId',
               level_no INT '$.levelNo',
               original_score DECIMAL(10,2) '$.originalScore') j;

    IF EXISTS (SELECT 1 FROM #items
               WHERE level_no IS NULL
                  OR NOT EXISTS (SELECT 1 FROM dbo.uf_assess_level_score ls
                                 WHERE ls.company_id = @company_id AND ls.dimension_id = @dimension_id
                                   AND ls.rate_value = #items.level_no))
    BEGIN
        ROLLBACK;
        THROW 50103, N'评级值不在当前维度等级分值配置（对应的评价）中', 1;
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
