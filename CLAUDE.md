# 能力测评系统

> 能力测评配置管理与报表系统，与泛微 e-cology 平台集成

---

## 项目信息

- **项目名称：** ability-assessment（能力测评系统）
- **技术栈：** Spring Boot 3 + Vue 3 + Element Plus + SQL Server
- **包管理器：** Maven（后端）+ pnpm（前端）
- **数据库：** SQL Server（与泛微共用）
- **JDK 版本：** Java 17+
- **Node 版本：** Node 18+

---

## 常用命令

### 后端（Spring Boot）

```bash
# 进入后端目录
cd backend

# 编译
mvn clean compile

# 运行
mvn spring-boot:run

# 打包
mvn clean package -DskipTests

# 运行测试
mvn test
```

### 前端（Vue 3）

```bash
# 进入前端目录
cd frontend

# 安装依赖
pnpm install

# 开发
pnpm dev

# 构建
pnpm build

# 预览
pnpm preview
```

---

## 项目结构

```
ability-assessment/
├── backend/                          # 后端（Spring Boot）
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/company/ability/
│   │   │   │       ├── controller/   # 控制器
│   │   │   │       ├── service/      # 服务层
│   │   │   │       ├── mapper/       # MyBatis Mapper
│   │   │   │       ├── entity/       # 数据实体
│   │   │   │       ├── dto/          # 数据传输对象
│   │   │   │       ├── vo/           # 视图对象
│   │   │   │       ├── config/       # 配置类
│   │   │   │       ├── security/     # 安全配置
│   │   │   │       └── utils/        # 工具类
│   │   │   └── resources/
│   │   │       ├── mapper/           # MyBatis XML
│   │   │       ├── application.yml   # 配置文件
│   │   │       └── application-dev.yml
│   │   └── test/                     # 测试
│   └── pom.xml                       # Maven 配置
│
├── frontend/                         # 前端（Vue 3）
│   ├── src/
│   │   ├── api/                      # API 接口
│   │   ├── components/               # 组件
│   │   │   ├── ui/                   # UI 组件
│   │   │   └── features/             # 业务组件
│   │   ├── views/                    # 页面
│   │   ├── router/                   # 路由
│   │   ├── stores/                   # 状态管理（Pinia）
│   │   ├── utils/                    # 工具函数
│   │   ├── types/                    # 类型定义
│   │   ├── App.vue
│   │   └── main.ts
│   ├── public/                       # 静态资源
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
│
└── CLAUDE.md                         # 本文件
```

---

## 代码规范

### 后端（Java）

- 遵循阿里巴巴 Java 开发规范
- 使用 RESTful API 设计
- 统一异常处理
- 统一响应格式
- 禁止使用 `System.out.println`，使用日志框架

### 前端（Vue 3）

- 使用 Composition API
- 组件命名使用 PascalCase
- 使用 TypeScript strict 模式
- 代码格式化使用 Prettier
- 禁止使用 `any` 类型

---

## 架构约定

- **分层架构：** Controller → Service → Mapper
- **认证方案：** 泛微账号密码（HrmResource 表）+ JWT Token
- **权限管理：** 在泛微端手动维护，系统只读取
- **API 风格：** RESTful
- **错误处理：** 统一异常处理 + 友好错误提示
- **数据隔离：** 基于 company_id 过滤

---

## 数据库设计

**数据库：** SQL Server（与泛微共用）

**核心表：**
- uf_ability_category - 能力类别
- uf_ability_element - 能力要素
- uf_org_ability_req - 组织能力要求
- uf_position_ability_req - 岗位能力要求
- uf_user_ability_req - 人员能力要求
- uf_score_weight - 评分权重配置
- uf_assessment_task - 测评任务
- uf_assessment_score - 测评评分记录
- uf_sso_token - SSO Token（临时）
- uf_jwt_token - JWT Token（临时）
- uf_sys_config - 系统配置

**视图（读取泛微组织架构）：**
- v_company - 分公司
- v_department - 部门
- v_position - 岗位
- v_user - 人员

---

## 环境变量

### 后端（application-dev.yml）

```yaml
spring:
  datasource:
    url: jdbc:sqlserver://localhost:1433;databaseName=ecology
    username: sa
    password: your-password
    driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver

jwt:
  secret: your-secret-key
  expiration: 7200  # 2小时
```

### 前端（.env.development）

```bash
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

---

## 单点登录（SSO）

**流程：**
1. 用户在泛微登录后，点击"能力测评系统"菜单
2. 泛微生成 SSO Token，存储到 uf_sso_token 表
3. 泛微跳转到独立应用：http://localhost:8080/sso?token=xxx
4. 独立应用接收 Token，调用泛微 SSO 验证接口
5. 验证通过，从 HrmResource 表获取用户信息，生成 JWT Token
6. 前端存储 JWT Token，自动登录，跳转到首页

---

## 文档规范

**所有项目文档集中在 `/home/wgd/ws/project/能力测评/` 目录下：**
- 需求文档：`/home/wgd/ws/project/能力测评/01-需求文档.md`
- 架构设计：`/home/wgd/ws/project/能力测评/02-架构设计.md`
- 技术选型：`/home/wgd/ws/project/能力测评/03-技术选型.md`

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
