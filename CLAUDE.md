# 能力测评系统

> 能力测评配置管理与报表系统，与泛微 e-cology 平台集成

---

## 项目信息

- **项目名称：** ability-assessment（能力测评系统）
- **技术栈：** Spring Boot 3.2 + MyBatis-Plus 3.5 + Vue 3 + Element Plus + Vite 5 + TypeScript
- **包管理器：** Maven（后端）+ pnpm（前端）
- **数据库：** SQL Server（与泛微 e-cology 共用同一库 `ecology`）
- **JDK 版本：** Java 21（`pom.xml` 中 `java.version=21`）
- **Node 版本：** Node 18+（当前开发环境为 Node 20）

---

## 常用命令

### 后端（Spring Boot）

```bash
# 进入后端目录
cd backend

# 编译（离线环境加 -o）
mvn clean compile

# 运行（默认端口 8080）
mvn spring-boot:run

# 打包
mvn clean package -DskipTests

# 运行测试（注意：当前测试代码无法编译，见「已知问题」）
mvn test
```

### 前端（Vue 3）

```bash
# 进入前端目录
cd frontend

# 安装依赖
pnpm install

# 开发（默认端口 3000，/api 代理到 localhost:8080）
pnpm dev

# 构建（先 vue-tsc 类型检查，再 vite build）
pnpm build

# 预览
pnpm preview

# 代码检查 / 格式化
pnpm lint
pnpm format
```

---

## 项目结构

```
ability-assessment/
├── backend/                          # 后端（Spring Boot）
│   ├── sql/                          # 历史迁移脚本（add_department_*、create_user_ability_req_tables）
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/company/ability/
│   │   │   │   ├── controller/       # 控制器（/api/v1/...）
│   │   │   │   ├── service/          # 服务接口
│   │   │   │   │   └── impl/         # 服务实现
│   │   │   │   ├── mapper/           # MyBatis-Plus Mapper
│   │   │   │   ├── entity/           # 数据实体（uf_* 表）
│   │   │   │   ├── dto/              # 请求对象
│   │   │   │   ├── vo/               # 响应对象
│   │   │   │   ├── config/           # CORS / MyBatis-Plus / Security / Jackson 配置
│   │   │   │   ├── exception/        # 业务异常 + 全局异常处理
│   │   │   │   └── utils/            # 工具类（JdbcPageHelper）
│   │   │   └── resources/
│   │   │       └── application.yml   # 主配置（dev profile）
│   │   └── test/                     # 测试（需修复，见「已知问题」）
│   └── pom.xml
│
├── frontend/                         # 前端（Vue 3）
│   ├── src/
│   │   ├── api/                      # 接口封装（按业务模块）
│   │   ├── components/               # 通用业务组件（如 ResourceAutocomplete）
│   │   ├── layouts/                  # 主布局（侧边导航）
│   │   ├── router/                   # 路由
│   │   ├── styles/                   # 全局样式（theme.css）
│   │   ├── types/                    # 类型定义
│   │   ├── utils/                    # 工具函数（request.ts：axios 封装）
│   │   ├── views/                    # 页面
│   │   ├── App.vue
│   │   └── main.ts                   # 入口（挂载 Pinia / Element Plus）
│   ├── .env.development              # VITE_API_BASE_URL=/api/v1
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
│
├── sql/                              # 建表脚本 01-21（按序执行）
├── docs/                             # 项目内补充文档
├── CLAUDE.md                         # 本文件
└── README.md
```

---

## 功能模块

