# 能力测评系统

> 能力测评配置管理与报表系统，与泛微 e-cology 平台集成

## 功能概览

- **能力体系维护**：能力类别、能力要素、要素等级的增删改查
- **能力要求配置**：岗位能力要求、人员能力要求（记录岗位来源，被引用时禁止删除）
- **能力测评配置**：能力类型权重、测评对象权重、等级标准、等级分值
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
├── sql/         # 建表脚本（按 01 → 08 顺序执行）
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
```

> `sql/06-create-score-weight.sql` 为已废弃的旧评分权重表，新部署可不执行。
> 代码中还使用了 `uf_position_ability_req_new`、`uf_position_ability_req_item`、`uf_ability_element_level`，但目前 `sql/` 中没有对应脚本，需要补充。

### 2. 配置后端

后端配置位于 `backend/src/main/resources/application.yml`，需要确认以下内容：

- `spring.datasource.*`：SQL Server 连接信息
- `JWT_SECRET`（环境变量）：JWT 签名密钥，至少 32 字符

> ⚠️ 当前数据库密码直接写在 `application.yml` 中并已提交到 Git，请改为环境变量或外部配置，并尽快更换密码。

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

- **后端测试无法编译**：`ScoreWeightServiceTest`、`UserAbilityReqServiceTest` 引用了已删除的旧类，`mvn test` 会失败。
- **接口暂无鉴权**：Spring Security 当前放行所有请求，请勿直接暴露到公网。

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
