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

Install frontend dependencies with `npm --prefix horizen-agent-web ci`, then run
`./scripts/format.sh apply` to format Java, XML, JavaScript/JSX, Less, HTML and JSON/YAML.
Use `./scripts/format.sh check` to verify without changing files. Java and source files use
four-space indentation; JSON/YAML use two. Dynamic MyBatis Mapper XML is excluded from the generic XML formatter to preserve its SQL text.
Maven verification and CI reject formatting drift. IntelliJ should use the repository
`.editorconfig`; the pinned formatters determine the canonical output.

Use explicit imports and simple names for annotations and class types. Fully-qualified
names in source are reserved for genuine naming conflicts. Spotless shortens eligible
type references and organizes imports when `./scripts/format.sh apply` runs.
The import-style check also rejects unnecessary package prefixes on annotations and
static type references, while allowing actual import/declaration conflicts.

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
