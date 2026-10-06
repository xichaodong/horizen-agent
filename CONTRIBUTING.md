# Contributing

Horizen Agent is maintained by xichaodong. Use repository Issues to report bugs, suggest features or discuss changes, and pull requests to contribute. The project is in preview; discuss changes to public contracts or storage schemas before implementing broad changes.

## Development

Use JDK 17+, the Maven Wrapper, Node.js 22 and Python 3.10+. Keep private settings in ignored `.env.yml` files. Start with [the project README](README.md) and [the Web guide](horizen-agent-web/README.md).

```sh
python3 scripts/check-public-content.py
bash scripts/check-architecture-boundaries.sh
./mvnw --batch-mode --no-transfer-progress clean verify
python3 scripts/smoke-demo.py
cd horizen-agent-web
npm ci
npm run lint
npm test
npm run build
```

Container delivery is checked separately with a complete image:

```sh
docker build -t horizen-agent:local .
bash scripts/smoke-container.sh horizen-agent:local
```

The smoke check covers bundled frontend assets, scripted SSE, non-root Java and the public YAML template mounted with 0600 permissions. It never loads the real local `.env.yml`. Version tags such as `v0.1.0-preview.1` run the full CI baseline before publishing AMD64/ARM64 images to GHCR and a packaged application with checksums to GitHub Releases. Prereleases receive their exact version tag rather than a `latest` image tag.

Live integrations are opt-in and are not required for an ordinary contribution. Provide synthetic fixtures or deterministic local tests for new behavior. Report any relevant live verification separately, with configuration and secrets omitted.

Keep development notes and process documents local. The root `docs/` and `doc/` directories and `horizen-agent-web/design-qa.md` are ignored and must not be added to the public repository. Public guides must be self-contained or link to tracked source and examples.

## Changes and review

Production classes, fields, methods and constructors have Javadoc. Describe their actual
responsibility, units, null values, version/state boundaries and resource ownership rather
than translating an identifier. `java scripts/CheckJavaDocs.java` checks declaration
coverage; reviewers still verify that descriptions match the implementation. Frontend
module and hook comments explain lifecycle, cancellation and asynchronous state ownership.
Write explanatory source comments in Chinese, including tests, frontend and scripts.
Keep technical identifiers, protocol names and third-party copyright/license notices in their original form.

Formatting uses the native IntelliJ IDEA formatter, pinned to the version/build in
[scripts/intellij-formatter.json](scripts/intellij-formatter.json). The shared project scheme is
[Project.xml](.idea/codeStyles/Project.xml), enabled by [codeStyleConfig.xml](.idea/codeStyles/codeStyleConfig.xml).
The repository [.editorconfig](.editorconfig) defines four-space source indentation and two-space JSON/YAML indentation.
Open the project in IDEA and use **Code → Reformat Code**; select the **Project** scheme under
**Settings → Editor → Code Style**. No Google Java Format plugin or Prettier integration is required.
Associate `.env.yml.example` with the YAML file type in IDEA to format the template in the editor.

Use `./scripts/format.sh apply` to format public source files or `./scripts/format.sh check` to validate
without writing changes. These commands detect the installed macOS application; on other systems set
`HORIZEN_IDEA_HOME` to the installation directory. Linux x64 can install the pinned formatter with
`python3 scripts/format-idea.py check --install`. The official archive SHA-256 is pinned and the installation
is cached outside the repository. The formatter uses an isolated temporary IDE configuration, allowing the
editor to remain open. It never loads private IDE plugins, formats real `.env.yml`, or visits local process documents.
CI uses the same native engine and settings. Building with `./mvnw verify` does not download an IDE.
Update the pinned version/checksum together when adopting a new formatter version.

Use explicit imports and simple names for annotations and class types. Fully-qualified names in source
are reserved for genuine naming conflicts. `scripts/check-java-style.py` enforces that rule; use IDEA's
import actions when adding references. Do not enable a second formatter on save or before committing.

- Keep domain models independent of runtime frameworks; follow the dependency boundaries in `AGENTS.md`.
- Target Java 17. Use ordinary POJOs/Lombok rather than Java records, and preserve validation and explicit JSON creators.
- Generate plain getters, setters and assignment-only constructors with Lombok. Project POJOs use `getX`/`isX`; preserve SPI names, Jackson/Spring annotations, synchronization, defensive copies and validation. Generate setters only for fields that are intended to be mutable.
- Keep application-specific workflows in Skills or adapters. Keep private systems out of public contracts and fixtures.
- Add observability for execution behavior. Preserve Turn, Session, model and tool correlation.
- Describe the problem, resulting behavior and relevant verification in the pull request. Document compatibility effects and schema upgrades.
- Before committing, run `python3 scripts/check-public-content.py --staged`. Review generated artifacts and diffs as well as scanner results.

## Licensing and attribution

Submit only material you are authorized to contribute under the project's Apache-2.0 license. Preserve third-party notices. Copied or adapted code/assets must identify the public upstream URL, exact revision, license and modifications in `THIRD_PARTY_NOTICES.md`; do not remove an existing copyright header.

Contributions are reviewed under [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report security issues through [SECURITY.md](SECURITY.md).
