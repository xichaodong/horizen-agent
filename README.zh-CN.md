# Horizen Agent

基于 AgentScope Core/Harness 的 Java Agent Runtime，为应用提供隔离会话、可观察的工具执行和可恢复的用户交互。

[English](README.md) · [Web 宿主指南](horizen-agent-web/README.md) · [贡献指南](CONTRIBUTING.md)

**当前为预览版 `0.1.0-SNAPSHOT`。** 稳定版之前，Java API、事件契约和持久化结构仍可能调整。

## 项目解决什么问题

AgentScope 提供模型与工具循环、Harness 策略。Horizen 在其上补充应用接入所需的运行接口与用例：

- 不透明 owner 身份、Session/Turn 隔离、重叠执行拒绝、取消和状态恢复。
- 模型与工具事件、关联 JSONL Trace、可选观测上报。
- 服务端持有执行、SSE 断线重连，以及分布式审批和澄清恢复。
- 可替换的 Provider 工具契约、Skill、存储、Artifact 和远程执行适配器。

适合在 Java 应用中嵌入 Agent，也可以通过本地 Web 工作台体验。基础 Runtime 不需要公司账号或公司网络。业务流程放在 Skill 和应用适配器中。

## 用 Docker 启动完整应用

```sh
docker compose up --build -d
```

打开 <http://127.0.0.1:8787/>。镜像包含构建后的前端和 Java 后端，默认启动无需凭据的脚本模型 demo。Compose 端口只绑定本机，工作区文件使用 `agent-data` 数据卷。

接入真实模型时，先复制配置模板（已有 `.env.yml` 时保留原文件）：

```sh
cp -n .env.yml.example .env.yml
```

在 `.env.yml` 中修改这四项，其余配置可以保持模板默认值：

```yaml
horizen:
  agent:
    model-mode: remote
    api-key: '填写你自己的模型服务 Key'
    base-url: 'https://your-model-provider.example/v1'
    model-name: '填写服务商提供的模型标识'
```

`base-url` 使用服务商的 OpenAI-compatible API 基础地址，不包含 `/chat/completions`。然后直接运行已发布镜像，无需安装 Java、Node.js 或在本机编译：

```sh
HORIZEN_AGENT_IMAGE=ghcr.io/xichaodong/horizen-agent:v0.1.0-preview.4 \
  docker compose -f compose.yml -f compose.configured.yml up -d --no-build
```

如需从当前源码构建，使用 `docker compose -f compose.yml -f compose.configured.yml up --build -d`。

启动时只读挂载 `.env.yml`，镜像构建不会包含该文件。启动入口准备好私有权限的配置后，以 UID 10001 运行 Java。可选外部服务也在这一份 YAML 中配置。

只想体验无凭据 demo 时，可以直接使用对应镜像：

```sh
HORIZEN_AGENT_IMAGE=ghcr.io/xichaodong/horizen-agent:v0.1.0-preview.4 docker compose up -d --no-build
```

用 `docker compose logs -f agent` 查看启动状态，用 `docker compose down` 停止。文件见 [Compose](compose.yml)、[配置覆盖](compose.configured.yml) 和 [Dockerfile](Dockerfile)。

## 无需凭据，先跑起来

需要 JDK 17+、Node.js 22 和 npm；仓库检查使用 Python 3.10+。Maven Wrapper 会下载 Maven 3.9.9，首次构建需要访问公开 Maven/npm 源。

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

终端一，从仓库根目录启动脚本模型后端：

```sh
./scripts/demo.sh
```

终端二，启动前端：

```sh
cd horizen-agent-web
npm ci
npm run dev
```

打开 <http://127.0.0.1:5173/>，发送 `Hello, Horizen!`，预期收到以 `scripted:` 开头的回复。
此模式通过真实运行与流式链路产生确定性回复，适合体验发送、取消和会话切换。它使用 `target/local-demo` 下独立的开发工作区，禁用外部集成，并跳过私人 `.env.yml` 导入；无需模型 Key、数据库、沙箱账号或 Horizen 服务。回复由脚本生成，不代表真实模型的理解能力。

