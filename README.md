# 能力测评系统

> 岗位能力模型 + 测评任务 + 个人测评报告，与泛微 e-cology 平台集成

## 功能概览

> 2026-11-21 方案 17（岗位能力模型重构）P3 已下线旧模型：能力类别 / 能力要素 / 能力要素等级 / 组织能力要求 /
> 岗位能力要求 / 人员能力要求 / 能力测评配置 / 外部评分导入 / 智能导入，全部前后端代码、页面路由、
> 旧 `uf_*` 表（`sql/51`）与 8 个 `usp_assess_*` 存储过程均已删除。以下为现役模块。

- **岗位管理**：部门（泛微部门树）+ 岗位名/编码/状态/排序，列出能力项数与权重合计
- **岗位能力模型**：按 部门+岗位 各自一套能力类别与能力项（名称/定义/权重/评级主体/排序），
  能力项等级动态配置（名称/分值/排序/描述），分值区间按岗位定义
- **测评任务**：发起（自评 / 直接上级 / 360 测评人）、任务管理（进度、催办、撤回）、
  token 链接填写页（PC + 移动端）
- **个人测评报告**：只读展示算分结果 —— 能力项得分、总得分（加权平均）、逐人分段评语（实时读，不等全员提交）；**分数统一成「分数 · 描述」**（头部总得分、明细表合计行、明细行三处同一套写法，描述全部取岗位分值区间如「专家 / 能手」那一套，`PaBandScale` 与算分侧共用同一份判档逻辑；原明细行的项刻度「开创/专家」已不再显示，接口仍返回；PC 抽屉与 H5 页口径一致）；**HR 确认封存后本人 / 直接上级才看得到**，封存会冻结内容（解除封存立刻又看不到）；**全部评价人（含自评）提交完才允许封存**，未评完时按钮置灰并写明还差几人；
  测评人按「测评人1（直接上级）/测评人2..N（360）」编号，姓名仅维度/系统管理员可见
- **我的测评**：员工本人入口（OA 门户/消息链接，PC + 手机）——两个 tab：「我参与的测评」= 自己的待办，「我的测评报告」= 自己的测评结果；**有直接下属的人多一个「下属的测评报告」tab**（只读、不含测评人姓名、只认 `managerid` 的直接下属）；手机版报告按能力项成卡，评级小块横排
- **泛微组织数据**：分公司、部门、岗位、人员的只读选择器（直接查询泛微 `Hr*` 表）

**算分口径**（唯一实现：`PaScoreServiceImpl`）：能力项得分 = 直接上级 + 360 测评人的算术平均（未评者不进分母）；
自评可见但不参与计算；总得分 = `Σ(能力项得分 × 权重) / Σ(权重)`；档位按 `[lower, upper)` 命中得出等级文案。

## 技术栈

**后端**
- Java 21
- Spring Boot 3.2
- MyBatis-Plus 3.5
- SQL Server（与泛微共用数据库）
- JWT（jjwt 0.12）+ 泛微 OAuth2 单点登录（已实现）

**前端**
- Vue 3 + TypeScript
- Element Plus
- Vite 5
- Vue Router 4、Pinia、Axios

## 项目结构

```
ability-assessment/
├── backend/     # 后端（Spring Boot）
├── frontend/    # 前端（Vue 3）
├── sql/         # 建表/运维脚本（按序号执行；50 新模型建表、51 旧模型清理、52 停用旧流程钩子）
├── docs/        # 补充文档
├── CLAUDE.md    # 项目指令（面向 Claude Code）
└── README.md
```

## 环境要求

- JDK 21
- Maven 3.6+（本机位于 `/home/wgd/apache-maven-3.9.16/bin/mvn`，不在 PATH）
- Node.js 18+ 与 pnpm（本机 pnpm 位于 `~/.nvm/versions/node/v20.20.2/bin/pnpm`，不在 PATH）
- SQL Server（可访问泛微 `ecology` 库）

## 快速开始

### 1. 初始化数据库

**全新部署只跑这 8 支**（其余 01–49 服务于已下线的旧模型，新库不必执行）：

```
sql/09-create-assess-result.sql      # uf_assess_period 年度表
sql/14-create-assess-dimension.sql   # 维度（类租户）与授权表
sql/21-create-assess-admin.sql       # 管理员名单
sql/24-unique-active-period.sql      # 年度「进行中」筛选唯一索引
sql/37-create-assess-task.sql        # uf_assess_task 测评任务
sql/38-create-assess-task-rater.sql  # uf_assess_task_rater 评价人
sql/40-create-push-msg.sql           # 泛微站内消息队列
sql/50-pa-schema.sql                 # 岗位能力模型 8 张 uf_pa_* 表（含 rater_type 放宽）
```

已有旧数据的库升级按 `50 → 51 → 52 → 53` 顺序：

