# 能力测评系统

> 能力测评配置管理与报表系统，与泛微 e-cology 平台集成

## 技术栈

**后端：**
- Spring Boot 3.2
- MyBatis-Plus 3.5
- SQL Server
- JWT 认证

**前端：**
- Vue 3
- Element Plus
- Vite 5
- TypeScript
- Pinia（状态管理）

## 项目结构

```
ability-assessment/
├── backend/          # 后端（Spring Boot）
├── frontend/         # 前端（Vue 3）
└── CLAUDE.md         # 项目指令
```

## 快速开始

### 后端

```bash
cd backend
mvn spring-boot:run
```

访问：http://localhost:8080

### 前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问：http://localhost:3000

## 文档

项目文档位于：`/home/wgd/ws/project/能力测评/`

- 需求文档：`01-需求文档.md`
- 架构设计：`02-架构设计.md`
- 技术选型：`03-技术选型.md`

## 开发规范

- 后端：遵循阿里巴巴 Java 开发规范
- 前端：使用 Composition API + TypeScript strict 模式
- 提交规范：使用 commitlint

详见：`CLAUDE.md`