| 模块 | 后端入口 | 前端页面 | 说明 |
|------|----------|----------|------|
| 维度管理（类租户） | `/api/v1/assess-dimensions` | 维度管理（仅管理员） | 维度 CRUD、分部/人员授权配置、顶栏维度切换（`/authorized`，管理员返回全部启用维度） |
| 能力类别管理 | `/api/v1/ability-categories` | 能力类别管理 | 能力类别 CRUD，删除前校验引用 |
| 能力要素管理 | `/api/v1/ability-elements` | 能力要素管理 | 要素 CRUD，启用要素时自动启用所属类别 |
| 能力等级 | `/api/v1/ability-element-levels` | 能力要素等级弹窗 | 要素的等级定义 |
| 组织能力要求 | `/api/v1/org-ability-reqs` | 组织能力要求（菜单已隐藏） | 按部门配置能力要求 |
| 岗位能力要求 | `/api/v1/position-ability-reqs` | 岗位能力要求 | 按部门 + 岗位配置能力要求 |
| 人员能力要求 | `/api/v1/user-ability-reqs-new` | 人员能力要求 | 按人员配置，记录岗位来源，被引用时禁止删除 |
| 能力测评配置 | `/api/v1/assess-config` | 能力测评配置 | 能力类型权重、测评对象权重、等级标准、等级分值 |
| 泛微组织数据 | `/api/v1/sub-companies`、`/departments`、`/job-titles`、`/resources`、`/search/*` 等 | 各选择器组件 | 通过 `JdbcTemplate` 只读查询泛微 `Hr*` 表 |
| 认证（SSO） | `/api/v1/auth/sso/login`、`/sso/callback`、`/me`、`/logout` | SsoCallback/SsoError 页、路由守卫 | 泛微 OAuth2 单点登录，JWT 签发校验（详见 SSO 一节）；`/me` 含 `admin` 角色标记 |
| 管理员配置 | `/api/v1/assess-admins` | 管理员配置（仅管理员） | 管理员名单维护：新增按泛微在职人员、移除（配置超管与本人不可删） |

**能力测评配置（`/api/v1/assess-config`）接口：**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / PUT | `/ability-weights` | 能力类型权重（粒度：能力类别） |
| GET / PUT | `/assessor-weights` | 测评对象权重 |
| GET / PUT | `/grade-levels` | 等级标准 |
| GET / PUT | `/level-scores` | 等级分值（等级序号 1-5 → 分值） |

> 旧的评分权重模块（`ScoreWeight*`、`/api/v1/score-weights`、`uf_score_weight`）已被「能力测评配置」取代，相关 Java 与前端代码已删除。`sql/06-create-score-weight.sql` 仅作历史保留。

---

## 代码规范

### 后端（Java）

- 遵循阿里巴巴 Java 开发规范
- 使用 RESTful API 设计，统一响应 `Result`，分页统一 `PageRequest` / `PageResult`
- 统一异常处理（`BusinessException` + `GlobalExceptionHandler`）
- 禁止使用 `System.out.println`，使用日志框架
- 逻辑删除字段为 `deleted`（0 未删除，1 已删除）

### 前端（Vue 3）

- 使用 Composition API
- 组件命名使用 PascalCase
- 使用 TypeScript strict 模式
- 代码格式化使用 Prettier
- 禁止使用 `any` 类型
- 接口请求统一经过 `utils/request.ts`

---

## 架构约定

