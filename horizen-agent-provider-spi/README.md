# Horizen Tool Provider SPI

This module is the dependency-light integration boundary for external tool providers. A Java
adapter may depend on it without depending on AgentScope, Spring, Reactor, or Horizen Core. A
provider written in another language can implement the same DTOs as JSON over HTTP.

The only compile dependency is Jackson annotations. Lombok is `provided` and is not required at
runtime.

## Protocol v1

`ProviderProtocol.CURRENT_VERSION` is sent in every catalog and invocation request and response.
Horizen fails closed when a peer explicitly returns a different version.

The two operations are:

```java
ProviderCatalogResponse catalog(ProviderCatalogRequest request);
ProviderInvokeResponse invoke(ProviderInvokeRequest request);
```

Catalog discovery has two scopes:

- `registration`: returns the maximum set of schemas that the runtime may register. It is not an
  authorization grant and does not require owner coordinates.
- `turn`: returns the tools currently authorized for one trusted `ownerKey/sessionId/turnId`.

`ownerKey`, `sessionId`, `turnId`, and `toolCallId` are supplied by the Horizen host. They are not
model arguments and must not be copied into a tool's `inputSchema`.

## Governance

Each `ToolContract` declares its behavior before execution:

```text
name / description / inputSchema
readOnly / riskLevel / approvalPolicy
timeoutSeconds / idempotent / concurrencySafe / supportsCancellation
group.id / group.description / group.activeByDefault / group.activateOnSkill
```

`riskLevel` describes risk; `approvalPolicy=required` is the explicit request to enter Horizen's
single approval state machine. `ProviderResultStatus` deliberately has no `approval_required`
value. A provider must not start a second approval flow after Horizen has invoked the tool.

Provider results can carry model-safe structured data, presentation blocks, and artifact
references. Artifact bytes remain in the configured object store; the protocol transports stable
references rather than Base64 payloads.

`catalogVersion` is reserved for later catalog refresh/observability. Protocol v1 consumers must
not treat it as an authorization cache key: the Turn catalog and provider-side authorization are
still authoritative.

## Compatibility

Changes that remove fields, change wire values, or make an optional field required need a new
protocol version. New optional fields may be added within v1. Run the compatibility tests before
publishing:

```sh
./mvnw -pl horizen-agent-provider-spi -am test
```
