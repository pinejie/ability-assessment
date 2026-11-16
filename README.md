# 能力测评系统

> 能力测评配置管理与报表系统，与泛微 e-cology 平台集成

## 功能概览

- **能力体系维护**：能力类别、能力要素、要素等级的增删改查
- **能力要求配置**：岗位能力要求、人员能力要求（记录岗位来源，被引用时禁止删除）
- **能力测评配置**：能力类型权重、测评人权重、等级标准、等级分值
- **泛微组织数据**：分公司、部门、岗位、人员的只读选择器（直接查询泛微 `Hr*` 表）

## 技术栈

**后端**
- Java 21
- Spring Boot 3.2
- MyBatis-Plus 3.5
- SQL Server（与泛微共用数据库）
- JWT（jjwt 0.12，认证功能规划中）

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
├── sql/         # 建表/运维脚本（按 01 → 32 顺序执行）
├── docs/        # 补充文档
├── CLAUDE.md    # 项目指令（面向 Claude Code）
└── README.md
```

## 环境要求

- JDK 21
- Maven 3.6+
- Node.js 18+ 与 pnpm
- SQL Server（可访问泛微 `ecology` 库）

## 快速开始

### 1. 初始化数据库

按顺序执行 `sql/` 下的脚本：

```
sql/01-create-ability-category.sql
sql/02-create-ability-element.sql
sql/03-create-org-ability-req.sql
sql/04-create-position-ability-req.sql
sql/05-create-user-ability-req.sql
sql/07-create-assess-config.sql
sql/08-create-assess-level-score.sql
# ……09 ~ 30 为后续迭代脚本，按编号顺序执行
sql/31-position-element-weight.sql   # 岗位权重列 + 测评页面权重快照列（recalc v7）
sql/32-category-weight.sql           # 岗位能力类别权重修正：重置明细权重为全局类别权重（recalc v8）
```

> `sql/06-create-score-weight.sql` 为已废弃的旧评分权重表，新部署可不执行。
> 代码中还使用了 `uf_position_ability_req_new`、`uf_position_ability_req_item`、`uf_ability_element_level`，旧脚本未完整覆盖，以实际库结构与后续迁移脚本为准。
> `sql/30`（等级分值动态化）、`sql/31`（岗位权重列）、`sql/32`（岗位类别权重口径修正）含列变更/存储过程重建，执行前请先备份并确认没有年度关闭操作在跑。

### 2. 配置后端

后端配置位于 `backend/src/main/resources/application.yml`。敏感项**不在**该文件中，而由
`backend/config/secret.yml` 提供（`spring.config.import` 以相对路径引入，故启动时
`WorkingDirectory` 必须是 `backend/`），或由同名环境变量覆盖：

| 项 | 来源 | 缺失后果 |
|---|---|---|
| `spring.datasource.username` | `${OA_DB_USER}` ← `secret.yml` | 启动失败 |
| `spring.datasource.password` | `${OA_DB_PASSWORD}` ← `secret.yml` | 启动失败 |
| `oa.sso.client-secret` | `secret.yml` | SSO 换取 ticket 失败 |
| `agent.internal-token` | `secret.yml` | `/agent/draft` 一律 401 |
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
agent:
  internal-token: <内部接口令牌>
jwt:
  secret: <至少 32 字符的随机串>
```

> 也可完全不建此文件，改用环境变量 `OA_DB_USER` / `OA_DB_PASSWORD` / `JWT_SECRET` 注入；
> 但 `oa.sso.client-secret` 与 `agent.internal-token` 目前只能从该文件读取。
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
| 能力类别 | `/api/v1/ability-categories` |
| 能力要素 | `/api/v1/ability-elements` |
| 能力要素等级 | `/api/v1/ability-element-levels` |
| 组织能力要求 | `/api/v1/org-ability-reqs` |
| 岗位能力要求 | `/api/v1/position-ability-reqs` |
| 人员能力要求 | `/api/v1/user-ability-reqs-new` |
| 能力测评配置 | `/api/v1/assess-config/{ability-weights, assessor-weights, grade-levels, level-scores}` |
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
