package dev.horizen.agent.adapter.agentscope.workspace.snapshot;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotKey;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotPointerRepository;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.*;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

class SandboxSnapshotCheckpointTest {
    @TempDir Path workspace;

    @Test
    void uploadFailureProducesFailureInsteadOfSuccessfulTurn() {
        var store = new InMemoryAgentStateStore();
        RuntimeContext bindings = bindings("owner", true);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("snapshot-test")
                        .workspace(workspace)
                        .stateStore(store)
                        .model(new FixtureModel())
                        .disableSubagents()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableWorkspaceContext()
                        .disableShellTool()
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent a, RuntimeContext context, String prompt) {
                                        context.put(
                                                SandboxAcquireResult.class,
                                                bindings.get(SandboxAcquireResult.class));
                                        context.put(
                                                SandboxContext.class,
                                                bindings.get(SandboxContext.class));
                                        return Mono.just(prompt);
                                    }
                                })
                        .build();
        try (var runtime = new HarnessAgentRuntime(agent)) {
            var events =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("owner")
                                            .sessionId("session")
                                            .turnId("turn")
                                            .message("fixture")
                                            .build())
                            .onErrorComplete()
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertFalse(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_FAILED));
        }
    }

    @Test
    void sharedSnapshotPointersAreOwnerScopedEvenForSameConversationId() throws Exception {
        var store = new InMemoryAgentStateStore();
        RuntimeContext a = bindings("owner-a", false);
        RuntimeContext b = bindings("owner-b", false);
        SandboxSnapshotCheckpoint.save(a, store, "snapshot-test");
        SandboxSnapshotCheckpoint.save(b, store, "snapshot-test");
        var shared = new SessionSandboxStateStore(store, "snapshot-test");
        var keyA =
                SandboxIsolationKey.resolve(IsolationScope.USER, a, "snapshot-test").orElseThrow();
        var keyB =
                SandboxIsolationKey.resolve(IsolationScope.USER, b, "snapshot-test").orElseThrow();
        assertEquals("owner-a", shared.load(keyA).orElseThrow());
        assertEquals("owner-b", shared.load(keyB).orElseThrow());
    }

    @Test
    void permanentPointerWriteFailureDoesNotPublishASuccessfulCacheCheckpoint() throws Exception {
        var store = new InMemoryAgentStateStore();
        RuntimeContext call = bindings("owner", false);
        var pointers =
                new WorkspaceSnapshotPointerRepository() {
                    public Optional<String> findSnapshotId(WorkspaceSnapshotKey key) {
                        return Optional.empty();
                    }

                    public void saveCommitted(WorkspaceSnapshotKey key, String id) {
                        throw new IllegalStateException("database failed");
                    }
                };
        assertThrows(
                SandboxSnapshotCheckpoint.SnapshotCheckpointException.class,
                () -> SandboxSnapshotCheckpoint.save(call, store, "snapshot-test", pointers));
        assertTrue(
                new SessionSandboxStateStore(store, "snapshot-test")
                        .load(
                                SandboxIsolationKey.resolve(
                                                IsolationScope.USER, call, "snapshot-test")
                                        .orElseThrow())
                        .isEmpty());
    }

    private static RuntimeContext bindings(String owner, boolean fail) {
        SandboxState state = new SandboxState() {};
        state.setSessionId(owner);
        WorkspaceSnapshotRepository snapshots =
                new WorkspaceSnapshotRepository() {
                    public void upload(String id, InputStream archive) {}

                    public InputStream download(String id) {
                        return InputStream.nullInputStream();
                    }

                    public boolean exists(String id) {
                        return true;
                    }
                };
        state.setSnapshot(
                new RemoteSnapshotSpec(new RepositoryRemoteSnapshotClient(snapshots)).build(owner));
        Sandbox sandbox =
                (Sandbox)
                        Proxy.newProxyInstance(
                                Sandbox.class.getClassLoader(),
                                new Class[] {Sandbox.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getState")) return state;
                                    if (method.getName().equals("stop") && fail)
                                        throw new IOException("snapshot storage unavailable");
                                    if (method.getName().equals("isRunning")) return true;
                                    return null;
                                });
        SandboxClient<SandboxClientOptions> client =
                new SandboxClient<>() {
                    public Sandbox create(
                            WorkspaceSpec w, SandboxSnapshotSpec s, SandboxClientOptions o) {
                        return sandbox;
                    }

                    public Sandbox resume(SandboxState s) {
                        return sandbox;
                    }

                    public void delete(Sandbox s) {}

                    public String serializeState(SandboxState s) {
                        return s.getSessionId();
                    }

                    public SandboxState deserializeState(String json) {
                        return state;
                    }
                };
        return RuntimeContext.builder()
                .userId(owner)
                .sessionId("same-session")
                .put(SandboxAcquireResult.class, SandboxAcquireResult.selfManaged(sandbox))
                .put(
                        SandboxContext.class,
                        SandboxContext.builder()
                                .client(client)
                                .isolationScope(IsolationScope.USER)
                                .build())
                .build();
    }

    private static final class FixtureModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "fixture";
        }

        @Override
        protected Flux<ChatResponse> doStream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("result").build()))
                            .build());
        }
    }
}
