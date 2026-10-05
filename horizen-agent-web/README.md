# Agent Web 宿主阅读指南

本模块是可运行的 Agent 服务宿主：提供 HTTP/SSE 接口，装配 AgentScope Runtime，管理后台 Turn，并连接存储、工具和沙箱。前端 React/Vite 测试台位于 `src/`；Java 宿主位于 `src/main/java/dev/horizen/agent/web/`。

首次体验请从仓库根目录运行 `./scripts/demo.sh`，另一个终端在本模块运行 `npm ci` 和 `npm run dev`。
这会使用独立的本地 demo 工作区，并禁用私人配置导入和外部集成。
界面使用本项目的 `console.less` 与本地 SVG。

## 包结构

| 包 | 职责 | 建议先读 |
| --- | --- | --- |
| 根包 | Spring Boot 启动入口 | `AgentWebApplication` |
| `api` | HTTP 接口、请求校验、响应转换与接口门面 | `AgentController`、`AgentService` |
| `identity` | 从可信宿主上下文解析身份 | `ExecutionIdentityResolver` |
| `bootstrap.runtime` | Runtime、工具及工作区装配 | `AgentHostConfiguration`、`AgentRuntimeFactory` |
| `bootstrap.storage` | MySQL、Redis、BOS 装配及状态 | `StorageConfiguration`、`RuntimeStorage` |
| `bootstrap.model` | 主模型、压缩模型与脚本模型 | `AgentModelFactory` |
| `bootstrap.workspace` | 工作区管理及迁移入口 | `WorkspaceManagementConfiguration` |
| `config` | 配置参数与校验 | 各类 `*Properties` |
| `execution` | Turn 执行、暂停、取消、状态持久化和租约恢复 | `AgentTurnCoordinator`、`TurnExecutionManager` |
| `stream` | SSE 连接、背压和 Redis 事件回放 | `SseConnectionManager`、`RedisTurnEventBridge` |
| `tools` | 宿主注册的具体工具及实现细节 | `SandboxBrowserTool`、`SandboxPatchTool` |

这些包用于表达宿主内部职责。可复用的 Agent 运行接口和 AgentScope 适配位于 `horizen-agent-core`；会话、审批、文件等用例位于 `horizen-agent-application`。

## 启动时先看装配

入口：[AgentHostConfiguration](src/main/java/dev/horizen/agent/web/bootstrap/runtime/AgentHostConfiguration.java)。

```text
AgentWebApplication
  → 扫描 config 配置与 bootstrap / identity / api 组件
  → AgentHostConfiguration.agentService()
      → RuntimeStorage：存储适配器
      → AgentRuntimeFactory：AgentScope Runtime
          → AgentModelFactory：模型
          → AgentToolRegistry：工具
      → 创建查询、交互、执行协调服务
      → 创建 AgentService 接口门面
```

`AgentService` 接收已装配的依赖，不再负责创建模型、存储或沙箱。基础设施由 `AgentHostResources` 持有；关闭时先停止 Turn 执行，再关闭上报、Skill 轮询、事件回放、存储和快照资源。

## 一次聊天请求按主链路读

入口：[AgentTurnCoordinator](src/main/java/dev/horizen/agent/web/execution/AgentTurnCoordinator.java)。

```text
AgentController.streamChat()
  → AgentService.streamChat()
  → AgentTurnCoordinator.startTurn()
      → 校验幂等和活跃 Turn，创建正式记录
      → AgentTurnRequestFactory：准备附件、Skill 和可信上下文
  → AgentTurnCoordinator.executeTurn()
      → TurnExecutionManager：持有后台执行订阅
      → HarnessAgentRuntime.stream()：进入 AgentScope
      → TurnEventPersistence：推进正式状态并保存事件
  → SseConnectionManager：向浏览器发送输出
```

浏览器断开只结束观察连接，后台执行继续。显式取消通过执行协调器处理。
分布式模式下，MySQL 保存正式状态和历史，Redis 保存工作上下文及当前 Turn 增量，BOS 保存文件内容，E2B 提供执行环境。

## 验证

从仓库根目录运行：

```sh
scripts/check-architecture-boundaries.sh
./mvnw verify
```

配置和启动说明见[项目 README](../README.zh-CN.md)，可选集成参数见[配置模板](../.env.yml.example)。真实服务验收需要单独显式启用对应测试标志；普通 `verify` 不调用这些服务。

## Refactored ownership

The standalone entry point and API routes are unchanged. Persistence is assembled by `StorageConfiguration` and `AgentJdbcConfiguration`; API/application beans by `AgentApiConfiguration`; runtime dependencies by `AgentHostConfiguration` and `RuntimeInfrastructureConfiguration`. `SseConfiguration` owns bounded writer and heartbeat executors. Object-store management no longer creates a second unqualified datasource.

The frontend separates `components/`, `hooks/`, `stream/` and `utils/`; `App.jsx` composes those responsibilities. Java memory/browser/file tools live under `web.integration.tool`. No remote Maven embedding/starter module is introduced.