| 脚本 | 作用 |
|---|---|
| `50-pa-schema.sql` | 建 `uf_pa_post/category/item/item_level/band/sheet/sheet_item/rating` 8 张表 |
| `51-pa-cleanup.sql` | 旧模型 25 张表先备份（`*_bak_20261121`）再 DROP；DROP 8 个 `usp_assess_*`；清空 `uf_assess_task*` 历史数据 |
| `52-retire-assess-wf-hook.sql` | 停用泛微流程 1840 节点 7143 的 `AssessHookAction`（`workflowactionset.isused = 0`），避免旧表删除后钩子报错 |
| `53-drop-pa-person-post.sql` | 备份 `uf_pa_person_post` 为 `*_bak_20261121` 后 DROP —— 岗位已在发起页当场选，这张映射表没有读方了 |
| `64-pa-sheet-seal.sql` | `uf_pa_sheet` 加报告封存列（`seal_status` / `seal_time` / `seal_by_loginid` / `seal_by_name` / `sealed_json`）+ 索引。**加完存量全部是未封存，自助视角（本人 / 直接上级）立刻看不到任何报告**，需 HR 在「测评报告」页逐份「确认封存」后才对外可见 |

执行方式（脚本自带 `GO` 分段）：

```bash
cd /home/wgd/ws/ability-assessment
DB_SECRET_FILE=config/secret.yml node sql/run-ddl.js sql/50-pa-schema.sql
```

> `sql/51` 会 DROP 表并清空任务数据，**不可逆**（备份表可手工回灌）。执行前确认没有年度关闭/归档在跑。
> `uf_assess_migration_log`（迁移台账）只有在跑过旧脚本的库里才存在，代码侧无任何引用；
> 全新部署不需要它 —— 上面 8 支脚本不会创建该表。

### 2. 配置后端

后端配置位于 `backend/src/main/resources/application.yml`。敏感项**不在**该文件中，而由
`backend/config/secret.yml` 提供（`spring.config.import` 以相对路径引入，故启动时
`WorkingDirectory` 必须是 `backend/`），或由同名环境变量覆盖：

| 项 | 来源 | 缺失后果 |
|---|---|---|
| `spring.datasource.username` | `${OA_DB_USER}` ← `secret.yml` | 启动失败 |
| `spring.datasource.password` | `${OA_DB_PASSWORD}` ← `secret.yml` | 启动失败 |
| `oa.sso.client-secret` | `secret.yml` | SSO 换取 ticket 失败 |
| `JWT_SECRET` | 环境变量（`application.yml` 有开发用默认值） | 生产务必显式指定，至少 32 字符 |

`secret.yml` 已在 `.gitignore` 中，**没有也不会进入 Git 历史**。仓库里没有提供示例文件，
新环境需自行创建 `backend/config/secret.yml`，结构如下（值按实际环境填写）：

```yaml
spring:
  datasource:
    username: <数据库账号>
    password: <数据库口令>
oa:
  sso:
    client-secret: <泛微 weaver_sso_app 对应应用的 app_secret_>
jwt:
  secret: <至少 32 字符的随机串>
```

> 也可完全不建此文件，改用环境变量 `OA_DB_USER` / `OA_DB_PASSWORD` / `JWT_SECRET` 注入；
> 但 `oa.sso.client-secret` 目前只能从该文件读取。
> 生产环境建议只用环境变量，不落盘明文配置文件。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

访问：http://localhost:8080（接口前缀 `/api/v1`）

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问：http://localhost:3000

开发环境下 `/api` 请求由 Vite 代理转发到 `http://localhost:8080`，前端 `.env.development` 中 `VITE_API_BASE_URL=/api/v1`。

> 生产构建需要另一份 `.env.production`，仓库未收录，见下文「首次部署前必须先建 `frontend/.env.production`」。

### 5. 构建

```bash
# 后端
cd backend && mvn clean package -DskipTests

# 前端
cd frontend && pnpm build
```

## 主要接口

| 模块 | 路径前缀 |
|------|----------|
| 岗位管理 | `/api/v1/pa/posts` |
| 岗位能力模型（类别/能力项/等级/分值区间） | `/api/v1/pa/posts/{postId}`、`/api/v1/pa/models/*` |
| 测评任务（发起/列表/详情/催办/撤回） | `/api/v1/pa/assess-tasks` |
| 评价填写（URL token 定位评价人；接口本身仍需登录态，`/api/v1/pa/*` 不在 permitAll 名单） | `/api/v1/pa/assess-fill/*` |
| 个人测评报告 | `/api/v1/pa/reports`、`/api/v1/pa/reports/{sheetId}`、`/api/v1/pa/reports/{sheetId}/seal`、`/api/v1/pa/reports/{sheetId}/unseal` |
| 年度设置（读开放，写仅维度管理员/系统管理员） | `/api/v1/assess/periods`、`/api/v1/assess/periods/can-write` |
| 维度管理 | `/api/v1/assess-dimensions` |
| 管理员配置 | `/api/v1/assess-admins` |
| 认证（SSO） | `/api/v1/auth/sso/login`、`/auth/sso/callback`、`/auth/me`、`/auth/logout` |
| 泛微组织数据 | `/api/v1/sub-companies`、`/departments`、`/job-titles`、`/resources`、`/search/*` 等 |

