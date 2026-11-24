-- ============================================================
-- 60 建表：360 测评人按能力项指派（uf_assess_task_rater_item）
-- ------------------------------------------------------------
-- 需求（2026-11-23）：发起测评时，选定岗位后把「配了 360 主体的能力项」逐项列出来，
-- 每一项单独选测评人。原来是「一批 360 评全部 360 项」—— 一个部门的 8 个能力项，
-- 只有其中 2 项需要 360，却要让同一批人去评全部，等于把不相干的人拉进来凑数。
--
-- 为什么单独一张表而不是在 uf_assess_task_rater 上加列：
--   一个 360 常常同时被指派到多个能力项（「沟通」「协作」都让同一个人评），
--   加列就得存成逗号串（不可查、不可加唯一约束），或者一人发多条 rater 行
--   （同一人多条链接、多条消息、进度条按项重复计数）。一人一条链接仍然是对的，
--   所以「人 ↔ 项」只能是一张多对多关系表。
--
-- 唯一约束 UX_rater_item (rater_id, item_id)：同一人被重复指派同一项时幂等，
--   也挡住代码里「名单里同一个人出现两次」的写法错误。
-- 不加 (task_id, item_id) 的约束：一个能力项本来就该有多个人评。
--
-- 与旧数据的关系：**存量任务没有本表的行**。填写侧据此回退到原口径
--   （rater360 身份 = 页面行快照 rater_360 = 1 的全部项），
--   所以本脚本执行前后，已发出的任务行为完全一致，不需要回填。
--   回填是错的：老任务当初就是「这批人评全部项」，凭空补行只会把当时的默契改写掉。
--
-- 幂等：建表与唯一索引都先判存在。
-- 回滚：DROP TABLE dbo.uf_assess_task_rater_item;
--       回滚后新任务会退化为「全部 360 项」，填写侧兼容分支照常工作。
-- ============================================================

USE ecology;
GO

IF OBJECT_ID('dbo.uf_assess_task_rater_item', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.uf_assess_task_rater_item (
        id          BIGINT IDENTITY(1,1) NOT NULL,
        -- 冗余 task_id：报告和撤回都按任务过滤，走 (task_id, rater_id) 比先查 rater 再关联短一层；
        -- 正确性由写入侧保证（rater_id 必须属于该 task_id）
        task_id     BIGINT NOT NULL,
        -- uf_assess_task_rater.id
        rater_id    BIGINT NOT NULL,
        -- uf_pa_item.id（项的身份用岗位能力项 ID，与 uf_pa_sheet_item.item_id 同一口径）
        item_id     BIGINT NOT NULL,
        create_time DATETIME2 NOT NULL DEFAULT SYSDATETIME(),

        CONSTRAINT PK_uf_assess_task_rater_item PRIMARY KEY CLUSTERED (id)
    );
    PRINT N'已建表 dbo.uf_assess_task_rater_item';
END
ELSE
BEGIN
    PRINT N'表 dbo.uf_assess_task_rater_item 已存在，跳过建表';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('dbo.uf_assess_task_rater_item') AND name = 'UX_rater_item')
BEGIN
    CREATE UNIQUE INDEX UX_rater_item ON dbo.uf_assess_task_rater_item (rater_id, item_id);
    PRINT N'已建唯一索引 UX_rater_item (rater_id, item_id)';
END
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('dbo.uf_assess_task_rater_item') AND name = 'IX_ri_task')
BEGIN
    CREATE INDEX IX_ri_task ON dbo.uf_assess_task_rater_item (task_id, item_id);
    PRINT N'已建索引 IX_ri_task (task_id, item_id)';
END
GO

PRINT N'60-add-pa-rater-item 执行完成：360 测评人可按能力项指派（存量任务不回填，走兼容口径）';
GO
