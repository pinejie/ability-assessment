# 能力测评系统

> 岗位能力模型 + 测评任务 + 个人测评报告，与泛微 e-cology 平台集成

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

# 运行（默认端口 8080）——必须走 user service，禁止在聊天会话里直接 mvn spring-boot:run
# 原因：会话进程树挂在 zd-code.service cgroup 下，该服务每次重启会 SIGTERM 整个 cgroup，
#      会话里启动的后端会被连带杀掉（2026-10-22 排查确认）。
# 生产形态（2026-11-16 起）：unit 跑 jar + prod profile，不再 source-run。
export XDG_RUNTIME_DIR=/run/user/1000
systemctl --user start ability-backend        # 启动（unit: ~/.config/systemd/user/ability-backend.service）
systemctl --user stop ability-backend         # 停止
systemctl --user restart ability-backend      # 重启
systemctl --user status ability-backend       # 状态
journalctl --user -u ability-backend -f       # 实时日志（走 journald，不再落 run-8080.log）
# 已 enable：开机/登录自启（依赖 Linger=yes），Restart=always 崩溃自动拉起

# 前台调试（临时跑一下才用这个；mvn 不在 PATH，用绝对路径）
/home/wgd/apache-maven-3.9.16/bin/mvn spring-boot:run

# 发布（构建 jar 后必须重启服务才生效——服务跑的是产物不是源码目录）
./deploy.sh --build
# 服务 ExecStart 跑的是 backend/app.jar，不是 target/*.jar：
# mvn package 会**原地重写** target 里的 jar（同一 inode），正在运行的 JVM 之后懒加载
# 内部类就会 NoClassDefFoundError（2026-11-22 导入失败的真实现场）。
# deploy.sh 打包后 cp 到 app.jar.new 再 mv 原子替换，运行中的进程继续持有旧 inode，不受影响。
# 改 unit 的 ExecStart 前先看这条，别改回 target/ 下那个路径。

# 打包
mvn clean package -DskipTests

# 运行测试（2026-11-24 P12 报告封存后全量 360 用例通过）
mvn test
```

### 数据库脚本

```bash
# 执行 DDL（`sql/` 下按序号；脚本自带 GO 分段，52 之前均为幂等可重跑）
DB_SECRET_FILE=config/secret.yml node sql/run-ddl.js sql/50-pa-schema.sql
```

### 前端（Vue 3）

```bash
# 进入前端目录
cd frontend

# 安装依赖
pnpm install

# 开发（端口 3000，host 0.0.0.0，/api 代理到 localhost:8080）——仅本机/内网调试用
# 禁止在聊天会话里直接 pnpm dev（会被 zd-code 重启连坐杀掉），长驻调试也走下面的 systemctl
# 生产形态（2026-11-16 起）：unit 跑构建产物 dist/ + server/static-server.mjs（零依赖静态服务，
# /api 流式代理到 8080，SSE 不缓冲）。不再对外暴露 Vite dev server（源码可读 + HMR 通道）。
# unit: ~/.config/systemd/user/ability-frontend.service（Restart=always，日志走 journald）
export XDG_RUNTIME_DIR=/run/user/1000
systemctl --user start ability-frontend      # 启动
systemctl --user stop ability-frontend       # 停止
systemctl --user restart ability-frontend    # 重启（改了前端代码需先 pnpm build 再重启）
journalctl --user -u ability-frontend -f     # 实时日志