- **分层架构：** Controller → Service → Mapper
- **泛微数据访问：** 组织架构数据（`HrmSubCompany`、`HrmDepartment`、`HrmJobTitles`、`HrmResource` 等）通过 `JdbcTemplate` 只读访问，不做写入
- **数据隔离：** 业务表按 `dimension_id`（维度，类租户）+ `company_id` 过滤。
  - 维度机制：前端顶栏切换维度 → 请求头 `X-Dimension-Id` → `DimensionContextFilter` → `DimensionContext`（ThreadLocal）→ MyBatis-Plus 租户拦截器（`MyBatisPlusConfig.TENANT_TABLES` 清单内的表自动拼 `WHERE dimension_id=?` 并在 INSERT 自动填充）；JdbcTemplate 原生 SQL（AssessPeriodService / AssessSheet* / AssessReportService）手动拼过滤，未列入清单的表（平台表、钩子直写子表）不拦截。
  - 上下文缺失（泛微钩子等）为宽松模式：拦截跳过、原生 SQL 回落默认维度（id=1）。新增表参与隔离时同步维护 `TENANT_TABLES` 清单。前端 `request.ts` 对 `X-Dimension-Id` 缺省兜底 `'1'`，UI 请求永远带头。
  - **「先删后插」的整表覆盖式保存禁止只靠拦截器隔离**：写路径必须显式带 `dimension_id`（delete 加 eq、insert set），否则宽松模式下 delete 跨维度误删。落点：`AssessConfigServiceImpl`（四组配置 getter/save 显式带维度，读取返回固定骨架）。
  - 维度与授权：`uf_assess_dimension` / `uf_assess_dimension_auth`（授权对象=分部/人员；**无授权行=仅管理员**［2026-10-22 语义，原为所有人可用］；配置后按人员 ID 或其一级分部命中）。接口 `/api/v1/assess-dimensions`。零租户普通用户前端显示引导页（不发业务请求）。详见 `project/能力测评/02-架构设计.md` 第十三、十四节。
- **客户评价开关：** `uf_ability_element.allow_client_assess`（1-允许/0-不允许）。不允许的要素客户分按 **0** 计入加权总分（做法 B），落点：`usp_assess_recalc` v4 与 `AssessArchiveServiceImpl`。

### 认证与权限（2026-10-21 已落地）

