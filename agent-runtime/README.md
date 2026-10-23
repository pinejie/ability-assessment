# 智能导入 agent 运行时（MCP 工具）源码

本目录是测评后端一键导入流水线所派生 claude CLI 的工具层源码：

- `mcp/cp_server.mjs`：具名 MCP 工具（人员/年度/非流程要素/评价人查询、文件解析并上报行数、
  交付稿校验与提交、唯一合法收场 submit_outcome）。DB 凭据运行时从工作区 `.mcp.json` 解析，
  本源码不落任何秘密。
- `mcp/cp-mcp-config.json`：注册为 `cp` MCP server 的**模板**——`CP_AGENT_TOKEN` 为占位符
  `${CP_AGENT_TOKEN}`，实际值与后端 `agent.internal-token`（`backend/config/secret.yml`）一致，
  部署时替换。后端 spawn 实际读取的是工作区根的 `.mcp.json`（含真实令牌，不入 Git）。
- `mcp/cp-settings.json`：工具白名单（最小权限：9 个具名工具，deny 通用读写/网络/命令）。

## 部署位置与更新方式

运行时目录：`/home/wgd/ws/cp/mcp`（工作区 `/home/wgd/ws/cp`，含 sessions/uploads 运行时数据，
不属于本仓库）。更新步骤：

1. `cp -r agent-runtime/mcp/* /home/wgd/ws/cp/mcp/`（`cp-mcp-config.json` 仅模板，运行时用的是
   `.mcp.json`，一般无需覆盖）；
2. 目标目录执行 `npm install --omit=dev`（依赖仅首次或 package.json 变化时需要）；
3. 无需重启后端——claude CLI 回合按会话派生，下一个导入批次自动加载新代码。

会话生命周期（闲置 30 分钟/上限 2 小时）与 uploads 目录清理由后端 AgentSessionService 负责。
