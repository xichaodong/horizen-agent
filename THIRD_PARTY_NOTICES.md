# Third-party notices

The project's own code, console stylesheet and bundled SVG artwork are distributed under Apache-2.0. Preserve applicable notices when distributing dependencies. This document describes direct dependency families; it is not a complete transitive dependency license audit.

| Dependency family | Use | Upstream |
| --- | --- | --- |
| AgentScope Java Core/Harness/extensions | Agent loop, policies, model and state adapters | https://github.com/agentscope-ai/agentscope-java |
| Apache Maven Wrapper | Build bootstrap scripts; original headers retained | https://maven.apache.org/wrapper/ |
| Reactor, Jackson, Lombok | Streams, JSON and Java boilerplate | https://projectreactor.io/ · https://github.com/FasterXML/jackson · https://projectlombok.org/ |
| Spring Boot, MyBatis, HikariCP, Jedis | Host and persistence adapters | https://spring.io/projects/spring-boot · https://mybatis.org/ · https://github.com/brettwooldridge/HikariCP · https://github.com/redis/jedis |
| BCE Java SDK | Optional BOS adapter | https://github.com/baidubce/bce-sdk-java |
| jsoup, Apache PDFBox | Content extraction | https://jsoup.org/ · https://pdfbox.apache.org/ |
| React, Ant Design Icons | Frontend rendering and dependency-provided icons | https://react.dev/ · https://github.com/ant-design/ant-design-icons |
| Vite, Less, ESLint | Frontend build and lint tooling | https://vite.dev/ · https://lesscss.org/ · https://eslint.org/ |
| Spotless, Google Java Format, Prettier/XML | Source formatting tools | https://github.com/diffplug/spotless · https://github.com/google/google-java-format · https://prettier.io/ · https://github.com/prettier/plugin-xml |
| JUnit, ArchUnit, H2 | Test-only dependencies | https://junit.org/ · https://www.archunit.org/ · https://h2database.com/ |

Exact direct versions are declared in the Maven POMs and frontend `package-lock.json`. Maven Wrapper scripts retain their Apache license headers. Review upstream licenses and transitive dependencies when preparing a binary distribution.

No application-specific upstream code port is declared here. Future copied/adapted code or artwork must record its public source, exact revision, license, original notice and local modifications.
