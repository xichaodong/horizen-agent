# Horizen Agent E2B HTTP adapter

This module connects AgentScope Harness to an E2B-compatible service that exposes:

- `POST /sandboxes`, `POST /sandboxes/{id}/restore`, `GET /sandboxes/{id}`, and `DELETE /sandboxes/{id}`;
- `POST /process.Process/StartSync` with a plain JSON body;
- `X-API-KEY` for management calls and `X-Access-Token` for envd calls.

It implements the public AgentScope `SandboxClient`, `Sandbox`, and `SandboxState` extension
points. AgentScope itself remains an ordinary Maven dependency.

```java
HttpE2bFilesystemSpec spec = new HttpE2bFilesystemSpec()
        .apiKey(System.getenv("AGENT_E2B_API_KEY"))
        .apiBaseUrl("https://api.agent-sandbox.example")
        .runtimeBaseUrlPattern("https://49983-{sandbox_id}.agent-sandbox.example")
        .templateId("code-sandbox")
        .workspaceRoot("/tmp/horizen-agent")
        .snapshotSpec(new NoopSnapshotSpec());

spec.isolationScope(IsolationScope.USER);
```

Commands currently wait for `StartSync` to finish. The production host uses a fresh sandbox per
Turn and does not restore workspace files after release. Files that must survive a Turn belong in a
dedicated artifact or object-storage path.
