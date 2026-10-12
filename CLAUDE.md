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
├── sql/                              # 建表脚本 01-08（按序执行）
├── docs/                             # 项目内补充文档
├── CLAUDE.md                         # 本文件
└── README.md
```

---

## 功能模块

| 模块 | 后端入口 | 前端页面 | 说明 |
|------|----------|----------|------|
| 能力类别管理 | `/api/v1/ability-categories` | 能力类别管理 | 能力类别 CRUD，删除前校验引用 |
| 能力要素管理 | `/api/v1/ability-elements` | 能力要素管理 | 要素 CRUD，启用要素时自动启用所属类别 |
| 能力等级 | `/api/v1/ability-element-levels` | 能力要素等级弹窗 | 要素的等级定义 |
| 组织能力要求 | `/api/v1/org-ability-reqs` | 组织能力要求（菜单已隐藏） | 按部门配置能力要求 |
| 岗位能力要求 | `/api/v1/position-ability-reqs` | 岗位能力要求 | 按部门 + 岗位配置能力要求 |
| 人员能力要求 | `/api/v1/user-ability-reqs-new` | 人员能力要求 | 按人员配置，记录岗位来源，被引用时禁止删除 |
| 能力测评配置 | `/api/v1/assess-config` | 能力测评配置 | 能力类型权重、测评对象权重、等级标准、等级分值 |
| 泛微组织数据 | `/api/v1/sub-companies`、`/departments`、`/job-titles`、`/resources`、`/search/*` 等 | 各选择器组件 | 通过 `JdbcTemplate` 只读查询泛微 `Hr*` 表 |

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
- **数据隔离：** 业务表按 `company_id` 过滤（目标约定，新表需遵守）

### 认证与权限（现状与规划）

- **现状：** `SecurityConfig` 中 `anyRequest().permitAll()`，CSRF 与 Session 已关闭，**接口当前未做登录校验**。
- **规划：** 泛微账号密码（HrmResource 表）+ JWT Token；权限在泛微端维护，系统只读取。
- 在规划的认证方案落地前，请勿将服务暴露到公网。

---

## 数据库设计

**数据库：** SQL Server（与泛微共用，库名 `ecology`）

**业务表（`uf_` 前缀，由 `sql/` 脚本创建）：**

| 表 | 说明 |
|----|------|
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

**规划中、尚未在代码中使用的表：** `uf_assessment_task`、`uf_assessment_score`、`uf_sso_token`、`uf_jwt_token`、`uf_sys_config`。

**泛微只读表：** `HrmSubCompany`、`HrmDepartment`、`HrmJobGroups`、`HrmJobActivities`、`HrmJobTitles`、`HrmResource`。

---

## 环境变量与配置

### 后端（`backend/src/main/resources/application.yml`）

| 配置项 | 说明 |
|--------|------|
| `server.port` | 8080 |
| `spring.profiles.active` | dev |
| `spring.datasource.url` / `username` / `password` | SQL Server 连接信息（当前直接写在 yml 中，见「安全事项」） |
| `jwt.secret` | 环境变量 `JWT_SECRET`，缺省值仅供开发，至少 32 字符 |
| `jwt.expiration` | 7200 秒（2 小时） |

### 前端（`frontend/.env.development`）

```bash
VITE_API_BASE_URL=/api/v1   # 开发时经 Vite 代理转发到 http://localhost:8080
```

---

## 单点登录（SSO）— 设计目标，尚未实现

**目标流程：**
1. 用户在泛微登录后，点击"能力测评系统"菜单
2. 泛微生成 SSO Token，存储到 uf_sso_token 表
3. 泛微跳转到独立应用：http://localhost:8080/sso?token=xxx
4. 独立应用接收 Token，调用泛微 SSO 验证接口
5. 验证通过，从 HrmResource 表获取用户信息，生成 JWT Token
6. 前端存储 JWT Token，自动登录，跳转到首页

---

## 安全事项

- **数据库密码明文存放于 `application.yml`，且已提交到 Git 历史。** 应改为环境变量或外部配置文件，并轮换该密码。
- `jwt.secret` 缺省值为占位字符串，生产环境必须通过 `JWT_SECRET` 注入强随机密钥。
- 接口当前无鉴权（见「认证与权限」）。
- `.env.*` 文件不要提交；`.gitignore` 已忽略 `.env`、`.env.local`、`.env.production`。

---

## 已知问题

- **后端测试无法编译：** `backend/src/test/.../ScoreWeightServiceTest.java` 与 `UserAbilityReqServiceTest.java` 仍引用已删除的旧类（`ScoreWeight*`、`UserAbilityReq*`），`mvn test` 会失败。需要改写为针对 `AssessConfigService` 与 `UserAbilityReqNew*` 的测试，或删除过时用例。
- **建表脚本不完整：** 见「数据库设计」中的三张待补充表。
- **安全：** 见「安全事项」。

---

## 文档规范

**所有项目文档集中在 `/home/wgd/ws/project/能力测评/` 目录下：**
- 需求文档：`/home/wgd/ws/project/能力测评/01-需求文档.md`
- 架构设计：`/home/wgd/ws/project/能力测评/02-架构设计.md`
- 技术选型：`/home/wgd/ws/project/能力测评/03-技术选型.md`
- 测试报告：`/home/wgd/ws/project/能力测评/05-测试报告.md`

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
