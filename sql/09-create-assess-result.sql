-- 09 测评汇总页面（v2，替换 v1 的 uf_assess_period/subject/subject_item/subject_client）
-- 数据库：SQL Server 2016 RTM（13.0.1601.5）
-- 配置表（uf_assess_weight_ability / uf_assess_weight_assessor / uf_assess_level_score / uf_assess_grade_level）
-- 为全局配置，不按年份，由脚本 01-08 创建，本脚本不改动。

IF OBJECT_ID('dbo.uf_assess_subject_client', 'U') IS NOT NULL DROP TABLE dbo.uf_assess_subject_client;
IF OBJECT_ID('dbo.uf_assess_subject_item', 'U')   IS NOT NULL DROP TABLE dbo.uf_assess_subject_item;
IF OBJECT_ID('dbo.uf_assess_subject', 'U')        IS NOT NULL DROP TABLE dbo.uf_assess_subject;
IF OBJECT_ID('dbo.uf_assess_period', 'U')         IS NOT NULL DROP TABLE dbo.uf_assess_period;
GO

-- 年度（只用于人员能力要求和测评汇总页面；配置不按年）
CREATE TABLE dbo.uf_assess_period (
    id           BIGINT IDENTITY(1,1) NOT NULL,
    company_id   BIGINT NOT NULL,
    year         INT NOT NULL,
    status       INT NOT NULL CONSTRAINT DF_assess_period_status DEFAULT 0,   -- 0 进行中 1 已关闭
    create_time  DATETIME2 NOT NULL CONSTRAINT DF_assess_period_ct DEFAULT SYSDATETIME(),
    update_time  DATETIME2 NOT NULL CONSTRAINT DF_assess_period_ut DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_period PRIMARY KEY (id),
    CONSTRAINT UQ_uf_assess_period_year UNIQUE (company_id, year)
);
GO

-- 测评汇总页面头（每人每年度一条）
CREATE TABLE dbo.uf_assess_sheet (
    id                 BIGINT IDENTITY(1,1) NOT NULL,
    company_id         BIGINT NOT NULL,
    period_id          BIGINT NOT NULL,
    resource_id        BIGINT NOT NULL,                 -- 被测人
    department_id      BIGINT NULL,                     -- 人员信息（同步刷新）
    job_title_id       BIGINT NULL,
    leader_resource_id BIGINT NULL,                     -- 直接领导（钩子写入时带入，仅展示）
    status             INT NOT NULL CONSTRAINT DF_assess_sheet_status DEFAULT 0,      -- 0 待评价 1 评价中 2 已完成
    need_recalc        INT NOT NULL CONSTRAINT DF_assess_sheet_recalc DEFAULT 1,      -- 1 待重算
    client_count       INT NOT NULL CONSTRAINT DF_assess_sheet_cnt DEFAULT 0,
    standard_score     DECIMAL(10,2) NULL,
    leader_score       DECIMAL(10,2) NULL,
    client_score       DECIMAL(10,2) NULL,
    self_score         DECIMAL(10,2) NULL,
    actual_score       DECIMAL(10,2) NULL,
    score_rate         DECIMAL(10,2) NULL,              -- 得分率（%）
    grade_name         NVARCHAR(20) NULL,
    sync_time          DATETIME2 NULL,
    create_time        DATETIME2 NOT NULL CONSTRAINT DF_assess_sheet_ct DEFAULT SYSDATETIME(),
    update_time        DATETIME2 NOT NULL CONSTRAINT DF_assess_sheet_ut DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_sheet PRIMARY KEY (id),
    CONSTRAINT UQ_uf_assess_sheet_period_res UNIQUE (period_id, resource_id),
    CONSTRAINT FK_uf_assess_sheet_period FOREIGN KEY (period_id) REFERENCES dbo.uf_assess_period(id)
);
GO

-- 测评汇总页面行（每个要素一行，对齐 Excel 测评汇总 sheet）
CREATE TABLE dbo.uf_assess_sheet_row (
    id                BIGINT IDENTITY(1,1) NOT NULL,
    sheet_id          BIGINT NOT NULL,
    category_id       BIGINT NOT NULL,
    category_name     NVARCHAR(100) NULL,
    element_id        BIGINT NOT NULL,
    element_name      NVARCHAR(100) NULL,
    required_level_no INT NULL,                         -- 要求等级（来自人员能力要求）
    active            INT NOT NULL CONSTRAINT DF_assess_row_active DEFAULT 1,   -- 1 有效 0 已移除
    sort_no           INT NULL,
    category_weight   DECIMAL(10,4) NULL,               -- 类别权重（%），计算过程写入
    standard_score    DECIMAL(10,4) NULL,               -- S(e)，计算过程写入
    leader_level_no   INT NULL,
    leader_score      DECIMAL(10,4) NULL,
    client_avg_score  DECIMAL(10,4) NULL,
    self_level_no     INT NULL,
    self_score        DECIMAL(10,4) NULL,
    CONSTRAINT PK_uf_assess_sheet_row PRIMARY KEY (id),
    CONSTRAINT UQ_uf_assess_sheet_row UNIQUE (sheet_id, element_id),
    CONSTRAINT FK_uf_assess_sheet_row_sheet FOREIGN KEY (sheet_id) REFERENCES dbo.uf_assess_sheet(id)
);
GO

-- 评级明细（所有评价人统一存放；覆盖式写入）
CREATE TABLE dbo.uf_assess_sheet_rating (
    id                  BIGINT IDENTITY(1,1) NOT NULL,
    sheet_id            BIGINT NOT NULL,
    element_id          BIGINT NOT NULL,
    rater_type          VARCHAR(10) NOT NULL,           -- LEADER / CLIENT / SELF
    rater_resource_id   BIGINT NOT NULL,                -- SELF 时等于被测人
    client_seq          INT NULL,                       -- 仅 CLIENT：客户序号，显示为"客户N"
    level_no            INT NOT NULL,                   -- 1–5
    score               DECIMAL(10,4) NULL,             -- G(e, 评价人)，计算过程写入
    workflow_request_id BIGINT NULL,                    -- 最近一次写入的流程 ID（追溯用）
    update_time         DATETIME2 NOT NULL CONSTRAINT DF_assess_rating_ut DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_sheet_rating PRIMARY KEY (id),
    CONSTRAINT UQ_uf_assess_sheet_rating UNIQUE (sheet_id, element_id, rater_type, rater_resource_id),
    CONSTRAINT CK_uf_assess_rating_type CHECK (rater_type IN ('LEADER', 'CLIENT', 'SELF')),
    CONSTRAINT CK_uf_assess_rating_level CHECK (level_no BETWEEN 1 AND 5),
    CONSTRAINT FK_uf_assess_rating_sheet FOREIGN KEY (sheet_id) REFERENCES dbo.uf_assess_sheet(id)
);
GO

-- 操作日志
CREATE TABLE dbo.uf_assess_sheet_log (
    id                   BIGINT IDENTITY(1,1) NOT NULL,
    sheet_id             BIGINT NOT NULL,
    action               VARCHAR(20) NOT NULL,          -- GENERATE / SYNC / RATE / RECALC
    operator_resource_id BIGINT NULL,
    detail_json          NVARCHAR(MAX) NULL,
    create_time          DATETIME2 NOT NULL CONSTRAINT DF_assess_log_ct DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_sheet_log PRIMARY KEY (id),
    CONSTRAINT FK_uf_assess_sheet_log_sheet FOREIGN KEY (sheet_id) REFERENCES dbo.uf_assess_sheet(id)
);
GO
