# PushMsgJob — 通用消息推送定时任务

> 一个**与业务无关**的泛微消息推送工具。任何系统想发站内消息/移动端推送，
> 往 `uf_push_msg` 插一行即可，不用改任务代码。

---

## 一、这是什么

```
业务系统                          泛微 OA
    │                                │
    │ INSERT INTO uf_push_msg        │
    │ (receiver_id, msg_type,        │
    │  title, content, pc_url, …)    │
    │                                │
    ▼                                │
┌──────────────────┐                 │
│   uf_push_msg    │◄── 轮询 ────────┤  PushMsgJob
│   status = 0     │                 │  （泛微后台配周期）
└──────────────────┘                 │
                     ┌───────────────┘
                     ▼
        Util_Message.sendAndpublishMessage
                     ▼
              回写 status = 1 / 2
```

**职责边界**（2026-11-13 重构的核心结论）：

| 谁 | 负责什么 |
|---|---|
| **入队侧**（各业务系统） | 这条消息**该不该插**、发给谁、内容是什么 —— 所有业务判断 |
| **任务**（本类） | 取未发记录 → 验接收人 → 发 → 回写状态 |

任务不知道什么是测评、合同、报销。唯一的"业务"是查 `HrmResource` 确认收件人还在职 ——
这是 OA 通用能力，不属于任何具体业务。

---

## 二、为什么这么设计

初版把「测评任务是否已撤回」「评价人是否已提交」写进了任务里，约 100 行。
那些判断换一个业务就全部作废，所以拆出来了：

- **好处**：新增一种提醒 = 插一行数据，零代码改动
- **代价**：任务不再帮忙判断"消息是否还该发"。业务方必须在**入队时**判断好

后者是刻意的。消息是"入队快照"，从入队到真正发出可能隔几小时（开关关闭、OA 停机、积压）。
如果业务状态在这期间变了，**入队侧应当自己决定要不要把消息改掉或删掉**，而不是指望发送任务回查。

---

## 三、表结构

```sql
CREATE TABLE dbo.uf_push_msg (
    id          BIGINT IDENTITY(1,1) NOT NULL,
    receiver_id INT            NOT NULL,   -- HrmResource.id，一人一行
    msg_type    INT            NOT NULL,   -- ECOLOGY_MESSAGE_TYPE.typecode
    title       NVARCHAR(200)  NOT NULL,
    content     NVARCHAR(1000) NULL,
    pc_url      VARCHAR(500)   NULL,
    mobile_url  VARCHAR(500)   NULL,
    status      TINYINT        NOT NULL DEFAULT 0,   -- 0未发送 1已发送 2异常
    last_error  NVARCHAR(500)  NULL,
    create_time DATETIME2      NOT NULL DEFAULT SYSDATETIME(),
    sent_time   DATETIME2      NULL
);
CREATE INDEX IX_pushmsg_pending ON dbo.uf_push_msg (status, id);
```

建表脚本：`sql/40-create-push-msg.sql`

### 为什么 receiver_id 是一人一行

泛微的 `createMessage(MessageType, Set<String> userList, …)` 支持一次发多人。
但**消息链接通常每人不同**（比如带专属 token 的评价链接），
合并成一条就只能共用一个链接，除第一个人之外全部打不开。

所以：**多人提醒就插多行**，一行的 `receiver_id` 是单个 `HrmResource.id`。

### status 语义

| 值 | 含义 | 说明 |
|---|---|---|
| 0 | 未发送 | 任务每轮扫这个状态 |
| 1 | 已发送 | 已回写 `sent_time` |
| 2 | 异常 | 原因在 `last_error`，**不自动重试** |

没有"已放弃"状态。失败后由人工判断：修好问题把 `status` 改回 0 即会重发。

---

## 四、部署

### 1. 建表

```bash
DB_USER=xxx DB_PASSWORD=xxx node sql/run-ddl.js 40-create-push-msg.sql
```

### 2. 配置消息类型

在泛微后台 **消息中心 → 消息类型** 里确认目标类型已注册，记下 `typecode`。
本任务不写死类型 —— 每行数据的 `msg_type` 决定用哪个。

测评使用**专属类型「能力测评提醒」`typecode = 596`**（`ECOLOGY_MESSAGE_TYPE` 中
`id=596 / SOURCENAME=能力测评提醒 / ecology_message_groupid=18`，2026-11-14 由 2505 创建）。

历史：在此之前测评借用的是泛微内置的「自定义提醒」`typecode = 36`，副作用是测评消息
落到「流程阅知提醒」分类下（36 的 `SOURCENAME` 就是这个，分组 groupid=2 流程组），
和流程阅知、合同类消息混在一起。2026-11-14 专属类型建好后已切换，
改的是 `AssessPushService.MSG_TYPE_ASSESS`，只需改这一处常量。

> 切换只影响之后新入队的消息；`uf_push_msg` 里历史 `msg_type=36` 的行保持原样。

### 3. 配置消息分组（本环境已配好）

**消息中心 → 移动端配置**（表 `ECOLOGY_BIZ_MOBILE_CONFIG`），
确保 `MESSAGETYPEID` 列包含你的 typecode（格式 `,596,`）。

