-- 26 Agent 交付稿表（二期：智能导入）
-- 数据库：SQL Server 2016 RTM。幂等。
-- draft = cp_server deliver 落库的结构化稿；生命周期：READY → COMMITTED / EXPIRED。
-- 写入仍只经测评后端 /agent/drafts/{id}/commit（前端按钮点击=确认）→ 复用 sql/25 存储过程。
-- 过期策略（惰性判定，方案 §6）：闲置 30 分钟（以 last_seen 计，前端轮询刷新）或
-- 绝对上限 2 小时（ready_time 起）→ EXPIRED，写钥匙同时作废。

IF OBJECT_ID('dbo.uf_assess_agent_draft', 'U') IS NULL
CREATE TABLE dbo.uf_assess_agent_draft (
    id             BIGINT IDENTITY(1,1) NOT NULL,
    session_id     NVARCHAR(64)  NOT NULL,              -- zd-code 会话 ID
    company_id     BIGINT        NOT NULL,
    year           INT           NOT NULL,
    dimension_id   BIGINT        NOT NULL,              -- 交付时已解析（多维歧义在入库前阻断）
    payload_json   NVARCHAR(MAX) NOT NULL,              -- 严格 schema 的稿：rows[{kind,resourceId,elementId?,levelNo?,originalScore?,annualScore?,basis?}]
    status         VARCHAR(20)   NOT NULL CONSTRAINT DF_agent_draft_status DEFAULT 'READY',  -- READY/COMMITTED/EXPIRED
    ready_time     DATETIME2     NOT NULL CONSTRAINT DF_agent_draft_ready DEFAULT SYSDATETIME(),
    last_seen_time DATETIME2     NOT NULL CONSTRAINT DF_agent_draft_seen DEFAULT SYSDATETIME(),
    commit_time    DATETIME2     NULL,
    commit_by      BIGINT        NULL,                  -- 点击写入的登录人（HrmResource.id）
    commit_result  NVARCHAR(500) NULL,
    CONSTRAINT PK_uf_assess_agent_draft PRIMARY KEY (id),
    CONSTRAINT CK_uf_assess_agent_draft_status CHECK (status IN ('READY', 'COMMITTED', 'EXPIRED'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_agent_draft_session' AND object_id = OBJECT_ID('dbo.uf_assess_agent_draft'))
    CREATE INDEX IX_agent_draft_session ON dbo.uf_assess_agent_draft (session_id, id DESC);
GO
