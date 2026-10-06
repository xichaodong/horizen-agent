# Horizen Agent

A Java agent runtime for applications that need isolated conversations, observable tool execution, and resumable interactions. Built on AgentScope Core/Harness.

[中文](README.zh-CN.md) · [Web host](horizen-agent-web/README.md) · [Contributing](CONTRIBUTING.md)

**Preview status:** `0.1.0-SNAPSHOT`. APIs, event contracts, and persistence schemas may change before a stable release.

## Why Horizen Agent?

AgentScope provides the model/tool loop and Harness capabilities. Horizen adds a host-facing runtime API and application services around that loop:

- **Isolated Sessions and Turns:** opaque owner identities, overlapping-Turn rejection, cancellation, and state recovery.
- **Observable execution:** model/tool events, correlated JSONL traces, and optional trace export.
- **Resumable conversations:** server-owned execution, reconnectable SSE, and distributed approval/ask-user recovery.
- **Replaceable adapters:** tool Provider contracts, Skills, storage, artifacts, and remote execution.

Use it to embed an agent in a Java application or explore the included local HTTP/SSE host. The runtime does not require a company account or company network. Business workflows belong in Skills and application adapters.

## Run the complete application with Docker

```sh
docker compose up --build -d
```

Open <http://127.0.0.1:8787/>. The image includes the production frontend and Java backend and starts the credential-free scripted demo by default. The Compose port is bound to loopback, and workspace files use the `agent-data` volume.

For a real model, copy the template first (an existing `.env.yml` is preserved):

```sh
cp -n .env.yml.example .env.yml
```

Set these four fields in `.env.yml`. Leave the other template settings at their defaults:

```yaml
horizen:
  agent:
    model-mode: remote
    api-key: 'your-own-model-service-key'
    base-url: 'https://your-model-provider.example/v1'
    model-name: 'your-provider-model-id'
```

Use your provider's OpenAI-compatible API base URL, without `/chat/completions`. Start the published image directly; Java, Node.js and a local build are not required:

```sh
HORIZEN_AGENT_IMAGE=ghcr.io/xichaodong/horizen-agent:v0.1.0-preview.3 \
  docker compose -f compose.yml -f compose.configured.yml up -d --no-build
```

To build from the current source instead, use `docker compose -f compose.yml -f compose.configured.yml up --build -d`.

The override mounts `.env.yml` read-only at startup; it is excluded from the image build. The startup process prepares the configuration with private permissions, then runs Java as UID 10001. Optional external services remain controlled by the same YAML file.

To try the credential-free demo, use the published image without a local build:

```sh
HORIZEN_AGENT_IMAGE=ghcr.io/xichaodong/horizen-agent:v0.1.0-preview.3 docker compose up -d --no-build
```

Use `docker compose logs -f agent` to inspect startup and `docker compose down` to stop. See [Compose](compose.yml), [configured override](compose.configured.yml) and [Dockerfile](Dockerfile).

## Try it without credentials

Requirements: JDK 17+, Node.js 22, npm, and Python 3.10+ for repository checks. The Maven Wrapper downloads Maven 3.9.9. The first build needs access to public Maven/npm registries.

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

Start the scripted backend in terminal 1, from the repository root:

```sh
./scripts/demo.sh
```

Start the frontend in terminal 2:

```sh
cd horizen-agent-web
npm ci
npm run dev
```

Open <http://127.0.0.1:5173/>. Send `Hello, Horizen!` and expect a reply beginning with `scripted:`. This mode exercises the real execution and streaming pipeline with deterministic replies; it does not answer questions using a language model. It uses a separate development workspace under `target/local-demo`, disables external integrations, and skips private `.env.yml` imports. No API key, database, sandbox account, or Horizen service is needed.

`AGENT_WEB_PORT` changes the backend port. For a different backend, set `AGENT_WEB_API_TARGET` before `npm run dev`; `AGENT_WEB_DEV_PORT` changes the frontend port. Press Ctrl+C in each terminal to stop.

For a command-line model/tool trace demo:

```sh
./mvnw install -DskipTests
./mvnw -pl horizen-agent-examples exec:exec -Ddemo.mode=no-horizen
cat target/traces/demo.jsonl
```

For a minimal Java embedding example, see [RuntimeEmbeddingDemo.java](horizen-agent-examples/src/main/java/dev/horizen/agent/examples/RuntimeEmbeddingDemo.java). After installing the modules, run:

```sh
./mvnw -pl horizen-agent-examples exec:exec -Ddemo.mainClass=dev.horizen.agent.examples.RuntimeEmbeddingDemo
```

## Connect a real model

The Web host supports OpenAI-compatible chat endpoints through AgentScope. `ARK_*` are the current host's environment-variable names; the endpoint can be changed to another compatible provider. Choose the URL and model identifier from your provider's documentation.

```sh
cp -n .env.yml.example .env.yml
# Edit .env.yml: set model-mode to remote and fill api-key, base-url, model-name.
./mvnw install -DskipTests
./mvnw -pl horizen-agent-web spring-boot:run
```

Use the same frontend command as above. The Java host reads the credential; it is never sent to the browser. Keep secrets in process environment or ignored local configuration. The normal host optionally imports `.env.yml`; `scripts/demo.sh` explicitly disables these imports. The commented [YAML template](.env.yml.example) lists the settings and keeps optional integrations disabled.

## Configure a separate vision model

The primary model handles text reasoning and tool calls. Configure an independent image-capable model in the existing `horizen.agent.vision` section of the same `.env.yml`:

