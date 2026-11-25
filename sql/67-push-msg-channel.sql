-- ============================================================
-- 67 uf_push_msg 加推送渠道
-- ------------------------------------------------------------
-- 背景：这张表原来只服务泛微一个渠道（由 OA 侧 PushMsgJob 取走发送）。
-- 接入其它系统后，同一条业务消息可能要发到企微 / 钉钉 / 邮件，
-- 各渠道的发送器不同、运行位置也不同（泛微只能在 OA 里发，
-- 因为 Util_Message.sendAndpublishMessage 是 Java 静态方法，不是 HTTP 接口）。
--
-- 设计：队列共用一张表，靠 channel 分流。
--   · channel='weaver'  → OA 侧 PushMsgJob 取走（加一个 where 条件）
--   · channel<>'weaver' → 后端 ChannelDispatcher 取走
-- 两边各扫各的行，互不干扰；任一侧停机不影响另一侧。
--
-- 默认值 'weaver' 的含义：存量记录全部归到泛微渠道。
--   当前 24 条记录 status 均为 1（已发送），加列本身不影响它们；
--   即便将来有 status=0 的存量行，默认值也保证它仍由原来的发送器处理。
--   **不需要 UPDATE 存量数据。**
--
-- 为什么不拆成两张表（uf_push_msg / uf_push_msg_ext）：
--   一张队列才能统一回答「这个人这条消息到底发了没有」。
--   拆表后查询要 UNION，统计口径容易两边不一致 —— 而「消息是否送达」
--   恰恰是运维排查时最先被问的问题。
--
-- 幂等：列已存在时跳过。
-- 回滚：
--   DROP INDEX IX_pushmsg_channel_pending ON dbo.uf_push_msg;
--   ALTER TABLE dbo.uf_push_msg DROP CONSTRAINT DF_pushmsg_channel;
--   ALTER TABLE dbo.uf_push_msg DROP COLUMN channel;
-- ============================================================

USE ecology;
GO

IF NOT EXISTS (SELECT 1 FROM sys.columns
               WHERE object_id = OBJECT_ID('dbo.uf_push_msg') AND name = 'channel')
BEGIN
    ALTER TABLE dbo.uf_push_msg
        ADD channel VARCHAR(32) NOT NULL
            CONSTRAINT DF_pushmsg_channel DEFAULT 'weaver';
    PRINT 'uf_push_msg.channel 已添加（默认 weaver，存量记录自动归入）';
END
ELSE
    PRINT 'uf_push_msg.channel 已存在，跳过';
GO

-- 两侧发送器都是「按渠道捞一批待发」的形态，这个索引让筛选直接 seek。
-- 放在 (channel, status, id) 而不是 (status, id)：
-- 加渠道后 status=0 的行会散落在多个渠道里，先按 channel 收窄才有效。
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE name = 'IX_pushmsg_channel_pending' AND object_id = OBJECT_ID('dbo.uf_push_msg'))
BEGIN
    CREATE INDEX IX_pushmsg_channel_pending ON dbo.uf_push_msg (channel, status, id);
    PRINT 'IX_pushmsg_channel_pending 已创建';
END
ELSE
    PRINT 'IX_pushmsg_channel_pending 已存在，跳过';
GO

PRINT '67 完成：推送队列支持多渠道分流';

-- ------------------------------------------------------------
-- 2026-11-24 部署记录：本机 MCP 连接不允许 CREATE INDEX，
-- 上面那段索引可能未执行。人工确认并补建：
--   SELECT name FROM sys.indexes
--    WHERE name = 'IX_pushmsg_channel_pending' AND object_id = OBJECT_ID('dbo.uf_push_msg');
-- 若无结果，手工执行：
--   USE ecology;
--   CREATE INDEX IX_pushmsg_channel_pending ON dbo.uf_push_msg (channel, status, id);
-- 缺这个索引不影响正确性，只影响待发行变多后的扫描效率。
-- ------------------------------------------------------------
