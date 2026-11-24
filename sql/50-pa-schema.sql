-- ============================================================
-- 50 建表：岗位能力模型（uf_pa_*）
-- ------------------------------------------------------------
-- 依据：《17-岗位能力模型重构方案》§四（2026-11-21 口径已全部确认）
--
-- 背景（为什么另起一套表，而不是改老表）：
--   旧模型是「全局通用能力库 → 按岗位挑要素 → 全局等级分值/测评人权重 → 与标准分比」。
--   新模型是「每个部门+岗位自带一整套能力模型 → 一人映射一岗 → 上级/360 打分
--   → 项得分取平均 → 按项权重加权平均 → 命中分值区间得等级说明」。
--   老表（uf_ability_category/element/level、uf_position_*、uf_user_*、
--   uf_assess_weight_*、uf_assess_grade_level、uf_assess_level_score、uf_assess_sheet*）
--   已被 sql/22、23、30、31、32、35、40、43、45、48、49 改了 11 轮，字段语义互相冲突
--   （如 weight 在不同脚本里含义不同、assess_in_flow 与 active 双口径）。
--   在上面继续加开关只会更难维护，故新建 9 张语义单一的表，旧表在 sql/51 连数据一并删除。
--
-- 与需求口径的对应（见方案 §二）：
--   · 岗位粒度 = 部门 + 岗位各配一套，跨部门同名岗位不共用（唯一键带 department_id）
--   · 权重只到「能力项」，类别不再有权重；不强制合计 100%（仅页面提醒），故无 CHECK
--   · 评级主体 = 多选固定两项 → 两个布尔列 + CHECK 至少选一个
--   · 等级 = 名称/分值/排序/描述，数量任意（无 level_no 概念，排序用 sort）
--   · 分值区间 = (lower_bound, upper_bound]，下界开上界闭，NULL 表示 ±∞
--   · 人员映射 = 一年一人一个岗位（UNIQUE period_id + resource_id）
--
-- 隔离：全部带 dimension_id，并已登记进 MyBatisPlusConfig.TENANT_TABLES，
--       由租户拦截器自动拼 WHERE / INSERT 填充。
--       唯一索引均带 dimension_id —— 同部门同岗位名在不同维度下互不冲突。
--
-- 幂等：建表用 IF OBJECT_ID(...) IS NULL，建索引用 sys.indexes 存在性判断。
--       两者分开写：表已存在但索引缺失时（如分两次执行、手工补表），重跑本脚本能补齐索引。
-- 回滚：本脚本只建表不动既有对象，DROP 9 张 uf_pa_* 即可。
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 1. 测评岗位（部门 + 岗位，项目自有；不是泛微 HrmJobTitles）
-- ------------------------------------------------------------
-- department_name 是快照：泛微部门改名后，历史报告里的部门名不应跟着变。
IF OBJECT_ID('dbo.uf_pa_post', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_post (
        id              BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id    BIGINT        NOT NULL,
        department_id   BIGINT        NOT NULL,
        department_name NVARCHAR(100) NULL,
        post_code       NVARCHAR(50)  NULL,
        post_name       NVARCHAR(100) NOT NULL,
        description     NVARCHAR(500) NULL,
        status          INT           NOT NULL CONSTRAINT DF_pa_post_status DEFAULT 1,
        sort            INT           NOT NULL CONSTRAINT DF_pa_post_sort DEFAULT 0,
        create_by       BIGINT        NULL,
        create_time     DATETIME2     NOT NULL CONSTRAINT DF_pa_post_ct DEFAULT SYSDATETIME(),
        update_by       BIGINT        NULL,
        update_time     DATETIME2     NOT NULL CONSTRAINT DF_pa_post_ut DEFAULT SYSDATETIME(),
        deleted         INT           NOT NULL CONSTRAINT DF_pa_post_del DEFAULT 0,
        CONSTRAINT PK_uf_pa_post PRIMARY KEY (id)
    );
END
GO
-- 唯一键用筛选索引（WHERE deleted = 0）：软删后可以重建同名岗位。
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_post_name' AND object_id = OBJECT_ID('dbo.uf_pa_post'))
    CREATE UNIQUE INDEX UX_pa_post_name ON dbo.uf_pa_post(dimension_id, department_id, post_name) WHERE deleted = 0;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_post_dim' AND object_id = OBJECT_ID('dbo.uf_pa_post'))
    CREATE INDEX IX_pa_post_dim ON dbo.uf_pa_post(dimension_id, deleted, status);
GO

-- ------------------------------------------------------------
-- 2. 能力类别（岗位内）
-- ------------------------------------------------------------
-- Excel 的「维度」列（领导力/管理力/质检专业…）落在这里。
-- 注意：**不要叫"维度"** —— 系统里 dimension_id 已经是租户，
--       业务上再来一个"维度"会让前后端和库三处语义打架。
IF OBJECT_ID('dbo.uf_pa_category', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_category (
        id            BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id  BIGINT        NOT NULL,
        post_id       BIGINT        NOT NULL,
        category_name NVARCHAR(100) NOT NULL,
        description   NVARCHAR(500) NULL,
        sort          INT           NOT NULL CONSTRAINT DF_pa_cat_sort DEFAULT 0,
        status        INT           NOT NULL CONSTRAINT DF_pa_cat_status DEFAULT 1,
        create_by     BIGINT        NULL,
        create_time   DATETIME2     NOT NULL CONSTRAINT DF_pa_cat_ct DEFAULT SYSDATETIME(),
        update_by     BIGINT        NULL,
        update_time   DATETIME2     NOT NULL CONSTRAINT DF_pa_cat_ut DEFAULT SYSDATETIME(),
        deleted       INT           NOT NULL CONSTRAINT DF_pa_cat_del DEFAULT 0,
        CONSTRAINT PK_uf_pa_category PRIMARY KEY (id)
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_category_name' AND object_id = OBJECT_ID('dbo.uf_pa_category'))
    CREATE UNIQUE INDEX UX_pa_category_name ON dbo.uf_pa_category(dimension_id, post_id, category_name) WHERE deleted = 0;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_category_post' AND object_id = OBJECT_ID('dbo.uf_pa_category'))
    CREATE INDEX IX_pa_category_post ON dbo.uf_pa_category(post_id, deleted);
GO

-- ------------------------------------------------------------
-- 3. 能力项（原「能力要素」，全局改名）
-- ------------------------------------------------------------
-- weight 单位是**百分数**：8.0000 表示 8%。
--   为什么不存 0.08：页面上人看的是「8%」，存小数就必须在每处显示做 ×100，
--   而"输入 8 结果存成 800%"是这类换算最经典的线上事故。导入时 0.08 × 100 换算一次，
--   换算只发生在一个地方（导入器）。
-- 不校验权重合计 = 100%：业务明确只提醒不阻断（人事专员那份 Excel 合计 110%）。
-- rater_leader / rater_360：只有固定两项，用两个布尔列比子表简单，
--   且天然支持组合（两者都 1 = 上级和 360 都评）。CHECK 保证至少选一个，
--   否则该能力项没有任何人能评，页面永远推不动。
-- item_code 唯一性限定在岗位内：Excel 里 L1/S1 等编码跨岗位大量重号。
-- 【2026-11-23 已推翻】即便限定在岗位内也不成立，见 sql/65：同一岗位里编码常分两段用
-- （专业能力 A01…G01，职业素养起又从 E01 来），UX_pa_item_code 已删除。
-- 下方 CREATE UNIQUE INDEX 保留为历史记录，新库跑完 50 后需接着跑 65。
IF OBJECT_ID('dbo.uf_pa_item', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_item (
        id           BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id BIGINT        NOT NULL,
        post_id      BIGINT        NOT NULL,
        category_id  BIGINT        NOT NULL,
        item_code    NVARCHAR(50)  NULL,
        item_name    NVARCHAR(100) NOT NULL,
        definition   NVARCHAR(MAX) NULL,
        weight       DECIMAL(9,4)  NOT NULL CONSTRAINT DF_pa_item_weight DEFAULT 0,
        rater_leader INT           NOT NULL CONSTRAINT DF_pa_item_rl DEFAULT 1,
        rater_360    INT           NOT NULL CONSTRAINT DF_pa_item_r3 DEFAULT 0,
        sort         INT           NOT NULL CONSTRAINT DF_pa_item_sort DEFAULT 0,
        status       INT           NOT NULL CONSTRAINT DF_pa_item_status DEFAULT 1,
        create_by    BIGINT        NULL,
        create_time  DATETIME2     NOT NULL CONSTRAINT DF_pa_item_ct DEFAULT SYSDATETIME(),
        update_by    BIGINT        NULL,
        update_time  DATETIME2     NOT NULL CONSTRAINT DF_pa_item_ut DEFAULT SYSDATETIME(),
        deleted      INT           NOT NULL CONSTRAINT DF_pa_item_del DEFAULT 0,
        CONSTRAINT PK_uf_pa_item PRIMARY KEY (id),
        CONSTRAINT CK_pa_item_rater CHECK (rater_leader = 1 OR rater_360 = 1)
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_item_code' AND object_id = OBJECT_ID('dbo.uf_pa_item'))
    CREATE UNIQUE INDEX UX_pa_item_code ON dbo.uf_pa_item(dimension_id, post_id, item_code) WHERE deleted = 0 AND item_code IS NOT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_item_post' AND object_id = OBJECT_ID('dbo.uf_pa_item'))
    CREATE INDEX IX_pa_item_post ON dbo.uf_pa_item(post_id, deleted);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_item_category' AND object_id = OBJECT_ID('dbo.uf_pa_item'))
    CREATE INDEX IX_pa_item_category ON dbo.uf_pa_item(category_id, deleted);
GO

-- ------------------------------------------------------------
-- 4. 能力项等级（每个能力项自己一套，数量任意）
-- ------------------------------------------------------------
-- 相对旧 uf_ability_element_level + uf_assess_level_score 的双表拆分，这里合成一张：
-- 等级号（level_no）概念取消，排序就是 sort。
--   · level_name  入门/新手、精进/能手…
--   · score       分值（等级分值就是它；旧表里"维度等级分值"与"要素等级分"
--                 两处并存、还要靠 rate_value 折算的问题彻底消失）
--   · level_desc  行为描述（Excel 每级那一整段文字，可空但强烈建议填 ——
--                 只给"精进/能手"四个字，评价人无从判断「知人善用」该打几分）
-- 服务层校验（不落库）：同项内 sort 唯一、score 唯一、至少 2 档、被评级引用后禁删。
IF OBJECT_ID('dbo.uf_pa_item_level', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_item_level (
        id           BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id BIGINT        NOT NULL,
        item_id      BIGINT        NOT NULL,
        level_name   NVARCHAR(50)  NOT NULL,
        score        DECIMAL(6,2)  NOT NULL,
        level_desc   NVARCHAR(MAX) NULL,
        sort         INT           NOT NULL CONSTRAINT DF_pa_lvl_sort DEFAULT 0,
        create_time  DATETIME2     NOT NULL CONSTRAINT DF_pa_lvl_ct DEFAULT SYSDATETIME(),
        update_time  DATETIME2     NOT NULL CONSTRAINT DF_pa_lvl_ut DEFAULT SYSDATETIME(),
        CONSTRAINT PK_uf_pa_item_level PRIMARY KEY (id)
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_item_level_sort' AND object_id = OBJECT_ID('dbo.uf_pa_item_level'))
    CREATE UNIQUE INDEX UX_pa_item_level_sort ON dbo.uf_pa_item_level(item_id, sort);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_item_level_item' AND object_id = OBJECT_ID('dbo.uf_pa_item_level'))
    CREATE INDEX IX_pa_item_level_item ON dbo.uf_pa_item_level(item_id);
GO

-- ------------------------------------------------------------
-- 5. 分值区间 → 等级说明（岗位级各一套）
-- ------------------------------------------------------------
-- 区间语义 (lower_bound, upper_bound]：下界开、上界闭，与旧 uf_assess_grade_level
-- 的「> lower AND <= upper」一致，业务心智不用重学。
--   · lower_bound IS NULL → −∞（最低档，写作 (,3]）
--   · upper_bound IS NULL → +∞（最高档，写作 (4,)）
-- 服务层校验：同岗位内区间不得重叠、必须留出上下开口（否则会有得分命中不到任何档）。
-- 注意这里比的是**分值刻度**（如 3~5 分），不再是旧模型的「得分率 %」。
IF OBJECT_ID('dbo.uf_pa_band', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_band (
        id           BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id BIGINT       NOT NULL,
        post_id      BIGINT       NOT NULL,
        band_name    NVARCHAR(50) NOT NULL,
        lower_bound  DECIMAL(6,2) NULL,
        upper_bound  DECIMAL(6,2) NULL,
        sort         INT          NOT NULL CONSTRAINT DF_pa_band_sort DEFAULT 0,
        create_time  DATETIME2    NOT NULL CONSTRAINT DF_pa_band_ct DEFAULT SYSDATETIME(),
        update_time  DATETIME2    NOT NULL CONSTRAINT DF_pa_band_ut DEFAULT SYSDATETIME(),
        CONSTRAINT PK_uf_pa_band PRIMARY KEY (id)
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_band_post' AND object_id = OBJECT_ID('dbo.uf_pa_band'))
    CREATE INDEX IX_pa_band_post ON dbo.uf_pa_band(post_id, sort);
GO

-- ------------------------------------------------------------
-- 6. 人员岗位映射 —— 已下线（2026-11-21 增补）
-- ------------------------------------------------------------
-- 原设计是一张 uf_pa_person_post（年度 × 人员 → 岗位），发起测评前必须先配一遍。
-- 现在岗位在发起页当场选择（PaTaskDTO.CreateRequest.postId），这张表没有任何读方，
-- 已由 sql/53 备份后 DROP。新装环境不需要它，所以这里不再建。
-- 「一年一人一岗」仍然成立，改由 uf_pa_sheet 的 (period_id, resource_id) 唯一索引保证。
--
-- 编号保留为 6 只是为了让下文的小节号不跟着挪 —— 免得历史讨论里说的「sql/50 第 7 节」
-- 突然指到别处。

-- ------------------------------------------------------------
-- 7. 测评页 / 页面行 / 评级
-- ------------------------------------------------------------
-- 一页 = 一个被测人一个年度。三张表替代旧 uf_assess_sheet / _row / _rating。
-- 旧表最大的问题（见《15-评价网页化改造方案》§14）是「active 与 assess_in_flow 双口径」：
--   存储过程按 row.active 数要素、网页按 element.assess_in_flow 收评级，
--   两者漂移就出现「页面收了 19 条、算分认为应有 20 条，全员提交也不出分」。
--   新模型只有「这一页有哪些能力项」一个口径（uf_pa_sheet_item），不存在双开关。
--
-- 快照策略：sheet_item 冗余 item_name / weight，评级表冗余 level_name / score。
--   岗位后续被改，历史报告不受影响（口径 #15）。
--
-- 自评：全项都评，self_score 只展示不进公式（口径 #13）。
-- status：0 未评价 / 1 部分提交 / 2 已算分。自评未交不改变本列（任务侧另标「待自评」）。
IF OBJECT_ID('dbo.uf_pa_sheet', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_sheet (
        id                 BIGINT IDENTITY(1,1) NOT NULL,
        company_id         BIGINT        NOT NULL,
        dimension_id       BIGINT        NOT NULL,
        period_id          BIGINT        NOT NULL,
        resource_id        BIGINT        NOT NULL,
        post_id            BIGINT        NOT NULL,
        department_id      BIGINT        NULL,
        department_name    NVARCHAR(100) NULL,
        post_name          NVARCHAR(100) NULL,
        leader_resource_id BIGINT        NULL,
        status             INT           NOT NULL CONSTRAINT DF_pa_sheet_status DEFAULT 0,
        item_count         INT           NOT NULL CONSTRAINT DF_pa_sheet_cnt DEFAULT 0,
        self_score         DECIMAL(10,4) NULL,
        leader_score       DECIMAL(10,4) NULL,
        rater360_score     DECIMAL(10,4) NULL,
        total_score        DECIMAL(10,4) NULL,
        band_name          NVARCHAR(50)  NULL,
        comment_text       NVARCHAR(MAX) NULL,
        snapshot_json      NVARCHAR(MAX) NULL,
        create_time        DATETIME2     NOT NULL CONSTRAINT DF_pa_sheet_ct DEFAULT SYSDATETIME(),
        update_time        DATETIME2     NOT NULL CONSTRAINT DF_pa_sheet_ut DEFAULT SYSDATETIME(),
        CONSTRAINT PK_uf_pa_sheet PRIMARY KEY (id)
    );
END
GO
-- 一人一年度一页；对同一人重复发起是「复用该页 + 新开任务」，不是新开页面
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_sheet_period_res' AND object_id = OBJECT_ID('dbo.uf_pa_sheet'))
    CREATE UNIQUE INDEX UX_pa_sheet_period_res ON dbo.uf_pa_sheet(period_id, resource_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_sheet_dim' AND object_id = OBJECT_ID('dbo.uf_pa_sheet'))
    CREATE INDEX IX_pa_sheet_dim ON dbo.uf_pa_sheet(dimension_id, period_id);
GO

IF OBJECT_ID('dbo.uf_pa_sheet_item', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_sheet_item (
        id             BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id   BIGINT        NOT NULL,
        sheet_id       BIGINT        NOT NULL,
        item_id        BIGINT        NOT NULL,
        category_id    BIGINT        NULL,
        category_name  NVARCHAR(100) NULL,
        item_code      NVARCHAR(50)  NULL,
        item_name      NVARCHAR(100) NULL,
        definition     NVARCHAR(MAX) NULL,
        sort_no        INT           NOT NULL CONSTRAINT DF_pa_si_sort DEFAULT 0,
        weight         DECIMAL(9,4)  NULL,
        rater_leader   INT           NOT NULL CONSTRAINT DF_pa_si_rl DEFAULT 0,
        rater_360      INT           NOT NULL CONSTRAINT DF_pa_si_r3 DEFAULT 0,
        leader_score   DECIMAL(10,4) NULL,
        rater360_score DECIMAL(10,4) NULL,
        self_score     DECIMAL(10,4) NULL,
        item_score     DECIMAL(10,4) NULL,
        rater360_count INT           NOT NULL CONSTRAINT DF_pa_si_cnt DEFAULT 0,
        CONSTRAINT PK_uf_pa_sheet_item PRIMARY KEY (id)
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_sheet_item' AND object_id = OBJECT_ID('dbo.uf_pa_sheet_item'))
    CREATE UNIQUE INDEX UX_pa_sheet_item ON dbo.uf_pa_sheet_item(sheet_id, item_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_sheet_item_sheet' AND object_id = OBJECT_ID('dbo.uf_pa_sheet_item'))
    CREATE INDEX IX_pa_sheet_item_sheet ON dbo.uf_pa_sheet_item(sheet_id);
GO

-- rater_type：SELF（自评）/ LEADER（直接上级）/ RATER360（360 测评人）
-- 旧 CLIENT 退役：360 不再是"客户"，是发起人手工选的一组内部人员，语义已变。
-- level_name / score 是快照：等级被改分值后，历史评级仍按当时的刻度解释。
IF OBJECT_ID('dbo.uf_pa_rating', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_rating (
        id                BIGINT IDENTITY(1,1) NOT NULL,
        dimension_id      BIGINT       NOT NULL,
        sheet_id          BIGINT       NOT NULL,
        item_id           BIGINT       NOT NULL,
        rater_type        VARCHAR(10)  NOT NULL,
        rater_resource_id BIGINT       NOT NULL,
        level_id          BIGINT       NOT NULL,
        level_name        NVARCHAR(50) NULL,
        score             DECIMAL(6,2) NOT NULL,
        update_time       DATETIME2    NOT NULL CONSTRAINT DF_pa_rating_ut DEFAULT SYSDATETIME(),
        CONSTRAINT PK_uf_pa_rating PRIMARY KEY (id),
        CONSTRAINT CK_pa_rating_type CHECK (rater_type IN ('SELF', 'LEADER', 'RATER360'))
    );
END
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_rating' AND object_id = OBJECT_ID('dbo.uf_pa_rating'))
    CREATE UNIQUE INDEX UX_pa_rating ON dbo.uf_pa_rating(sheet_id, item_id, rater_type, rater_resource_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_rating_sheet' AND object_id = OBJECT_ID('dbo.uf_pa_rating'))
    CREATE INDEX IX_pa_rating_sheet ON dbo.uf_pa_rating(sheet_id);
GO

-- ------------------------------------------------------------
-- 8. 沿用的表（不动结构，仅 sql/51 清空数据）
-- ------------------------------------------------------------
--   uf_assess_task / uf_assess_task_rater：发起、链接、催办、撤回、草稿、评语
--     · rater_type 取值改为 SELF / LEADER / RATER360（见下方 §9 放宽 CHECK）
--     · sheet_id 语义改为指向 uf_pa_sheet
--   uf_assess_period        年度（唯一定义源，不变）
--   uf_assess_dimension     维度/租户（不变）
--   uf_assess_dimension_auth 维度授权（不变）
--   uf_assess_admin         管理员（不变）
--   uf_push_msg             消息队列（业务无关的通用设施，PushMsgJob 保留）

PRINT N'50-pa-schema 执行完成：uf_pa_post / uf_pa_category / uf_pa_item / uf_pa_item_level / uf_pa_band / uf_pa_sheet / uf_pa_sheet_item / uf_pa_rating';
GO

-- ------------------------------------------------------------
-- 9. uf_assess_task_rater.rater_type 放宽：加入 RATER360
-- ------------------------------------------------------------
-- 旧 CHECK CK_tr_type 只允许 SELF / CLIENT / LEADER。新模型的 360 测评人
-- 是发起人手工选的一组内部人员，与旧「客户评价人」语义不同，因此换类型名而不是沿用：
-- 沿用 CLIENT 会让报告页和消息文案继续叫「客户」，与新菜单「360 测评人」对不上。
-- 先删后加，幂等：重跑本段不会因为「约束已存在」失败。
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_tr_type'
           AND parent_object_id = OBJECT_ID('dbo.uf_assess_task_rater'))
    ALTER TABLE dbo.uf_assess_task_rater DROP CONSTRAINT CK_tr_type;
GO
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'CK_tr_type'
               AND parent_object_id = OBJECT_ID('dbo.uf_assess_task_rater'))
    ALTER TABLE dbo.uf_assess_task_rater WITH CHECK
        ADD CONSTRAINT CK_tr_type CHECK (rater_type IN ('LEADER', 'CLIENT', 'SELF', 'RATER360'));
GO

PRINT N'50-pa-schema 执行完成（含 rater_type 放宽）';
GO