**本环境 2026-11-14 实查**：596 对应的行已由后台建类型时自动生成 ——
`id=Yp6EAWg2, NAME=能力测评提醒, MESSAGETYPEID=',596,'`，`ENABLE/ENABLEPC/SENDMOBILE` 均为 `y`，分组就绪。
（对照：国贸采购提醒是 `id=7cBQ30c3, MESSAGETYPEID=',547,'`。）

> ⚠️ 分组 ID **不等于** 类型 ID。任务按 `MESSAGETYPEID LIKE '%,596,%'` 反查，
> 不能拿 typecode 直接当分组 ID 去 join。换类型时这张表要跟着加一项，否则
> 站内消息能收到、手机端收不到（或干脆不发移动推送）。

### 4. 确认消息开关（本环境已验证开启）

`weaver_message_center.properties`：

```properties
InnerMessage=1     # 必须。为 0 时消息被静默丢弃
PublishMessage=1   # 移动端快捷推送需要
```

> ⚠️ **这是最要命的一个坑**：`Util_Message.sendMessage` 在 `if (isInnerEnable())`
> 之外直接 `return true`。开关没开时消息**静默丢失**，但返回值仍是 `true`。
> 任务开头会自检这个开关，没开会直接返回并记 error 日志，不会把消息标成已发。

**本环境（ecology @ 172.18.28.108）2026-11-14 实测**：开关均为开启状态。
判据不是读配置文件（容器里改没改看不出来），而是看有没有消息真的落地 ——
`ECOLOGY_MESSAGE_INFO_READ` 近两日仍有 typecode 21/23/25/36/217/500/547 的归档记录，
其中 36 就是测评在用的类型。开关关闭时这张表不会有新行。

所以这一步在新环境部署时是**检查项**，本环境可以跳过。

### 5. 注册定时任务（本环境已完成）

泛微后台 **系统管理 → 定时任务**，新建：

| 项 | 值 |
|---|---|
| 类名 | `com.engine.interfaces.sj.zhongda.job.push.PushMsgJob` |
| 基类 | `weaver.interfaces.schedule.BaseCronJob` |
| 周期 | 建议 1 分钟（无消息时只走一次索引 seek） |

**本环境 2026-11-14 实测已完成**：`uf_push_msg` 7 条全部 `status=1`，
最近一次 `sent_time = 2026-11-14 00:24`（数据库时间），对应 `ECOLOGY_MESSAGE_INFO_READ` 里
typecode 36 的「能力测评 · 请对 … 进行客户评价」等标题。任务在跑。

---

## 五、怎么用（给其他系统）

插一行就行了：

```sql
INSERT INTO uf_push_msg (receiver_id, msg_type, title, content, pc_url, mobile_url)
VALUES (2505, 36, '报销单待审批', '您有 1 张报销单待审批，请及时处理。',
        '/spa/workflow/static4form/index.html#/main/workflow/req?requestid=123',
        '/mobile/plugin/1/workflow/view.jsp?requestid=123');
```

任务下一轮就会发出，并回写 `status=1` 与 `sent_time`。

**唯一需要业务方自己判断的**：如果这条消息在发出前已经失效（单据被撤销了），
入队侧应当自己删掉它或改状态 —— 任务不会替你判断。

---

## 六、故障排查

| 现象 | 排查 |
|---|---|
| 消息一直 `status=0` | ① `InnerMessage` 开关；② 定时任务是否注册/启用；③ 看 OA 日志 `[PushMsgJob]` |
| `status=2`，`last_error` 是"人员状态为 5" | 接收人已离职，换人或删掉这条 |
| `status=2`，`last_error` 是"没有 OA 登录名" | 泛微里补登录名 |
| `status=2`，`last_error` 是"消息类型 N 未配置" | 消息中心里没有注册该 typecode |
| 站内消息收到，手机收不到 | `PublishMessage=1`；分组配置 `SENDMOBILE`；EM 应用 `AGENTID` |
| 用户说没收到，但表里 `status=1` | 查 `sent_time`；再查 `ECOLOGY_MESSAGE_INFO_READ` 是否有记录 |

日志前缀统一为 `[PushMsgJob]`。

---

## 七、类信息

| 项 | 值 |
|---|---|
| 类名 | `com.engine.interfaces.sj.zhongda.job.push.PushMsgJob` |
| 基类 | `weaver.interfaces.schedule.BaseCronJob` |
| 主表 | `uf_push_msg` |
| 依赖 | `Util_Message`、`MessageBean`、`MessageType`（位于 `ecology/WEB-INF/lib` 的 cloudstore 开发包） |

cloudstore 是泛微云商店模块的包。本环境已具备 —— 证据不是查文件，是任务真的发出去了：
7 条推送全部 `status=1`，泛微归档表里能看到对应的测评标题。类缺失的话
`Util_Message` 一加载就 `NoClassDefFoundError`，不可能有 `status=1`。

换到精简部署如果真缺这个包，才需要改用 `weaver.messagecenter` 下的等价 API。
判断方法：编译一下，报 `程序包 com.cloudstore.dev.api.util 不存在` 就是缺。
