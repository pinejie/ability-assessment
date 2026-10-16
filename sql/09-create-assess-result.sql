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

-- ============================================================
-- 表及字段描述（MS_Description）
-- 脚本开头已 DROP 上述表，扩展属性随表删除，此处直接新增，无需判重。
-- ============================================================

-- uf_assess_period：测评年度
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'测评年度表：每个公司每个年度一条，控制该年度测评汇总页面的开启与关闭。仅用于测评汇总页面，配置表不按年份。',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'主键ID（自增）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'公司ID，当前固定为 1',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'company_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'测评年度，如 2026',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'year';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'年度状态：0 进行中，1 已关闭（关闭后拒绝写入，报告快照冻结）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'status';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'创建时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'create_time';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'更新时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_period', @level2type = N'COLUMN', @level2name = N'update_time';
GO

-- uf_assess_sheet：测评汇总页面头
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'测评汇总页面头表：每位被测人每个年度一条，保存页面得分、等级与状态。得分与等级由存储过程计算写入。',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'主键ID（自增）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'公司ID，当前固定为 1',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'company_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'所属测评年度ID，关联 uf_assess_period.id',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'period_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'被测人ID（HrmResource.id）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'resource_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'被测人所属部门ID（人员信息，同步刷新）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'department_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'被测人岗位ID（人员信息，同步刷新）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'job_title_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'直接领导ID。钩子写入评级时带入，仅用于展示，不参与计算',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'leader_resource_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'页面状态：0 待评价，1 评价中，2 已完成',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'status';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'是否待重算：1 待重算，0 已是最新结果',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'need_recalc';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'客户评价人数',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'client_count';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'标准总分（各要素标准分之和）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'standard_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'直接领导评价总分',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'leader_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'客户评价总分',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'client_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'自评总分',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'self_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'实际总分（按角色权重汇总后的最终得分）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'actual_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'得分率（%）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'score_rate';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'等级名称，如 需提升、基本胜任、胜任、优秀、卓越',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'grade_name';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'最近一次与人员信息同步的时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'sync_time';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'创建时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'create_time';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'更新时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet', @level2type = N'COLUMN', @level2name = N'update_time';
GO

-- uf_assess_sheet_row：测评汇总页面行
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'测评汇总页面行表：页面中每个有效要素一行，对齐 Excel 测评汇总 sheet。要素与要求等级来自人员能力要求，得分由计算过程写入。',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'主键ID（自增）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'所属页面ID，关联 uf_assess_sheet.id',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'sheet_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'类别ID',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'category_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'类别名称（冗余存储，便于展示）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'category_name';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'要素ID。钩子 ratings_json 中的 elementId 对应此字段',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'element_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'要素名称（冗余存储，便于展示）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'element_name';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'要求等级（1–5），来自人员能力要求',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'required_level_no';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'是否有效：1 有效，0 已移除（移除后不参与计算与评级）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'active';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'排序号',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'sort_no';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'类别权重（%），计算过程写入',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'category_weight';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'要素标准分 S(e)，计算过程写入',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'standard_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'直接领导评定等级（1–5）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'leader_level_no';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'直接领导单项得分',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'leader_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'客户平均单项得分（多位客户取平均）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'client_avg_score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'自评等级（1–5）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'self_level_no';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'自评单项得分',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_row', @level2type = N'COLUMN', @level2name = N'self_score';
GO

-- uf_assess_sheet_rating：评级明细
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'评级明细表：所有评价人的评级统一存放。一行 = 一个评价人对一个要素的评级，同一评价人重复写入为覆盖。',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'主键ID（自增）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'所属页面ID，关联 uf_assess_sheet.id',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'sheet_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'要素ID，关联 uf_assess_sheet_row.element_id',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'element_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'评价类型：LEADER 直接领导 / CLIENT 服务客户 / SELF 自评',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'rater_type';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'评价人ID（HrmResource.id）。SELF 时等于被测人ID',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'rater_resource_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'客户序号，仅 CLIENT 有值，显示为"客户N"。由存储过程分配，钩子不传',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'client_seq';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'评级等级，取值 1–5（1 知识级 … 5 变通级）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'level_no';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'单项得分 G(e, 评价人)，计算过程写入',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'score';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'最近一次写入的泛微流程请求ID，用于追溯',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'workflow_request_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'最近更新时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_rating', @level2type = N'COLUMN', @level2name = N'update_time';
GO

-- uf_assess_sheet_log：操作日志
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'测评汇总页面操作日志表：记录页面生成、同步、评级、重算等操作，用于审计与追溯。',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'主键ID（自增）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'所属页面ID，关联 uf_assess_sheet.id',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'sheet_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'操作类型：GENERATE 生成页面 / SYNC 同步人员信息 / RATE 评级 / RECALC 重算',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'action';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'操作人ID（HrmResource.id），系统操作可为空',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'operator_resource_id';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'操作详情（JSON 格式）',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'detail_json';
EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'操作时间',
     @level0type = N'SCHEMA', @level0name = N'dbo', @level1type = N'TABLE', @level1name = N'uf_assess_sheet_log', @level2type = N'COLUMN', @level2name = N'create_time';
GO