后端端口可通过 `AGENT_WEB_PORT` 修改。前端通过 `AGENT_WEB_API_TARGET` 指定后端，通过 `AGENT_WEB_DEV_PORT` 修改端口。两个终端分别按 Ctrl+C 关闭。

命令行模型/工具 Trace 示例：

```sh
./mvnw install -DskipTests
./mvnw -pl horizen-agent-examples exec:exec -Ddemo.mode=no-horizen
cat target/traces/demo.jsonl
```

最小 Java 接入示例见 [RuntimeEmbeddingDemo.java](horizen-agent-examples/src/main/java/dev/horizen/agent/examples/RuntimeEmbeddingDemo.java)。安装各模块后执行：

```sh
./mvnw -pl horizen-agent-examples exec:exec -Ddemo.mainClass=dev.horizen.agent.examples.RuntimeEmbeddingDemo
```

## 接入真实模型

Web 宿主通过 AgentScope 支持 OpenAI-compatible 接口。目前环境变量沿用 `ARK_*` 名称，但可配置其他兼容服务。模型名称和 URL 以服务商文档为准。

```sh
cp -n .env.yml.example .env.yml
# 编辑 .env.yml：model-mode 改为 remote，填写 api-key、base-url、model-name。
./mvnw install -DskipTests
./mvnw -pl horizen-agent-web spring-boot:run
```

前端启动方式相同。凭据由 Java 宿主读取，不会发送到浏览器。普通启动会可选导入本地 `.env.yml`；demo 入口明确禁用这些导入。带注释的 [YAML 模板](.env.yml.example) 列出了各项配置，可选集成默认关闭。

## 单独配置视觉模型

主模型负责文本推理和工具调用，图片由独立视觉模型处理。在同一份 `.env.yml` 的 `horizen.agent.vision` 节点填写：

```yaml
horizen:
  agent:
    vision:
      enabled: true
      model-name: '填写服务商提供的视觉模型标识'
      base-url: '' # 留空复用主模型地址，也可填写另一家视觉服务的基础地址。
      api-key: '' # 留空复用主模型 Key，也可使用独立凭据；真实 Key 只放本地配置。
```

视觉模型名称必须显式填写。若视觉服务与主模型的地址属于不同服务商，必须填写独立 Key，不能隐式复用主模型凭据。未开启视觉功能时，不会自动使用主模型读图。脚本模型 demo 不调用远端视觉服务。

上传的图片只向主模型提供 Artifact 引用；需要看图时，Agent 调用 `vision_analyze`，独立视觉模型读取图片并返回文字，主模型据此继续回答。`browser_vision` 截图后也使用这一个视觉模型。原始图片和图片访问链接不会直接注入主模型请求。

图片上传与读取需要配置 Artifact 存储，浏览器截图还需要启用沙箱。`multimodal` 节点保留数量、字节容量及链接有效期限制，旧的 `direct-image-input-enabled` 主模型直接看图开关已移除。上下文压缩模型仍通过 `context.compression-model-*` 独立配置。

## 按需启用集成

| 能力 | 本地 demo | 可选集成 |
| --- | --- | --- |
| 模型 | 脚本回复 | OpenAI-compatible 模型服务 |
| 会话状态 | 本地开发状态 | MySQL + Redis 分布式运行 |
| 外部工具 | 演示工具 | [Provider 工具契约](horizen-agent-provider-spi/README.md) |
| 远程命令与文件 | 关闭 | [E2B HTTP](horizen-agent-sandbox-e2b-http/README.md) |
| Artifact 与工作区持久化 | 本地开发适配器 | BOS 与工作区快照 |
| 观测上报 | 关闭；CLI 示例输出 JSONL | Horizen Trace 上报 |
| 工作区发布 | 关闭 | 工作区发布服务 |
| 云端评测 | 关闭 | 云端评测服务 |

