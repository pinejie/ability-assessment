-- ============================================================
-- 55 建表：能力结构导入会话（uf_pa_import_session）
-- ------------------------------------------------------------
-- 依据：能力结构导入方案（2026-11-21 口径确认完毕）
--
-- 为什么落一张表，而不是放内存：
--   · 需求「切到别的页面再切回来还要能看到」——内存会话在多实例/重启后全丢
--   · 模型单次调用实测 18~25 秒，必须异步；异步任务态需要一个跨请求的载体
--   · 复用 hr-report 的做法（uf_hr_ana_query + GET /ask/{id} 轮询 + 启动兜底），
--     那里已验证过「任务态落库 + 前端轮询 + 进程重启标记失败」这一套是够用的
--
-- 状态机（status）：
--   PARSING     正在读 Excel
--   CHECKING    正在识别结构 / 计算待确认项 / 校验回答
--   NEEDS_CONFIRM 有需要用户确认的问题（questions 非空）
--   READY       没有疑问了，等用户点「确认导入」
--   APPLYING    正在写入
--   DONE        写入完成
--   FAILED      本次会话失败（error 有原因）
--   MANUAL      追问超过安全阀，转手工映射
--   EXPIRED     超过 30 分钟无交互，由定时任务标记
--
-- 过期语义：expire_time = update_time + 30 分钟，每次交互都会刷新。
--   过期后清空 payload / mapping / dialog 这些大字段（保留一行瘦记录便于查问题），
--   不物理删除 —— 「上次导入到底卡在哪」这种问题，删了就没法回溯了。
--
-- 隔离：带 dimension_id，已登记进 MyBatisPlusConfig.TENANT_TABLES。
--   注意 resource_id 也参与鉴权：只能看自己发起的会话（会话里带整份文件内容）。
--
-- 幂等：建表用 IF OBJECT_ID(...) IS NULL，建索引单独判 sys.indexes。
-- 回滚：DROP TABLE dbo.uf_pa_import_session（会话是临时态，删掉不影响业务数据）。
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.uf_pa_import_session', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_pa_import_session (
        id            BIGINT IDENTITY(1,1) NOT NULL,
        -- 对外的会话号（UUID）：id 自增可枚举，前端拿 id 去猜别人的会话不该成立
        session_key   VARCHAR(64)    NOT NULL,
        dimension_id  BIGINT         NOT NULL,
        -- 发起人 HrmResource.id：会话里带整份文件内容，不能跨人可见
        resource_id   BIGINT         NOT NULL,

        status        VARCHAR(20)    NOT NULL,
        -- 阶段文案（中文，直接给前端展示：「识别表格布局」「写入能力项 3/8」）
        stage         NVARCHAR(100)  NULL,

        file_name     NVARCHAR(255)  NULL,
        file_size     BIGINT         NULL,

        -- 解析出的原始表格结构（每 sheet 的每行每列文本 + 合并区域），模型只读这个
        payload       NVARCHAR(MAX)  NULL,
        -- 模型认出的列映射（统一结构，不含任何排版概念）
        mapping       NVARCHAR(MAX)  NULL,
        -- 当前这一轮待确认项清单
        pending       NVARCHAR(MAX)  NULL,
        -- 对话留痕：问 → 用户原话 → 模型抽出值 → 代码校验 → 最终落值
        dialog        NVARCHAR(MAX)  NULL,
        -- 校验预览（三类写入分支 + 三类测评分支的后果）
        preview       NVARCHAR(MAX)  NULL,
        -- 执行结果（岗位/类别/能力项/等级/区间计数 + 逐 sheet 决策）
        result        NVARCHAR(MAX)  NULL,
        -- 覆盖前旧模型快照，供页面下载留档
        snapshot      NVARCHAR(MAX)  NULL,

        error         NVARCHAR(1000) NULL,
        -- 已追问轮次：安全阀用（连续 10 轮还有未解决项 → MANUAL）
        round_no      INT            NOT NULL CONSTRAINT DF_pa_is_round DEFAULT 0,
        -- 进度（0-100），大循环里推进度用
        progress      INT            NOT NULL CONSTRAINT DF_pa_is_prog DEFAULT 0,

        create_by     BIGINT         NULL,
        create_time   DATETIME2      NOT NULL CONSTRAINT DF_pa_is_ct DEFAULT SYSDATETIME(),
        update_time   DATETIME2      NOT NULL CONSTRAINT DF_pa_is_ut DEFAULT SYSDATETIME(),
        expire_time   DATETIME2      NOT NULL,

        CONSTRAINT PK_uf_pa_import_session PRIMARY KEY (id)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'UX_pa_import_session_key' AND object_id = OBJECT_ID('dbo.uf_pa_import_session'))
    CREATE UNIQUE INDEX UX_pa_import_session_key ON dbo.uf_pa_import_session(session_key);
GO
-- 「进页面还原本人的会话」：按人 + 维度倒序取最新一条
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_import_session_owner' AND object_id = OBJECT_ID('dbo.uf_pa_import_session'))
    CREATE INDEX IX_pa_import_session_owner ON dbo.uf_pa_import_session(dimension_id, resource_id, update_time DESC);
GO
-- 过期清理任务扫这里
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pa_import_session_expire' AND object_id = OBJECT_ID('dbo.uf_pa_import_session'))
    CREATE INDEX IX_pa_import_session_expire ON dbo.uf_pa_import_session(status, expire_time);
GO

PRINT '55-create-pa-import-session.sql 执行完成';
GO