```yaml
horizen:
  agent:
    vision:
      enabled: true
      model-name: 'your-provider-vision-model-id'
      base-url: '' # Empty reuses the primary endpoint; a separate provider is supported.
      api-key: '' # Empty reuses the primary credential; keep real credentials in local configuration.
```

An explicit vision model name is required. A different provider origin requires an explicit vision credential; the primary credential is never implicitly sent to another service. Disabled vision never falls back to the primary model, and the scripted demo does not call a remote vision provider.

Uploaded images enter the conversation as Artifact references. The agent calls `vision_analyze` when needed; the independent vision model receives the image and returns text for the primary model to reason about. `browser_vision` uses the same vision model after capturing a screenshot. Raw image blocks and signed image URLs are not directly injected into primary-model requests.

Image upload and analysis require Artifact storage; browser screenshots also require the sandbox integration. `multimodal` retains image-count, byte and URL-expiry limits. The former `direct-image-input-enabled` primary-model image switch is removed. Context compression remains independently configurable under `context.compression-model-*`.

## Choose integrations as needed

| Capability | Default local demo | Optional integration |
| --- | --- | --- |
| Model | Scripted replies | OpenAI-compatible endpoint |
| Conversation state | Local development state | MySQL + Redis for distributed operation |
| Tool execution | Native demo tools | [Provider contracts](horizen-agent-provider-spi/README.md) |
| Remote commands/files | Disabled | [E2B HTTP adapter](horizen-agent-sandbox-e2b-http/README.md) |
| Artifact/workspace persistence | Local development adapters | BOS and workspace snapshots |
| Trace export | Disabled; CLI demo writes JSONL | Horizen trace-contract exporter |
| Workspace publications | Disabled | Workspace release service |
| Cloud evaluation | Disabled | Cloud-evaluation service |

`local` mode supports model chat, conversation context within the running process, SSE reconnection to an active turn, and cancellation. It does not store the formal session directory or message history; reloading the page or restarting the application does not guarantee chat-history recovery. Persistent history, approval/clarification recovery, and cross-instance recovery require MySQL + Redis with `distributed` mode. The Docker `agent-data` volume stores workspace files and does not automatically provide a conversation database.

The Maven reactor compiles these adapters, but using the basic runtime does not require their services. The YAML template is in the repository root as `.env.yml.example`; the host does not automatically import them.

For distributed mode, create the MySQL schema before startup:

```sh
mysql -u root -p horizen_agent < horizen-agent-storage-jdbc/src/main/resources/schema/mysql.sql
```

Configure MySQL and Redis using the [YAML template](.env.yml.example). The application does not automatically create or migrate database tables. For existing installations, stop all Agent instances and apply the relevant SQL upgrades under `horizen-agent-storage-jdbc/src/main/resources/schema/` before restarting.

## Runtime and HTTP boundaries

The execution model is `Session → Turn → Model/Tool Step`. The server owns execution; closing a browser detaches its observer. Cancel explicitly to stop a Turn. Only one non-terminal Turn may exist for the same owner and Session.

The local Web host binds to `127.0.0.1` and uses a fixed `web-tester` identity. It is a development host. A shared deployment must provide authentication and a trusted `ExecutionIdentityResolver`; changing the listening address alone does not provide user isolation. Reject unauthenticated requests and enforce authorization at the host boundary. Shared deployments also need TLS, resource limits, and streaming proxies with response buffering disabled.

Text trace capture is off by default. JSONL redacts common secret field names, but cannot remove arbitrary credentials or personal data in free text. Synthetic examples explicitly opt into text capture. Local host shell is disabled; remote commands require an explicit sandbox adapter.

## Modules

| Module | Responsibility |
| --- | --- |
| `common`, `domain` | Shared utilities, validated models and repository ports |
| `runtime-api`, `core` | Host-facing Reactor API and AgentScope implementation |
| `application` | Session, Turn, interaction, artifact and workspace use cases |
| `provider-spi`, `provider-codec` | Dependency-light external tool contracts and JSON codec |
| `tools` | Reusable browser, file, memory, web, vision, process and session tools |
| `storage-jdbc`, `storage-redis`, `storage-bos` | Persistence adapters |
| `sandbox-e2b-http` | Remote execution adapter |
| `skill-horizen-http`, `gateway-http`, `observability-horizen` | Optional HTTP integrations |
| `evaluation-http` | Optional cloud-evaluation execution adapter |
| `examples`, `web` | Runnable examples and the local Web console |

All module directories use the `horizen-agent-` prefix. The [Web host guide](horizen-agent-web/README.md) explains the composition and request flow; the [Provider guide](horizen-agent-provider-spi/README.md) describes external tool contracts.

## Development and project status

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

GitHub CI verifies Java 17/21 on Linux, the frontend, repository content, and a credential-free Web smoke test. Live model, database, object-store and sandbox tests require explicit local configuration and are not part of the public CI baseline. The CI commands above are reproducible locally. See [contribution rules](CONTRIBUTING.md) and [changelog](CHANGELOG.md).

Run `./scripts/format.sh apply` after source edits, or `./scripts/format.sh check` to validate without changing files. The project uses the native IntelliJ IDEA formatter with shared project settings and a pinned version, matching **Code → Reformat Code** in IDEA. CI checks the same format; Maven verification checks compilation and tests. See [IDE and formatter setup](CONTRIBUTING.md#changes-and-review).

Maintainer: **xichaodong**. Use this repository's Issues for bugs and feature requests and pull requests for contributions. Security reports follow [SECURITY.md](SECURITY.md). See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [third-party notices](THIRD_PARTY_NOTICES.md). Third-party dependencies retain their own licenses. The console stylesheet and bundled SVG artwork are created for this project; there are no external image or font dependencies.