`local` 模式支持模型聊天、同一进程内的上下文、活跃执行的 SSE 重连和取消，但不保存正式会话目录或消息历史；刷新页面、重启应用后不保证恢复聊天记录。持久化历史、审批/澄清恢复和跨实例恢复需要配置 MySQL + Redis，并切换为 `distributed` 模式。Docker 的 `agent-data` 卷用于工作区文件，不会自动保存会话数据库。

构建会编译这些适配器，使用基础 Runtime 不要求接通对应服务。配置模板位于仓库根目录，命名为 `.env.yml.example`，应用不会自动导入模板文件。

分布式模式需要先创建 MySQL 表：

```sh
mysql -u root -p horizen_agent < horizen-agent-storage-jdbc/src/main/resources/schema/mysql.sql
```

按 [YAML 模板](.env.yml.example) 配置 MySQL 与 Redis。应用启动不会自动建表或迁移；升级已有数据库时，需停止所有 Agent 实例，执行 `horizen-agent-storage-jdbc/src/main/resources/schema/` 下对应升级 SQL 后再启动。

## 使用边界

执行结构是 `Session → Turn → Model/Tool Step`。同 owner、同 Session 只允许一个非终态 Turn。浏览器断开仅移除观察者，停止执行需要显式取消。

Web 默认绑定 `127.0.0.1`，采用固定的 `web-tester` 身份，适用于本地开发。多人部署需要认证，并通过可信的 `ExecutionIdentityResolver` 提供身份；仅修改监听地址不能实现用户隔离。宿主应拒绝未认证请求并实施授权，配置 TLS、资源限制，并关闭流式代理的响应缓冲。

文本 Trace 默认关闭；按字段名脱敏不能清理自由文本中任意位置的敏感内容。示例只对合成数据开启文本采集。本机 Shell 已禁用，远程命令需要显式启用沙箱适配器。

模块职责见 [英文 README](README.md#modules)，装配与请求流程见 [Web 宿主指南](horizen-agent-web/README.md)，外部工具接入见 [Provider 工具契约](horizen-agent-provider-spi/README.md)。

## 开发与协作

```sh
python3 scripts/check-public-content.py
bash scripts/check-architecture-boundaries.sh
./mvnw --batch-mode --no-transfer-progress clean verify
cd horizen-agent-web
npm ci
npm run lint
npm test
npm run build
```

GitHub CI 覆盖 Linux 下 Java 17/21、前端、公开内容检查和无凭据 Web 冒烟测试。真实模型、数据库、对象存储和沙箱测试需要显式配置，不属于公开 CI 基线。上述命令可在本地复现 CI 基线，贡献规则见 [贡献指南](CONTRIBUTING.md)，版本调整见 [变更记录](CHANGELOG.md)。

安装前端依赖后，用 `./scripts/format.sh apply` 一键格式化，或用 `./scripts/format.sh check` 只检查格式。
代码统一使用 IDEA 原生格式化器，项目共享 `.idea/codeStyles/Project.xml` 和 `.editorconfig`；在 IDEA 中使用“代码 → 重新格式化代码”即可。CI 固定同一版本检查格式，Maven 验证负责编译和测试。安装及版本说明见 [贡献指南](CONTRIBUTING.md#changes-and-review)。

维护者：**xichaodong**。问题和建议通过当前仓库 Issues 提交，贡献通过 PR 提交。安全问题遵循 [SECURITY.md](SECURITY.md)，贡献规则见 [CONTRIBUTING.md](CONTRIBUTING.md)。

许可证：Apache-2.0，见 [LICENSE](LICENSE)、[NOTICE](NOTICE) 和 [第三方声明](THIRD_PARTY_NOTICES.md)。工作台样式与 SVG 为本项目独立制作，无外部图片或字体依赖。