## 已知问题

- **在线文档默认开启**：`application.yml` 未设 `assess.api-docs.enabled` 时为 `true`，`/doc.html`、
  `/v3/api-docs` 对外可访问。生产已由 `application-prod.yml` 关闭（端点返回 401/404），
  本地开发按需保留。

## 生产部署

本机以 **systemd user service** 托管（`Linger=yes`，与 `zd-code.service` 分属不同 cgroup，
聊天桥重启不会连带杀掉本服务）。**前后端都跑构建产物，不跑源码目录或 dev server。**

| | 形态 | 端口 | 单元 |
|---|---|---|---|
| 前端 | `vite build` 产物 `dist/`，由 `frontend/server/static-server.mjs`（零依赖）托管 | 3000 | `ability-frontend` |
| 后端 | `mvn package` 产物 jar + `prod` profile | 8080 | `ability-backend` |

nginx 反代 `172.18.28.206:3000`（配置在 nginx 主机，不在本仓库）；前端静态服务把 `/api/*`
流式转发到本机 8080，SSE 端点已禁缓冲。

### ⚠️ 首次部署前必须先建 `frontend/.env.production`

`.env.production` 属于环境相关配置，**已被 `.gitignore` 排除、仓库里没有**。
新机器克隆下来直接构建会出问题，必须手动创建一次：

```bash
cd frontend
printf 'VITE_API_BASE_URL=/api/v1\n' > .env.production
```

**为什么不能省**：`src/utils/request.ts` 的 axios `baseURL` 和
`src/utils/auth.ts` 的 SSO 跳转地址都直接读 `import.meta.env.VITE_API_BASE_URL`，
代码里**没有兜底默认值**。缺这个文件时 Vite 会把它替成 `undefined`，构建不会报错，
但运行期 SSO 跳转地址会变成 `undefined/auth/sso/login`，页面直接跳空。

值固定是 `/api/v1`（相对路径）：前端由 `static-server.mjs` 托管，同源 `/api/*` 会被
它反代到本机 8080，不需要写绝对地址。**换域名也不需要改这里**——对外域名由 nginx 决定。

> 上面两个页面 `/api/v1` 前缀之所以能工作，是因为 nginx 与 `static-server.mjs`
> 都只透传 `/api/*`，不重写路径。若以后要把前端挪到独立域名，需同时调整
> `ASSESS_FILL_BASE_URL`、`oa.sso.redirect-uri` 与 `weaver_sso_app.service_id` 三处。

### 发布

```bash
./deploy.sh --build    # 构建前端 dist + 后端 jar，然后重启两个服务并自检
./deploy.sh            # 产物已构建时，只重启 + 自检
./deploy.sh --build-only   # 只构建不重启
```

> 改完代码必须重新构建才生效——服务跑的是产物，不是源码目录。
> `deploy.sh --build` 内部执行 `pnpm build`，**不会**替你创建 `.env.production`。

### 运维

```bash
export XDG_RUNTIME_DIR=/run/user/1000
systemctl --user {status,restart} ability-backend ability-frontend
journalctl --user -u ability-backend -f     # 日志走 journald，自带上限，不再落文件
```

### 生产与开发的差异（`application-prod.yml`）

- `logging.level.com.company.ability: INFO`（开发为 DEBUG）
- MyBatis `log-impl` 改为 `NoLoggingImpl`（开发打全部 SQL 与参数，含个人信息）
- `assess.api-docs.enabled: false` + springdoc/knife4j 关闭在线文档
- `assess.auth.enforced: true` 强制登录

数据库口令、SSO client-secret、JWT 密钥仍从 `backend/config/secret.yml` 读取
（`spring.config.import` 走相对路径，故服务 `WorkingDirectory` 必须是 `backend/`），
或由环境变量 `OA_DB_USER` / `OA_DB_PASSWORD` / `JWT_SECRET` 覆盖。


## 文档

项目文档位于：`/home/wgd/ws/project/能力测评/`

- 需求文档：`01-需求文档.md`
- 架构设计：`02-架构设计.md`
- 技术选型：`03-技术选型.md`
- 测试报告：`05-测试报告.md`

## 开发规范

- 后端：遵循阿里巴巴 Java 开发规范
- 前端：使用 Composition API + TypeScript strict 模式，禁止 `any`
- 提交规范：使用 commitlint（`type(scope): description`）

详见：`CLAUDE.md`
