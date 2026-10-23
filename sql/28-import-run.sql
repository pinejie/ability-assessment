-- 28 一键导入流水线：批次表 uf_assess_import_run（方案 11 §4.1）
-- 数据库：SQL Server 2016 RTM。幂等。
-- run = 一次"上传→(自动/回环)校验→确认→写入"的批次；agent 三出口经 /agent/outcome 驱动状态机。
-- 轮次权威计数在 round_count（后端双保险）；写入仍复用 uf_assess_agent_draft + commitDraft。

IF OBJECT_ID('dbo.uf_assess_import_run', 'U') IS NULL
CREATE TABLE dbo.uf_assess_import_run (
    id             BIGINT IDENTITY(1,1) NOT NULL,
    company_id     BIGINT        NOT NULL,
    dimension_id   BIGINT        NULL,                 -- 建批未知，READY 时定
    year           INT           NULL,                 -- 同上（可被文件内容判定）
    session_id     NVARCHAR(64)  NOT NULL,             -- 内部会话（AgentSessionService）
    file_path      NVARCHAR(400) NOT NULL,
    file_name      NVARCHAR(200) NOT NULL,
    user_rules     NVARCHAR(MAX) NULL,                 -- 批次规则框原文
    status         VARCHAR(20)   NOT NULL CONSTRAINT DF_import_run_status DEFAULT 'DRAFT',
        -- DRAFT/PARSING/NEED_INPUT/EXHAUSTED/READY/WRITING/DONE/FAILED_RETRYABLE/REJECTED/STALE
        -- 终态追加：CHAT_ESC / MANUAL（出口A/B触发后本 run 关闭）
    round_count    INT           NOT NULL CONSTRAINT DF_import_run_round DEFAULT 0,   -- agent 主动提问轮数
    transcript_json NVARCHAR(MAX) NULL,                -- [{ask:[questions],answer:[…]}] 全量留档
    outcome_json   NVARCHAR(MAX) NULL,                 -- 最近一次 submit_outcome 原文
    draft_id       BIGINT        NULL,                 -- READY 后关联 agent_draft
    result_json    NVARCHAR(MAX) NULL,                 -- DONE 回执（逐人）
    fail_reason    NVARCHAR(500) NULL,
    prompt_version VARCHAR(20)   NOT NULL,
    created_by     BIGINT        NOT NULL,             -- 操作人 resourceId
    created_at     DATETIME2     NOT NULL CONSTRAINT DF_import_run_ct DEFAULT SYSDATETIME(),
    updated_at     DATETIME2     NOT NULL CONSTRAINT DF_import_run_ut DEFAULT SYSDATETIME(),
    CONSTRAINT PK_uf_assess_import_run PRIMARY KEY (id),
    CONSTRAINT CK_uf_assess_import_run_status CHECK (status IN
        ('DRAFT','PARSING','NEED_INPUT','EXHAUSTED','READY','WRITING','DONE',
         'FAILED_RETRYABLE','REJECTED','STALE','CHAT_ESC','MANUAL'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_import_run_session' AND object_id = OBJECT_ID('dbo.uf_assess_import_run'))
    CREATE INDEX IX_import_run_session ON dbo.uf_assess_import_run (session_id);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_import_run_created' AND object_id = OBJECT_ID('dbo.uf_assess_import_run'))
    CREATE INDEX IX_import_run_created ON dbo.uf_assess_import_run (company_id, created_by, id DESC);
GO