# 临时前台调试才用（pnpm 装在 nvm 下，不在默认 PATH）：
/home/wgd/.nvm/versions/node/v20.20.2/bin/pnpm dev

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
│   │   └── test/                     # 测试（H2 内存库，360 用例）
│   └── pom.xml
│
├── sql/                              # 建表/运维脚本（按序号执行；50 岗位能力模型建表、51 旧模型清理、52 停用旧流程钩子、53 删人员岗位映射、54 清业务数据、55/56 导入会话表；另含 import-pa-*.py 一次性导入脚本）
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
│   ├── server/                       # static-server.mjs：托管 dist/ 并把 /api/* 反代到 8080
│   ├── .env.development              # VITE_API_BASE_URL=/api/v1
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
│
├── oa-job/                           # 泛微定时任务源码（PushMsgJob：轮询 uf_push_msg 发送站内消息）
├── CLAUDE.md                         # 本文件
└── README.md
```

---

## 功能模块

| 模块 | 后端入口 | 前端页面 | 说明 |
|------|----------|----------|------|
| 维度管理（类租户） | `/api/v1/assess-dimensions` | `/assess-dimensions`（仅管理员） | 维度 CRUD、分部/人员授权配置、顶栏维度切换（`/authorized`） |
| 岗位管理 | `/api/v1/pa/posts` | `/pa-posts` | 部门（泛微部门树）+ 岗位名/编码/状态/排序；列出能力项数与权重合计 |
| 岗位能力模型 | `/api/v1/pa/posts/{postId}` | `/pa-models` | 能力类别（左树）/ 能力项（右表：编码/名称/定义/权重/评级主体/排序/状态）/ 等级弹窗 / 分值区间 Tab |
| 发起测评 | `/api/v1/pa/assess-tasks` | `/pa/assess-tasks` | 年度 → 被测人（全部在岗人员）→ **岗位下拉当场选** → **360 按能力项逐项选人**（2026-11-23，方案 17 · P9）：选完岗位后列出该岗位全部 `rater_360=1` 的能力项（类别/能力项/权重，一行一项），每行单独选 360（**2026-11-24 已撤掉表上方的「批量套用」栏**：一行一项自己选已是主路径，多一个批次框反而要人判断「框里的算不算已选」，批量落下去之后哪些逐项改过也看不出来）。入参 `rater360Items: [{itemId, resourceIds}]`（旧 `rater360ResourceIds` 仍兼容，等价于「每个 360 项都派这批人」）；一人只发一条链接、`itemCount`=分到几项 |
| 测评任务发起 | `/api/v1/pa/assess-tasks`（`/page`、`/{id}`、`/void`、`/remind`） | `/pa/assess-task-manage` | 任务列表（进度/状态/总得分/等级说明）、详情抽屉、催办、撤回 |
| 评价填写 | `/api/v1/pa/assess-fill/*`（token 鉴权） | `/pa/assess-fill/:token`、`/m/pa/assess-fill/:token` | 按页面行快照过滤能力项，**360 再按 `uf_assess_task_rater_item` 指派行二次过滤**（该任务有指派行就只出分到本人的项；一行都没有=改造前发的存量任务，回退快照 `rater_360=1` 评全部 360 项）；权重徽标按**整份页面**求和，不按本人可见项（否则只评 1 项的 360 会误报「权重不是 100%」）；等级卡片带名称/分值/描述；强制全覆盖；直接上级与 360 必填评语。**回执页 PC/H5 都显示能力项定义 + 所选档位描述**（`usePaFill.levelOf()` 负责带出 `levelDesc`，缺它两边会同时丢）；**填写态/回执页/只读状态页顶部统一一个返回条**（`components/PaBackLink.vue`，PC `size="pc"` 13px 灰、H5 14px 蓝，文字「我的测评」），跳转前先静默存草稿（补 200ms 防抖的时间差）；H5 我的测评报告页同一份组件、落点也是 `/m/my-assess-tasks` |
| 我的测评 | `/api/v1/pa/assess-tasks/mine`、`/api/v1/pa/reports/my-reports[/years\|/{sheetId}]`、`/api/v1/pa/reports/sub-reports[/exists\|/{sheetId}]` | `/my-assess-tasks`（手机 `/m/my-assess-tasks`、报告 `/m/my-assess-tasks/report/:sheetId`、下属报告 `/m/my-assess-tasks/sub-report/:sheetId`） | **两个 tab：我参与的测评 / 我的测评报告**（2026-11-24 由上下两块改来；PC 用 `el-tabs`，H5 用自绘分段条，H5 切 tab 滚回顶部；对**有直接下属**的人再多一个「下属的测评报告」，见本节末）；tab 角标数字=**条目数**，橙色只在「有真待办」时出现；**默认 tab 由内容定位**（真待办>0 → 待办；无待办有报告 → 报告；都空 → 待办），换年度重新定位。**「真待办」≠ 条目数**：`myTasks` 返回的是 `status IN (0,1)`，已提交的也在列表里（供回看），必须按 `taskStatus===0 && raterStatus===0` 过滤。顶部年度下拉默认激活年；不占菜单位，从 OA 门户/消息链接进入。H5 版能力项一卡一项（不用 el-table）。**第三 tab「下属的测评报告」（2026-11-24，P11）**：只对有直接下属的人出现（`hasSubs`，由 `/sub-reports/exists` 决定），口径=`HrmResource.managerid` 的**直接**下属（不递归、不整部门），不过滤离职；服务端逐条校验 `sheet.managerid==登录人`，不然一律 `40701 测评报告不存在`；**只读**，详情走 `buildDetail(..., showRaterName=false)`，测评人只显示「测评人N」；PC 表格第三列是姓名、点行复用报告抽屉；H5 复用 `MyReportMobile.vue` 靠路由 `meta.subReport` 分流取数/标题；H5 从下属报告返回带 `?tab=subs`，否则默认 tab 逻辑会把人带回自己的报告。**新增接口必须挂在 `DimensionContextFilter.EXEMPT_URI_PREFIXES` 的 `/api/v1/pa/reports/sub-reports` 下**——普通员工没有维度授权，被维度头过滤就会 403 |
| 个人测评报告 | `/api/v1/pa/reports`（`/`、`/{sheetId}`、`/{sheetId}/seal`、`/{sheetId}/unseal`） | `/assess-sheets` | 只读展示算分结果；列表 姓名/年度/部门/岗位/状态/360 人数/总得分/等级说明/**封存状态**；行明细列 自评（标注「不参与计算」）/测评人1（直接上级）/测评人2..N，**评语逐人分段实时读**（不等全员提交）；姓名仅维度/系统管理员可见。**封存（2026-11-24，P12，sql/64）**：封存＝自助视角的可见性开关 + 内容冻结，管理端任何时候都看得见（封存前读实时数据、封存后读 `sealed_json` 快照）；只有系统管理员/本维度管理员能封存（复用 `canSeeRaterName` 口径，详情返回 `canSeal`），前置条件 **`status=2` 且「最近一次任务里没有 `status=0` 的评价人」**（2026-11-24 需求变更：**全部评价人含自评都提交完才能封存**，详情返回 `sealReady` / `pendingRaterCount`，前端按钮置灰并写明还差几人；已作废的评价人不算，否则撤回过的任务永远封不了）；**快照按管理员视角（带姓名）存，读取时按调用人 `maskRaterNames()` 抹姓名**；未封存的报告本人/上级列表里直接不出现、点名读报 `40702`；重复封存/解除未封存/封未出分的报 `40703`、越权报 `403`；封存后禁止破坏性操作（`40209` 撤回/重算、`40604` 重新发起、导入跳过并告警）；列表「封存状态」列 + 抽屉顶部封存条（`canSeal` 为真才画按钮，二次确认写全后果，封存后不关抽屉并重拉详情）；存量数据全部 `seal_status=0`，上线即对自助视角不可见，需 HR 逐份确认。**分数格式统一（2026-11-24，P12-c）**：头部总得分、明细表合计行、明细行三处的综合得分**同一套写法「分数 · 描述」**，共用 `PaReportDrawer.vue` 里的函数式组件 `ScoreWithBand`；头部原来那条独立的等级说明横幅已撤除。**三处同一把尺子（2026-11-24，P12-d）**：头部总得分、合计行、明细行的描述**全部取 `uf_pa_band.band_name`**（岗位分值区间，新手/熟手/能手/高手/专家），明细行对应字段是 `PaReportVO.Row.itemBandName`。判档逻辑只有一份 `utils/PaBandScale.resolve`，评分侧（总得分）和报告侧（每项）共用 —— 各写一遍会漂成「总得分 3.5 能手、单项 3.5 高手」。`itemLevelName`（项刻度，如「开创/专家」）接口仍返回、`uf_pa_item_level` 数据仍在，**页面不再显示**。`PaBandScale` 比较前先 `setScale(2, HALF_UP)`（显示 3.50 就按 3.50 判），没命中区间**返回 null 不抛异常**（批量出报告时别让一个配歪的岗位 500 整份报告）。**老封存快照读取时回填** `backfillItemBand`：只按快照里冻结的 `itemScore` 补 `itemBandName` 这一个展示字段，**不回写 `sealed_json`**（审计凭证，冻分数一字节不动）。H5 `MyReportMobile.vue` 同口径（`.m-score-row`：大号分数 + 区间名 + 右对齐「总得分」，没出分显示「未出分」）。**`ScoreWithBand` 的样式必须写在非 scoped 块**：`h()` 造的节点不带 `data-v-*`，scoped 选择器匹配不到、`:deep()` 也救不回来（合计行的 `<td>` 自身也没有 scope 属性），现已改名 `sc-*` 放进文件末尾的非 scoped 块 |
| 年度设置 | `/api/v1/assess/periods`（`/can-write`） | `/assess-periods`（基础数据维护） | 年度列表 + 状态开关；同公司+维度「进行中」年度至多一个，**同年同维度只能一条**（前端弹窗就地挡 + 服务层 `create()` 再判 + 唯一索引 `UQ_uf_assess_period_year` 兜底）。**写权限按维度给**：该维度的维度管理员（`uf_assess_dimension_auth` `auth_type=2`）或系统管理员，其余人只读（2026-11-23 方案 B） |
| 管理员配置 | `/api/v1/assess-admins` | `/assess-admins`（仅**超级**管理员） | 名单维护：新增按泛微在职人员、移除（配置超管与本人不可删） |
| 能力结构导入 | `/api/v1/pa/import/sessions`（`/current`、`/{key}`、`/{key}/sheets`、`/{key}/answers`、`/{key}/plan`、`/{key}/apply`） | `/ability-structure-import` | 上传 Excel → 模型总览 → **用户勾 sheet** → 列映射识别 → 提问/预览（覆盖策略、撤回进行中任务）→ 一个事务写库 |
| 泛微组织数据 | `/api/v1/sub-companies`、`/departments`、`/job-titles`、`/search/*` 等 | 各选择器组件 | 通过 `JdbcTemplate` 只读查询泛微 `Hr*` 表 |
| 认证（SSO） | `/api/v1/auth/sso/login`、`/sso/callback`、`/me`、`/logout` | SsoCallback/SsoError 页、路由守卫 | 泛微 OAuth2 单点登录，JWT 签发校验（详见 SSO 一节） |

> **旧模型已整体下线（2026-11-21，方案 17 · P3）**：能力类别 / 能力要素 / 能力要素等级 / 组织能力要求 /
> 岗位能力要求 / 人员能力要求 / 能力测评配置 / 外部评分导入 / 智能导入 / 旧测评页与归档 —— 前后端代码、
> 页面路由、`uf_*` 表（`sql/51`）与 8 个 `usp_assess_*` 存储过程全部删除。
> **「人员岗位映射」也一并下线（同日增补）**：岗位改在发起页当场选，该页/接口/`uf_pa_person_post` 表
> 全部删除（`sql/53` 备份后 DROP）。现存的 4 组菜单：基础数据维护（岗位管理 / 岗位能力模型 / 年度设置）/
> 测评任务发起 / 个人测评报告 / 管理员设置（维度设置 / 管理员配置 / 能力结构导入）。

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
  - 维度机制：前端顶栏切换维度 → 请求头 `X-Dimension-Id` → `DimensionContextFilter` → `DimensionContext`（ThreadLocal）→ MyBatis-Plus 租户拦截器（`MyBatisPlusConfig.TENANT_TABLES` 清单内的表自动拼 `WHERE dimension_id=?` 并在 INSERT 自动填充）；JdbcTemplate 原生 SQL（AssessPeriodService 等）手动拼过滤，未列入清单的表（平台表、钩子直写子表）不拦截。
  - 上下文缺失（泛微钩子等）为宽松模式：拦截跳过、原生 SQL 回落兜底维度 id=1。新增表参与隔离时同步维护 `TENANT_TABLES` 清单。前端 `request.ts` 对 `X-Dimension-Id` 缺省兜底 `'1'`，UI 请求永远带头。**注（2026-10-22）**：默认维度已由 `sql/22` 清理删除，id=1 兜底仅剩哨兵语义（读不到数据、无泄漏面）；正式 UI 请求由零租户门控保证必带真实有权维度。
  - 维度与授权：`uf_assess_dimension` / `uf_assess_dimension_auth`（授权对象=分部/人员；**无授权行=仅管理员**［2026-10-22 语义，原为所有人可用］；配置后人员 ID 命中，或人员分部**沿上级链**命中授权分部；分部授权只提供**一级分部**（选择器平铺多选，数据源 `/api/v1/sub-companies/level1`；勾一级=覆盖整条下级分支，服务端链匹配实现，见 14.7/14.8）。接口 `/api/v1/assess-dimensions`。零租户普通用户前端显示引导页（不发业务请求）。详见 `project/能力测评/02-架构设计.md` 第十三、十四节（14.6~14.8）。
- **岗位能力模型（2026-11-21，方案 17 · sql/50）：** 能力类别与能力项**按 部门+岗位 各自一套**（不再全局共享），
  能力项带**权重 %**与**评级主体**（`LEADER` 直接上级 / `RATER360` 360 测评人，二选一），等级（名称/分值/排序/描述）
  按能力项动态配置（`uf_pa_item_level`）；分值区间（`uf_pa_band`）按岗位定义，判级用 **`[lower, upper)`**。
  落点：`PaModelServiceImpl` / `PaPostServiceImpl`。旧「等级标准 / 等级分值 / 测评人权重 /
  类别权重」四套配置**全部删除**（`sql/51` 同步 DROP）。
- **试跑数据清场 + 首批模型导入（2026-11-21）：** `sql/54-wipe-pa-data.sql` 把 11 张业务表备份为 `*_bak_20261121b`
  后清空（保留年度/维度/授权/管理员）；随后用 `sql/import-pa-20261121.py` 走正式 API 导入两岗位 ——
  「数字化推进办公室」下的 **技术人员**（id 13）与 **信息化管理人员**（id 14），维度 3（中达集团），
  各 5 个能力类别 / 8 个能力项 / 40 条等级 / 5 档区间，权重合计各 100%。等级名称形如「入门/新手」，
  区间 `[1,2)/[2,3)/[3,4)/[4,5)/[5,+∞)`。导入脚本只调固定接口，源数据见 `sql/pa_sheets-20261121.json`。
- **算分口径（方案 17 §六）：** 能力项得分 = 直接上级 + 360 测评人（评了该项者）的**算术平均**，未评者**不进分母**；
  自评**可见但不参与计算**；总得分 = **加权平均** `Σ(能力项得分 × 权重) / Σ(权重)`，四舍五入 2 位；
  档位按 `[lower, upper)` 命中，等级文案取区间名称。落点：`PaScoreServiceImpl`（唯一算分入口）。
- **填写页过滤口径（口径 #15）：** 填写页的能力项清单取 `uf_pa_sheet_item.rater_leader / rater_360` 快照，
  不做实时重算 —— 任务是发起时刻的模型快照。**360 再叠一层指派过滤**（2026-11-23，方案 17 · P9）：
  `uf_assess_task_rater_item` 里该任务有行就只出分给本人的项（每人链接附 `itemCount`），
  一行都没有则视为存量任务、按快照评全部 360 项。**新增/老任务的分界在任务级、不在项级** ——
  否则新任务里没人指派的项会被当成「老口径」派给所有 360。报告侧同理：`RaterEntry.assigned=false`
  渲染「未分配」，`true` 且无等级文案才是「未评」；`rater_360=0` 的项（只由上级评）一律「未分配」。
  权重徽标（「权重合计 ≠ 100%」）按整份页面算，说的是岗位模型配得对不对，与本人分到几项无关。
  直接上级与 360 测评人**必须写文字评语**（口径 #14），
  个人测评报告**逐人分段实时读** `uf_assess_task_rater.comment`（一人一段、带测评人N 编号），
  不等全员提交；`uf_pa_sheet.comment_text` 仍在 finish 时合并写入，但页面已不读它（只留作直查/导出）。
  **测评人编号口径**：直接上级 = 测评人1，360 依次 测评人2..N；明细列头与评语编号一致。
  姓名只在维度管理员/系统管理员视角返回，被测评人本人（`/reports/my-reports/*`）恒不返回姓名。
- **自评不阻塞算分（口径 #13）：** 自评未提交不挡算分与出报告，报告里自评列标注「不参与计算」。
- **「先删后插」的整表覆盖式保存禁止只靠拦截器隔离**：写路径必须显式带 `dimension_id`（delete 加 eq、insert set），
  否则宽松模式下 delete 跨维度误删（落点：`PaModelServiceImpl` 等 `Pa*` 服务）。
- **年度唯一激活（2026-10-22）：** 同一公司+维度下「进行中」（status=0）年度至多一个。`AssessPeriodService` 的
  create（新增即激活）与 changeStatus(0)（激活）都会**先自动关闭**同维度其他进行中年度（先关后开以兼容唯一索引），
  再打开目标；库层兜底为筛选唯一索引 `UX_assess_period_active`（sql/24）。**注（2026-11-21）：** 年度关闭/重开
  **不再冻结报告快照**（`uf_assess_report_snapshot` 已随 P3 删除），语义由「年度关闭后不再接受新评价、
  已提交的仍可查看」保证；个人测评报告每次打开直接读 `uf_pa_*`。
- **运维钩子（2026-11-21，sql/52）：** 泛微流程 1840 的节点 7143 原挂 `AssessHookAction`（旧模型评价回传）；
  旧表删除后该钩子会在每次触发时报对象名无效，已 `UPDATE workflowactionset SET isused = 0 WHERE id = 4341` 停用。
  回滚=置 1（但需同时恢复 `sql/51` 删掉的表，实际不可逆）。

### 认证与权限（2026-10-21 已落地）

- **登录：** 泛微 OAuth2 单点登录（weaversso 认证中心，详见「单点登录（SSO）」一节与 `project/能力测评/02-架构设计.md` 第七节）。授权码换 JWT：`AuthController`（/api/v1/auth/sso/*、/me、/logout）→ `SsoLoginService` → `OaSsoClient` → HrmResource（loginid，status=1 在职）。
- **鉴权：** `assess.auth.enforced=true`（默认）时业务接口全部要求 `Authorization: Bearer <JWT>`；`JwtAuthFilter` 解析、未认证统一返回 401 JSON。设 false 回退匿名（仅开发调试，禁止公网）。
- **权限：** 账号与组织权威在泛微端，本系统只读；维度授权已升级为按 JWT 人员身份强制过滤（`UserContext`）。请求级越权：`DimensionContextFilter` 对已登录用户的 `X-Dimension-Id` 按授权表校验，无权维度直接 403（`AssessDimensionService.isAuthorizedForUser`）；无登录身份的内部调用维持宽松。
- **管理员（2026-10-22 已落地）：** 身份 = 配置超管（`assess.bootstrap-admins`，OA loginid，不可界面删除、用于引导首批管理员）∪ `uf_assess_admin` 表（sql/21，「管理员配置」页维护）。实时查库判定、**不进 JWT**（撤销即时生效，避免 2 小时空窗）。管理员：维度管理接口全放行、顶栏切换器返回全部启用维度、请求级维度校验豁免（即可逐租户查看全部数据；跨租户汇总视图未实现）；`AdminService.requireAdmin()` 是各管理接口统一门。**角色分级（14.9）：**「管理员配置」页与 `/api/v1/assess-admins` 仅超管（`requireSuperAdmin`，纯配置判定）；`/me` 增加 `superAdmin` 字段。详见 `project/能力测评/02-架构设计.md` 第十四节。
- **维度管理员（2026-11-23，sql/58 合并后）：** = `uf_assess_dimension_auth` 里 `auth_type=2` 的人员授权，**不再单独一张表**。判定入口 `AssessDimensionService.isDimensionAdmin(dimensionId, resourceId)`。目前只授两条权：① 测评报告里能看见 360 测评人的真实姓名（`PaReportServiceImpl.canSeeRaterName`）；② 维护**当前维度**的年度设置（`AssessPeriodService.requireWritePermission`，方案 B）。**不**给维度本身的增删改与授权配置（`AssessDimensionController` 仍是 `requireAdmin`），也不给管理员名单（`requireSuperAdmin`）。
- 备用「Ecology 账密校验」方案（`登录密码校验方案.md`）未启用。

### 能力结构导入的识别口径（2026-11-22 改造）

**分工：模型看排版，代码读内容。** 模型只回答「第几行是表头、每列分别是什么」，不抄任何单元格内容；
内容由 `PaImportDraftAssembler` 按列坐标回 Excel 读原文。所以模型<b>没有机会改写原文</b> ——
能力项定义、行为描述那几十段长文本永远是客户写的那份。

**两条路径：**

1. `PaImportStructureAi.detectColumns` —— **首选**。每表只答十几行（`headerRow`/`firstDataRow`/
   `lastDataRow`/各列字母/各档表头），25 张表一次调用就能给完（实测 57 秒，输出约 3K）。
2. `PaImportStructureAi.detect` —— **兜底**。某张表模型标 `layout=OTHER`（一项跨多行、或横排、
   或看不出列含义）时，对**那一张**走老的逐格识别。单张输出量远在额度内。

**为什么必须改：** 老路径要求模型把每个格子都抄一遍。25 张表时输出必然吃穿 `max_tokens=16384`，
返回 null，用户看到「模型没能读懂这份文件的结构」—— 其实模型读懂了，只是说不完。
（2026-11-22 实测：同一份 25 表文件整份逐格 → null，单张逐格 → 成功；改列映射后一次给完。）
**不要再把「整份文件塞给逐格识别」当成修复方向**，那条路撞的是额度上限，换模型/调参数都不稳。

**取值的几个确定性规则（都有单测锁）：**
- 档位分值/档名一律回 Excel 读该列表头（`1分 · 入门/新手` → 1 / `入门/新手`），不采信模型抄的那份。
  表头解析不出分值时按列序给 1、2、3…（通用约定），不为此刷一堆问题。
- 类别列是合并单元格时按块沿用块首值（`PaExcelRaw.Sheet.valueAt` 自动回落左上角）。
- `合计/小计/备注/说明/注` 开头的行不是能力项，跳过；但「合计能力的运用」这种正常名字不拦。
- 权重合计≈1 才按小数口径 ×100；既不像 1 也不像 100 就不换算，交校验器报错让人裁决。
- 评价主体认不出来标 `-1` 并记对账，**不默认成上级**（那等于替客户改评级关系）。
- 评价主体**原文照抄进 `rater_text`**：那一列的写法（「内部客户360＋直接上级」「跨部门360＋直接上级」）
  业务含义不同，但拆成 rater_leader / rater_360 两个开关后长得一样，不存原文用户在页面上看不出区别。
  原文只显示、不参与算分；认不出来时原文也照样留，用户才知道是哪句话没认出来。

**用户必须自己勾 sheet。** 总览阶段不预选任何工作表 —— 曾替用户勾上前 5 张模型判 IMPORT 的表，
结果「必须让用户选」这条防线形同虚设，25 张表一次就建了 5 个岗位。

---

## 数据库设计

**数据库：** SQL Server（与泛微共用，库名 `ecology`）

**业务表（`uf_pa_*`，由 `sql/50-pa-schema.sql` 创建）：**

| 表 | 说明 |
|----|------|
| uf_pa_post | 岗位（部门 dept_id + 岗位名/编码/状态/排序；软删） |
| uf_pa_category | 能力类别（**按岗位各自一套**，`post_id` 归属） |
| uf_pa_item | 能力项（名称/定义/**权重 %**/**评级主体** rater_leader+rater_360/**主体原文** rater_text/排序/状态；`category_id` 归属） |
| uf_pa_item_level | 能力项等级（名称/分值/排序/描述，数量动态） |
| uf_pa_band | 分值区间（按岗位定义 lower/upper + 等级文案，判级用 `[lower, upper)`） |
| uf_pa_sheet | 测评页（**一人一年一页**，`(period_id, resource_id)` 唯一；头部快照 姓名/部门/岗位/年度）。**封存列（sql/64）**：`seal_status`(0/1) / `seal_time` / `seal_by_loginid` / `seal_by_name` / `sealed_json`（封存那一刻整份报告的 JSON，含姓名，读取时按调用人脱敏） |
| uf_pa_sheet_item | 测评页能力项行（发起时的模型快照，含 `rater_leader`/`rater_360` 过滤位与 `rater_text` 原文） |
| uf_pa_rating | 逐项评级（评价人 role=LEADER/RATER360/SELF + item/level + score）；**不含整份评语** |

**平台表（保留）：**

| 表 | 说明 |
|----|------|
| uf_assess_dimension | 测评维度（类租户）；原默认维度 id=1 已删除（2026-10-22，sql/22），现存维度从 3 起 |
| uf_assess_dimension_auth | 维度授权（auth_type 1-分部 2-人员；无授权行=仅管理员） |
| uf_assess_admin | 系统管理员名单（全局角色，loginid 唯一，物理删除小表，`sql/21`） |
| uf_assess_period | 年度（同公司+维度「进行中」唯一，`sql/24` 筛选唯一索引） |
| uf_assess_task | 测评任务（与 `uf_pa_sheet` 多对一；状态/进度/总得分/等级文案；`client_count`=360 人数快照、`rating_count`=有效能力项数快照） |
| uf_assess_task_rater | 任务评价人（role=LEADER/RATER360/SELF + token + 提交状态 + 草稿 + **整份评语 `comment`**） |
| uf_assess_task_rater_item | **360 按项指派**（`task_id` / `rater_id` / `item_id`，`UNIQUE(rater_id, item_id)`，`sql/60`）。一人可指多项，故用关系表而非在 rater 表加列。**不进 `TENANT_TABLES`**：租户靠 `task_id` 落地。**存量任务不回填** |
| uf_push_msg | 泛微站内消息待发队列（`oa-job` 的 `PushMsgJob` 轮询发送） |
| uf_assess_migration_log | 迁移日志（`sql/NN` 执行记录） |

**已 DROP（2026-11-21，方案 17 · P3 · `sql/51-pa-cleanup.sql`，删前全量备份为 `*_bak_20261121`）：**
`uf_ability_category` / `uf_ability_element` / `uf_ability_element_level` / `uf_org_ability_req` /
`uf_position_ability_req(_item/_new)` / `uf_user_ability_req(_item/_new)` / `uf_score_weight` /
`uf_assess_weight_ability` / `uf_assess_weight_assessor` / `uf_assess_grade_level` / `uf_assess_level_score` /
`uf_assess_sheet` / `uf_assess_sheet_row` / `uf_assess_sheet_rating` / `uf_assess_sheet_log` /
`uf_assess_comment` / `uf_assess_report_snapshot` / `uf_assess_external_import_log` / `uf_assess_import_*` /
`uf_assess_agent_*` 等共 25 张，以及 `uf_assess_task*` 的历史数据（表保留、数据清空）。
同时 DROP 8 个存储过程：`usp_assess_rate` / `usp_assess_recalc` v8 / `usp_assess_external_score` /
`usp_assess_external_score_clear` / 等（算分已全部收归 Java `PaScoreServiceImpl`）。

**已作废（不再创建）：** `uf_sso_token`、`uf_jwt_token`——SSO 改走 OAuth2 后 ticket/JWT 均不落库。
**已作废（未被使用）：** `uf_assessment_task`、`uf_assessment_score`、`uf_sys_config`。

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
| `pa.import.ai.*` | 能力结构导入的模型网关：`base-url`/`api-key`/`model` 走环境变量 `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_MODEL`（部署时写在 `~/.config/ability-backend.env`，由 systemd drop-in `10-ai-gateway.conf` 注入）；`timeout-seconds` 默认 120、`max-tokens` 默认 16384（推理模型的思考过程也计入额度）、`max-rounds` 10、`session-ttl-minutes` 30。缺凭证时服务照常启动，只是导入的自动识别降级 |

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

- **产品形态已上线（2026-11-16）：** 前后端均跑构建产物。前端改完必须 `pnpm build` 再 `systemctl --user restart ability-frontend`；后端必须 `mvn package` 再重启。**存盘不再自动生效。**
- **工具输出掩码可污染字符串字面量（2026-10-21 实际踩过）：** 本工作环境会把形似密钥的字符串以 `***` 掩码展示；若照抄掩码结果写回文件，会把真实值毁成字面 `***`（曾使 `utils/auth.ts` 三个 localStorage key 同名，token 被 user 覆盖 → SSO 登录成功但请求永远 401 循环）。用 `awk 'NR==x{print length($0)}'` 按字节长度核验文件真实内容，勿凭显示值断言；给密钥类字面量取名避开 token/jwt/secret 等词。
- **建表脚本完整：** `sql/` 01–53 全部可幂等重跑；`50-pa-schema.sql` 建新模型 8 张表，`51-pa-cleanup.sql` 清旧表，`52-retire-assess-wf-hook.sql` 停用旧流程钩子，`53-drop-pa-person-post.sql` 备份后删掉人员岗位映射表。`54-wipe-pa-data.sql` 是**数据清场脚本**（备份为 `*_bak_20261121b` 后清空 11 张业务表，保留年度/维度/授权/管理员），非 DDL，重跑会让备份表和数据都变空，只在明确要清历史时执行。
- **Maven 不在 PATH：** 本机使用 `~/apache-maven-3.9.16/bin/mvn`（下文命令中的 `mvn` 均指该路径）。
- **报告封存（P12，2026-11-24）：** 封存是**两件事同时发生** —— ① 自助视角（本人 `/my-reports*`、直接上级 `/sub-reports*`）的可见性开关；② 内容冻结（`uf_pa_sheet.sealed_json` 存封存那一刻的整份报告，之后所有视角含管理端都读它）。**管理端不受可见性限制**：HR 要确认的正是「还没封存的那一份」，封存前就看不到就没法判断该不该封。前置条件缺一不可（管理员身份 / **全员已提交含自评** / `status=2` / 未封存），**校验顺序是先数「还差几人」再查「有没有出分」**——两个都不满足时用户要的是「还差谁」这个可执行信息，不是「还没出分」这种只能干等的话；解除封存清空 5 个字段，快照一并删掉。快照存**管理员视角**（带姓名），读取时按调用人决定是否 `maskRaterNames()` —— 反过来「先抠掉再恢复」做不到。错误码 `40702`（未封存，与 `40701`「不存在」故意分开：归属已过，含糊成不存在会让人反复重进页面找 bug）、`40703`（状态不对）、`403`（越权）、`40209`/`40604`（封存后禁止撤回/重算/重新发起）。**存量全部 `seal_status=0`，上线即对自助视角全不可见**，这是确认过的取舍：那些报告 HR 没逐份看过，批量封存等于代签。自助页只读无按钮，空态文案要写清「要等 HR 确认封存」，因为空的原因有两个（没评完 / 等确认）。
- ~~后端测试无法编译~~（已解决：过时用例已删除；2026-10-21 修复 H2 测试库 `schema-test.sql` 与维度/客户评价/新版岗位表实体字段漂移，`mvn test` 全量 42 用例通过；2026-10-22 流程需评价开关落地后全量 73 通过；**2026-11-21 方案 17 · P3 后 `mvn test` 全量 196 用例通过；2026-11-22 导入改造后全量 252 用例通过；2026-11-23 评语逐人分段 + 我的测评报告后全量 270 用例通过；2026-11-23 年度设置维护权下放维度管理员后全量 285 用例通过；2026-11-23 年度重复校验后全量 288 用例通过；2026-11-24 P11 下属报告后全量 326 用例通过；2026-11-24 P12 报告封存后全量 343 用例通过；2026-11-24 P12-b 封存门槛改为「全员评完」后全量 360 用例通过**）。
- **H2 测试库 SQL Server 兼容：** `uf_assess_period.year` 是 H2 2.x 保留字，测试 URL 需带 `NON_KEYWORDS=YEAR`（已在 `application-test.yml`）；生产同步/归档原生 SQL 使用 `SYSDATETIME()`，H2 无此函数，经 `schema-test.sql` 末尾 `CREATE ALIAS` 映射到测试类 `H2SqlServerFunctions`。新增 SQL Server 专有函数/语法进测试路径时，照此补别名，不要为测试改生产 SQL。
  两条实测踩过的细则：① **`ISNULL` 在 H2 没有，字符串兜底一律写 ANSI 的 `COALESCE`**（两者在 SQL Server 上等价），不要指望补别名 —— H2 的 `CREATE ALIAS` **不支持同名重载**，多写一个 `isNull(String,String)` 会让启动脚本整段失败、整个 ApplicationContext 起不来；② 分页/取首行用 `OFFSET … FETCH NEXT` 时**必须带 `ORDER BY`**，SQL Server 缺了报语法错而 H2 容忍，单测抓不到。
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
- **岗位能力模型重构方案（当前主线，含 P1–P4 实施记录）：`/home/wgd/ws/project/能力测评/17-岗位能力模型重构方案.md`**

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
