-- ============================================================
-- 38 建表：uf_assess_task_rater（评价人任务）
-- ------------------------------------------------------------
-- 依据：《15-评价网页化改造方案》§5.2
-- 说明：
--   · 一个任务下挂 N 个评价人，每人一行，持有独立 access_token。
--   · 评价人只能发起时选定，不支持中途追加/替换（方案 Q1）。
--     选错的唯一纠正手段是「撤回 → 重新发起」。
--   · 客户评价人也是 HrmResource 人员（rater_resource_id），不是系统外的自由文本，
--     因为 usp_assess_rate / usp_assess_recalc 都按 rater_resource_id 分组计数。
--   · status：0-待评 / 1-已提交 / 3-已作废（随任务撤回）
--       无「已过期」（不设截止时间）、无「已驳回」（提交即终态，Q3 无审批）。
--   · draft_json 存草稿 {"59":3,"60":4}，提交后保留用于回显；
--     任务撤回时与 status 一并清空（§4.3.4 步骤 ⑥）。
--   · draft_version 用于多标签页并发保护：服务端比对后拒绝旧版本覆盖（§4.2.2）。
--   · access_token 明文存库（长度 43，Base64URL(32B)，VARCHAR(64) 足够）。
--     它是「入口」不是「身份」——防代填靠登录人 == rater_resource_id（§10.2）。
-- 回滚：DROP TABLE dbo.uf_assess_task_rater;
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.uf_assess_task_rater', 'U') IS NULL
CREATE TABLE dbo.uf_assess_task_rater (
    id                BIGINT IDENTITY(1,1) NOT NULL,
    task_id           BIGINT       NOT NULL,
    rater_resource_id BIGINT       NOT NULL,
    rater_type        VARCHAR(10)  NOT NULL,      -- LEADER / CLIENT / SELF
    status            TINYINT      NOT NULL CONSTRAINT DF_tr_status DEFAULT 0,
                                 -- 0-待评 1-已提交 3-已作废（任务撤回）；无「已过期」
    draft_json        NVARCHAR(MAX) NULL,        -- 草稿（要素ID → 等级），见 §4.2.2
    draft_time        DATETIME2    NULL,
    draft_version     INT          NOT NULL CONSTRAINT DF_tr_dv DEFAULT 0,  -- 草稿版本，防多标签页覆盖
    submit_time       DATETIME2    NULL,
    remind_count      INT          NOT NULL CONSTRAINT DF_tr_rc DEFAULT 0,
    last_remind_time  DATETIME2    NULL,
    access_token      VARCHAR(64)  NOT NULL,     -- 专属链接令牌
    create_time       DATETIME2    NOT NULL CONSTRAINT DF_tr_ct DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_task_rater PRIMARY KEY (id),
    CONSTRAINT CK_tr_type CHECK (rater_type IN ('LEADER','CLIENT','SELF')),
    CONSTRAINT CK_tr_status CHECK (status IN (0,1,3)),
    CONSTRAINT UQ_tr UNIQUE (task_id, rater_type, rater_resource_id)
);
GO

-- token 是评价页的唯一入口，必须唯一且能走索引
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_tr_token' AND object_id = OBJECT_ID('dbo.uf_assess_task_rater'))
    CREATE UNIQUE INDEX UX_tr_token ON dbo.uf_assess_task_rater (access_token);
GO

-- 「我的评价」列表：按人查任务态（§7.2.1，S2 使用）
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_tr_rater' AND object_id = OBJECT_ID('dbo.uf_assess_task_rater'))
    CREATE INDEX IX_tr_rater ON dbo.uf_assess_task_rater (rater_resource_id, status);
GO

PRINT '--- uf_assess_task_rater 结构 ---';
SELECT c.column_id, c.name, TYPE_NAME(c.user_type_id) AS type, c.max_length, c.is_nullable
FROM sys.columns c WHERE c.object_id = OBJECT_ID('dbo.uf_assess_task_rater') ORDER BY c.column_id;
GO
