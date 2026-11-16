-- ============================================================
-- 37 建表：uf_assess_task（测评任务）
-- ------------------------------------------------------------
-- 依据：《15-评价网页化改造方案》§5.1
-- 说明：
--   · 一个任务 = 「某个年度、某维度、某被测人」的一次评价征集。
--   · 与 uf_assess_sheet 多对一：一张页面（一人一年度）可被多次发起
--     （撤回后重新发起），故任务独立成表。
--   · status 无「已截止」也无「待审核」（方案 Q3/§5.4 已定）：
--       0 进行中 / 2 已完成 / 3 已取消 / 4 已撤回
--   · 取消与撤回的区别：取消不动评级（保留已完成部分），撤回回退到评价初（§5.4）。
-- 与生产库一致性：remark=1000、void_reason=400、CK 允许 0/2/3/4。
-- 回滚：DROP TABLE dbo.uf_assess_task;
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.uf_assess_task', 'U') IS NULL
CREATE TABLE dbo.uf_assess_task (
    id                  BIGINT IDENTITY(1,1) NOT NULL,
    company_id          BIGINT        NOT NULL,
    dimension_id        BIGINT        NOT NULL,
    period_id           BIGINT        NOT NULL,   -- uf_assess_period.id
    sheet_id            BIGINT        NULL,       -- uf_assess_sheet.id，发起时同步生成
    resource_id         BIGINT        NOT NULL,   -- 被测人
    department_id       BIGINT        NULL,       -- 快照，岗位权重匹配用
    job_title_id        BIGINT        NULL,       -- 快照
    leader_resource_id  BIGINT        NULL,       -- 直接领导快照（可从页面头带出后修改）
    status              TINYINT       NOT NULL CONSTRAINT DF_task_status DEFAULT 0,
                                  -- 0-进行中 2-已完成 3-已取消 4-已撤回（无「已截止」）
    rating_count        INT           NOT NULL CONSTRAINT DF_task_rc DEFAULT 0,  -- 有效要素数快照
    client_count        INT           NOT NULL CONSTRAINT DF_task_cc DEFAULT 0,  -- 客户数快照
    remark              NVARCHAR(1000) NULL,
    create_by           BIGINT        NOT NULL,
    create_time         DATETIME2     NOT NULL CONSTRAINT DF_task_ct DEFAULT SYSDATETIME(),
    update_time         DATETIME2     NOT NULL CONSTRAINT DF_task_ut DEFAULT SYSDATETIME(),
    -- 撤回与替换链（见 §4.3）
    void_by             BIGINT        NULL,
    void_time           DATETIME2     NULL,
    void_reason         NVARCHAR(400) NULL,       -- 必填；会原样外发给评价人（见 §8.6.3）
    void_notice_json    NVARCHAR(MAX) NULL,       -- 撤回通知正文快照，便于事后核对
    replaced_by_task_id BIGINT        NULL,       -- 被哪个新任务替换
    CONSTRAINT PK_uf_assess_task PRIMARY KEY (id),
    CONSTRAINT CK_task_status CHECK (status IN (0,2,3,4))
);
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_task_sheet' AND object_id = OBJECT_ID('dbo.uf_assess_task'))
    CREATE INDEX IX_task_sheet ON dbo.uf_assess_task (sheet_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_task_dim' AND object_id = OBJECT_ID('dbo.uf_assess_task'))
    CREATE INDEX IX_task_dim ON dbo.uf_assess_task (company_id, dimension_id, period_id, status);
GO

-- 注意：生产库当前未建「同一 sheet 只允许一个进行中任务」的唯一过滤索引。
-- 该约束由服务层发起校验保证（见 §7.1.1「重复发起」）。若要下沉到库层，用：
--   CREATE UNIQUE INDEX UX_task_active_sheet ON dbo.uf_assess_task (sheet_id) WHERE status = 0;
-- 但历史数据若已存在重复进行中任务会建失败，故此处不建。

PRINT '--- uf_assess_task 结构 ---';
SELECT c.column_id, c.name, TYPE_NAME(c.user_type_id) AS type, c.max_length, c.is_nullable
FROM sys.columns c WHERE c.object_id = OBJECT_ID('dbo.uf_assess_task') ORDER BY c.column_id;
GO
