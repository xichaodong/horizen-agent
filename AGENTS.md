# Horizen Agent contributor instructions

- Target Java 17 and use the Maven Wrapper. Run `./mvnw verify` before delivery.
- Apply `scripts/format.sh apply` after source changes. Java uses four-space Google Java Format (AOSP); XML and frontend use repository Prettier rules. `mvnw verify` checks Java formatting; CI checks XML/frontend formatting too.
- Do not use Java `record`; use ordinary Lombok POJOs and preserve explicit validation where required.
- Use Lombok for plain getters, setters and assignment-only constructors. Use standard `getX`/`isX` accessors for project POJOs; preserve required SPI method names and method annotations. Keep explicit methods when they validate, copy mutable data, synchronize or implement behavior. Do not add setters merely to reduce code.
- Document production classes, fields, methods and constructors with meaningful Javadoc. Explain responsibility, units, null semantics, ownership and state/version boundaries where relevant. Preserve useful existing comments and avoid repeating identifiers. Run `java scripts/CheckJavaDocs.java` before delivery.
- Write explanatory source comments in Chinese, including production code, tests, frontend and scripts. Keep technical identifiers, protocol names and third-party copyright/license notices in their original form.
- Import annotation and class types and use their simple names in source. Keep fully-qualified type names only where an actual naming conflict requires them; Spotless and `scripts/check-java-style.py` check this rule.
- Validated domain snapshots and commands must not expose generated setters. Keep JSON creators explicit; preserve wire compatibility when changing accessors or constructors.
- Keep the runtime independent of any application, internal service, or company network.
- Use AgentScope Core for the agent loop. Keep harness policies replaceable through supported APIs.
- Keep domain procedures in skills. Do not hardcode application-specific diagnostic workflows.
- Add observability alongside each execution feature. Correlate runs, model calls, and tools.
- Do not include credentials, private skills, real user conversations, or internal endpoints in fixtures.
- Keep process documents in the ignored local `docs/` or `doc/` directories. Do not publish those directories or the local `horizen-agent-web/design-qa.md` report, and do not link public documentation to them.
- Use one local `.env.yml` with lowercase hierarchical configuration keys and one commented `.env.yml.example` template. Keep credentials in the ignored local file and optional integrations disabled in the template. Scripts and live-test readers use this same file.
- Keep host execution separate from sandbox execution. A local process is not a sandbox.
- Preserve upstream license notices when porting code and document the exact public revision.
- Scope changes to the current milestone; avoid speculative interfaces for unfinished modules.