- **登录：** 泛微 OAuth2 单点登录（weaversso 认证中心，详见「单点登录（SSO）」一节与 `project/能力测评/02-架构设计.md` 第七节）。授权码换 JWT：`AuthController`（/api/v1/auth/sso/*、/me、/logout）→ `SsoLoginService` → `OaSsoClient` → HrmResource（loginid，status=1 在职）。
- **鉴权：** `assess.auth.enforced=true`（默认）时业务接口全部要求 `Authorization: Bearer <JWT>`；`JwtAuthFilter` 解析、未认证统一返回 401 JSON。设 false 回退匿名（仅开发调试，禁止公网）。
- **权限：** 账号与组织权威在泛微端，本系统只读；维度授权已升级为按 JWT 人员身份强制过滤（`UserContext`）。请求级越权：`DimensionContextFilter` 对已登录用户的 `X-Dimension-Id` 按授权表校验，无权维度直接 403（`AssessDimensionService.isAuthorizedForUser`）；无登录身份的内部调用维持宽松。
- **管理员（2026-10-22 已落地）：** 身份 = 配置超管（`assess.bootstrap-admins`，OA loginid，不可界面删除、用于引导首批管理员）∪ `uf_assess_admin` 表（sql/21，「管理员配置」页维护）。实时查库判定、**不进 JWT**（撤销即时生效，避免 2 小时空窗）。管理员：维度管理接口全放行、顶栏切换器返回全部启用维度、请求级维度校验豁免（即可逐租户查看全部数据；跨租户汇总视图未实现）；`AdminService.requireAdmin()` 是各管理接口统一门。详见 `project/能力测评/02-架构设计.md` 第十四节。
- 备用「Ecology 账密校验」方案（`登录密码校验方案.md`）未启用。

---

## 数据库设计

**数据库：** SQL Server（与泛微共用，库名 `ecology`）

**业务表（`uf_` 前缀，由 `sql/` 脚本创建）：**

| 表 | 说明 |
|----|------|
| uf_assess_dimension | 测评维度（类租户），id=1 为默认维度 |
| uf_assess_dimension_auth | 维度授权（auth_type 1-分部 2-人员；无授权行=仅管理员，2026-10-22 起） |
| uf_assess_admin | 系统管理员名单（全局角色，loginid 唯一，物理删除小表，`sql/21`） |
| uf_ability_category | 能力类别 |
| uf_ability_element | 能力要素 |
| uf_org_ability_req | 组织能力要求 |
| uf_position_ability_req | 岗位能力要求（旧版，由 `sql/04` 创建） |
| uf_user_ability_req | 人员能力要求（旧版，由 `sql/05` 创建） |
| uf_user_ability_req_new | 人员能力要求（新版主表） |
| uf_user_ability_req_item | 人员能力要求明细 |
| uf_score_weight | 评分权重（已废弃，见上文） |
| uf_assess_weight_ability | 能力测评：能力类型权重 |
| uf_assess_weight_assessor | 能力测评：测评对象权重 |
| uf_assess_grade_level | 能力测评：等级标准 |
| uf_assess_level_score | 能力测评：等级分值 |

**代码中已使用、但 `sql/` 中尚无建表脚本的表（待补充 DDL）：**

- `uf_position_ability_req_new`（岗位能力要求新版主表）
- `uf_position_ability_req_item`（岗位能力要求明细）
- `uf_ability_element_level`（能力要素等级）

**规划中、尚未在代码中使用的表：** `uf_assessment_task`、`uf_assessment_score`、`uf_sys_config`。
**已作废（不再创建）：** `uf_sso_token`、`uf_jwt_token`——SSO 改走 OAuth2 后 ticket/JWT 均不落库。

**泛微只读表：** `HrmSubCompany`、`HrmDepartment`、`HrmJobGroups`、`HrmJobActivities`、`HrmJobTitles`、`HrmResource`。

---

## 环境变量与配置

### 后端（`backend/src/main/resources/application.yml`）

| 配置项 | 说明 |
|--------|------|
| `server.port` | 8080 |
| `spring.profiles.active` | dev |
| `spring.datasource.url` / `username` / `password` | SQL Server 连接信息。口令经 `config/secret.yml`（开发）或环境变量 `OA_DB_USER`/`OA_DB_PASSWORD`（部署）注入，两者皆缺则启动失败 |
| `jwt.secret` | 环境变量 `JWT_SECRET` 或 `config/secret.yml`；开发实际值在 secret.yml |
| `jwt.expiration` | 7200 秒（2 小时） |
| `oa.sso.*` | 泛微 OAuth2：base-url/client-id/redirect-uri/超时/state TTL |
| `assess.auth.enforced` | 业务接口强制登录开关（默认 true） |
| `assess.bootstrap-admins` | 配置超管 OA 登录名（逗号分隔，当前 `2024188`）：永远视为管理员且不可界面删除 |

**本地敏感配置 `backend/config/secret.yml`**（已 gitignore，经 `spring.config.import` 加载）：
`spring.datasource.username/password`、`oa.sso.client-secret`、`jwt.secret`。缺失且
`assess.auth.enforced=true` 时 `SsoConfigValidator` 会拒绝启动并给出指引。新克隆需先创建该文件。

### 前端（`frontend/.env.development`）

```bash
VITE_API_BASE_URL=/api/v1   # 开发时经 Vite 代理转发到 http://localhost:8080
```

---

## 单点登录（SSO）— 已实现（泛微 OAuth2 / weaversso）

**对接依据：** 泛微官方《异构系统接入 OAuth2 认证开发说明》v4.0。
**认证中心：** `http://xcx.zhongda.cn:8080`（接口 `/sso/oauth2.0/authorize|accessToken|profile`、`/sso/logout`）。
**应用注册：** 泛微 `weaver_sso_app` id=5「CBA」，client_id `1feab8b2-bd67-42da-8a31-2dce003fc73a`。

**实际流程：**
1. 用户访问前端任意页 → 无 JWT → 路由守卫整页跳 `GET /api/v1/auth/sso/login?from=<原页面>`
2. 后端签发 state（16 位随机短凭证，回跳地址存服务端内存映射、一次性消费）→ 302 到 OA
   authorize；OA 已登录则直接回跳 `/sso/callback?ticket=ST-…&state=…`（ticket 60 秒一次有效）
3. 回调页 POST `/api/v1/auth/sso/callback {ticket,state}` → 后端验 state、
   ticket 换 access_token（参数名 code）、取 profile.id（=OA loginid）
4. loginid 查 HrmResource（status=1）→ 签发 JWT（2h）→ 前端存 localStorage + Pinia → 回原页面

**要点：**
- ticket 在 URL 中参数名为 `ticket`、换 token 时参数名为 `code`（同一个值）
- 账号规则为同名映射：`apploginid = loginid`，无需 relatedAccount/映射表
- 原设计的 `uf_sso_token`/`uf_jwt_token` 落库方案**已作废**（无状态，不落库）
- 前端有 10 秒 SSO 跳转循环保护；回跳仅允许站内相对路径

---

## 安全事项

- ~~数据库密码明文存放于 `application.yml`~~（2026-10-21 已迁移至 `config/secret.yml`/环境变量）。**遗留：旧口令仍在 Git 历史，轮换该数据库密码待办。**
- `jwt.secret` 与 `oa.sso.client-secret` 走 `backend/config/secret.yml`（已 gitignore）；生产用环境变量注入亦可。
- 业务接口已强制 JWT 登录（`assess.auth.enforced`）；**SSO 联调期若需匿名访问，临时置 false 后务必改回**。
- 残余风险（已知并接受）：OA 认证中心为公网 IP + 明文 HTTP；JWT 无黑名单，签发后 2 小时内有效；`.env.*` 与 secret.yml 不入 Git。

---

## 已知问题

- **工具输出掩码可污染字符串字面量（2026-10-21 实际踩过）：** 本工作环境会把形似密钥的字符串以 `***` 掩码展示；若照抄掩码结果写回文件，会把真实值毁成字面 `***`（曾使 `utils/auth.ts` 三个 localStorage key 同名，token 被 user 覆盖 → SSO 登录成功但请求永远 401 循环）。用 `awk 'NR==x{print length($0)}'` 按字节长度核验文件真实内容，勿凭显示值断言；给密钥类字面量取名避开 token/jwt/secret 等词。
- **建表脚本不完整：** 见「数据库设计」中的三张待补充表。
- **Maven 不在 PATH：** 本机使用 `~/apache-maven-3.9.16/bin/mvn`（下文命令中的 `mvn` 均指该路径）。
- ~~后端测试无法编译~~（已解决：过时用例已删除；2026-10-21 修复 H2 测试库 `schema-test.sql` 与维度/客户评价/新版岗位表实体字段漂移，`mvn test` 全量 42 用例通过）。
- **安全：** 见「安全事项」。

---

## 文档规范

**所有项目文档集中在 `/home/wgd/ws/project/能力测评/` 目录下：**
- 需求文档：`/home/wgd/ws/project/能力测评/01-需求文档.md`
- 架构设计：`/home/wgd/ws/project/能力测评/02-架构设计.md`
- 技术选型：`/home/wgd/ws/project/能力测评/03-技术选型.md`
- 测试报告：`/home/wgd/ws/project/能力测评/05-测试报告.md`
- 测评计算与归档设计：`/home/wgd/ws/project/能力测评/06-测评计算与归档设计.md`
- 泛微钩子数据对接说明：`/home/wgd/ws/project/能力测评/07-泛微钩子数据对接说明.md`
- 泛微对接总结：`/home/wgd/ws/project/能力测评/08-泛微对接总结.md`

**文档内容与代码不一致时，以代码为准，并同步更新本文件与 README。**

---

## 提交规范

使用 commitlint：

```bash
# 提交信息格式
type(scope): description

# 类型
feat: 新功能
fix: 修复 bug
docs: 文档更新
style: 代码格式
refactor: 重构
test: 测试相关
chore: 构建过程或辅助工具变动
```

---

## 参考

- 工作流规范：`/home/wgd/ws/CLAUDE.md`
- Skill 文档：`/home/wgd/ws/.claude/skills/`
- 项目文档：`/home/wgd/ws/project/能力测评/`

---

**文档结束**
