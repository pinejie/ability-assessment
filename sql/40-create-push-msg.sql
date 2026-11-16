-- ============================================================
-- 40 建表：uf_push_msg（通用消息队列表）
-- ------------------------------------------------------------
-- 定位：这是一个**与业务无关**的发送队列。任何系统想发泛微站内消息/移动端推送，
--       往这张表插一行即可，由泛微侧定时任务 PushMsgJob 轮询发出。
--
-- 为什么与测评解耦（2026-11-13）：
--   初版表叫 uf_assess_push_msg，字段里带 biz_type/biz_key，定时任务里塞了 100 行
--   「任务是否已撤回/评价人是否已提交」的判定 —— 那些是测评业务语义，换一个系统就全部作废。
--   现在把这些判断挪回各自的**入队侧**：谁插消息谁负责判断该不该插。
--   任务只做三件事：取未发记录、验证接收人还能不能收、发出去并回写状态。
--
-- 字段说明：
--   · receiver_id  一人一行。多人提醒就插多行。
--                  不合并成逗号串的原因：泛微 createMessage(Set<String> userList)
--                  虽然支持一次发多人，但消息链接往往是「每人一个」的（如带专属 token），
--                  合并后只能共用一个链接，除第一个之外全部打不开。
--   · msg_type     泛微消息类型 typecode（ECOLOGY_MESSAGE_TYPE.typecode）。
--                  允许每行不同，任务按 typecode 缓存分组配置。
--                  不能写死：消息中心后台配置的类型编号因环境而异。
--   · status       0-未发送 / 1-已发送 / 2-异常
--                  没有「已放弃」：重试由人工决定，任务不做自动放弃 ——
--                  提醒类消息宁可多发一次，也不能悄悄丢掉。
--   · last_error   异常原因，写到表里而不是只留日志，运维不登服务器也能看。
--   · create_time  入队时间；sent_time 实际发出时间。
--                  排查「用户说没收到」时，这两个时间戳是第一手证据。
--
-- 语义：**至少发送一次**。任务取 status=0 → 发送 → 回写 1。
--       进程中途被杀则该行仍为 0，下轮重发。极端情况会重复，但不会丢。
--
-- 回滚：DROP TABLE dbo.uf_push_msg;
-- ============================================================

USE ecology;
GO

-- ------------------------------------------------------------
-- 先删旧表。必须在新表之前：两者的约束名曾经重名
-- （DF_push_status / CK_push_status / DF_push_ct），先建新表会撞名失败。
-- 旧表从未真正发送过（OA 侧任务未部署，全部 status=0），且仍在开发测试阶段，
-- 直接删除不做数据迁移。
-- ------------------------------------------------------------
IF OBJECT_ID('dbo.uf_assess_push_msg', 'U') IS NOT NULL
    DROP TABLE dbo.uf_assess_push_msg;
GO

IF OBJECT_ID('dbo.uf_push_msg', 'U') IS NULL
CREATE TABLE dbo.uf_push_msg (
    id          BIGINT IDENTITY(1,1) NOT NULL,
    receiver_id INT            NOT NULL,       -- HrmResource.id，一人一行
    msg_type    INT            NOT NULL,       -- ECOLOGY_MESSAGE_TYPE.typecode
    title       NVARCHAR(200)  NOT NULL,
    content     NVARCHAR(1000) NULL,
    pc_url      VARCHAR(500)   NULL,           -- PC 端链接
    mobile_url  VARCHAR(500)   NULL,           -- 移动端链接
    status      TINYINT        NOT NULL CONSTRAINT DF_pushmsg_status DEFAULT 0,
                                               -- 0-未发送 1-已发送 2-异常
    last_error  NVARCHAR(500)  NULL,
    create_time DATETIME2      NOT NULL CONSTRAINT DF_pushmsg_ct DEFAULT SYSDATETIME(),
    sent_time   DATETIME2      NULL,
    CONSTRAINT PK_uf_push_msg PRIMARY KEY (id),
    CONSTRAINT CK_pushmsg_status CHECK (status IN (0, 1, 2))
);
GO

-- 任务每轮按 status=0 ORDER BY id 取一批，这个索引让它直接 seek
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'IX_pushmsg_pending' AND object_id = OBJECT_ID('dbo.uf_push_msg'))
    CREATE INDEX IX_pushmsg_pending ON dbo.uf_push_msg (status, id);
GO
