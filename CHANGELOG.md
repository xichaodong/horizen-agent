# Changelog

## Unreleased — 0.1.0 preview preparation

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

Compatibility: Java APIs, stream contracts and persistence schemas are provisional. The Maven version remains `0.1.0-SNAPSHOT`; this entry does not declare a published release.
