# Changelog

## v0.1.0-preview.3 — 2026-10-06

- Upgrade the transitive `source-map-js` dependency to 1.2.2 to address [GHSA-68fv-2mgg-jv7q](https://github.com/advisories/GHSA-68fv-2mgg-jv7q). Preview 2 did not publish artifacts because the dependency audit blocked its release.

- Preserve the completed Turn status when the host releases its source subscription after receiving the final reply. Resource cleanup no longer changes a successful real-model conversation into a cancelled Turn.
- Verify real-model chat, multi-turn context, native tool execution, SSE reconnection, cancellation during generation, and a subsequent turn after cancellation using synthetic inputs.
- Show the four YAML model settings and the configured published-image command in both getting-started guides. Clarify that persistent conversation history and human-interaction recovery require the distributed storage integrations.

## v0.1.0-preview.1 — 2026-10-06

- AgentScope-based runtime with owner/session isolation, Turn lifecycle and correlated events.
- Local Web/SSE host, deterministic demos and an independent console design with bundled SVG assets.
- Provider SPI, workspace/Skill publication, approval/ask-user recovery and structured outputs.
- Optional JDBC, Redis, BOS, E2B, tracing and evaluation adapters.
- Credential-free startup, Java embedding example, public-content checks and demo smoke verification.
- English/Chinese getting-started documentation, contribution and security policies.
- Replace redundant hand-written accessors and assignment-only constructors with Lombok. Java callers now use standard `getX`/`isX` POJO accessors; required SPI method names and JSON creators remain stable. Hosts embedding the preview Java API must update former fluent accessor calls and rebuild.
- Use one `.env.yml` for local configuration, live acceptance and workspace migration commands. Publish one commented `.env.yml.example` template with lowercase hierarchical keys and a configuration guide; split files and local `.properties` files are no longer imported.
- Return a fixed public message for unexpected chat failures, with correlated, redacted diagnostics recorded on the server. Align multipart file limits with Artifact capacity and reject oversized uploads before reading their body, with an explicit HTTP 413 response.
- Decouple Turn cancellation and host deadlines from blocking event persistence. Serialize observed facts, select one terminal owner, and retain the session until pending writes and local cleanup settle. Apply the configured JDBC statement budget to ordinary mapped SQL as well as lease renewal.

- Package the production frontend with the Web application, add an offline-by-default container and a read-only YAML startup override, verify both container modes in CI, and publish versioned AMD64/ARM64 images and application checksums after the full baseline passes.

- Preserve the evaluation admission limit while handing completed worker slots to the next request. Completion callbacks can submit a successor without transient executor rejection, and shutdown settles queued handoffs.

Compatibility: Java APIs, stream contracts and persistence schemas are provisional. The Maven version remains `0.1.0-SNAPSHOT`; the preview tags identify published artifacts.
