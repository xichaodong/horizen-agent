package dev.horizen.agent.adapter.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.domain.artifact.ArtifactEventCollector;
import dev.horizen.agent.domain.askuser.AskUserEventCollector;
import dev.horizen.agent.domain.presentation.PresentationEventCollector;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class HarnessAgentStopReasonTest {
    @TempDir
    Path workspace;
    private HarnessAgent agent;
    private HarnessAgentEventMapper mapper;

    @BeforeEach
    void setup() {
        agent =
                HarnessAgent.builder()
                        .name("stop-reason-test")
                        .workspace(workspace)
                        .model(
                                new ChatModelBase() {
                                    @Override
                                    public String getModelName() {
                                        return "synthetic";
                                    }

                                    @Override
                                    protected Flux<ChatResponse> doStream(
                                            List<Msg> messages,
                                            List<ToolSchema> tools,
                                            GenerateOptions options) {
                                        return Flux.empty();
                                    }
                                })
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableTranscript()
                        .disableSubagents()
                        .disableShellTool()
                        .build();
        mapper = new HarnessAgentEventMapper(agent);
    }

    @AfterEach
    void cleanup() {
        agent.close();
    }

    private final AgentTurnRequest request =
            AgentTurnRequest.builder()
                    .ownerKey("synthetic-owner")
                    .sessionId("session")
                    .turnId("turn")
                    .message("synthetic task")
                    .build();

    @Test
    void everyPinnedStopReasonIsExplicitlyClassified() {
        for (GenerateReason reason : GenerateReason.values()) {
            var events = map(new AgentResultEvent(message().withGenerateReason(reason)));
            switch (reason) {
                case PERMISSION_ASKING, TOOL_SUSPENDED, MIDDLEWARE_STOP_REQUESTED -> assertTrue(events.isEmpty());
                case MODEL_STOP, STRUCTURED_OUTPUT -> assertEquals(
                        AgentRuntimeEvent.Type.TURN_COMPLETED, events.get(0).getType());
                case INTERRUPTED -> assertEquals(
                        AgentRuntimeEvent.Type.TURN_CANCELLED, events.get(0).getType());
                default -> {
                    assertEquals(
                            AgentRuntimeEvent.Type.TURN_FAILED,
                            events.get(0).getType(),
                            reason.name());
                    assertFalse(events.get(0).getText().isBlank());
                }
            }
        }
    }

    @Test
    void unknownWireStopReasonCannotFallBackToUpstreamsNormalStopDefault() {
        Msg invalid =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .textContent("partial result")
                        .metadata(Map.of(Msg.METADATA_GENERATE_REASON, "FUTURE_STOP_REASON"))
                        .build();
        AgentRuntimeEvent event = map(new AgentResultEvent(invalid)).get(0);
        assertEquals(AgentRuntimeEvent.Type.TURN_FAILED, event.getType());
        assertEquals("UNKNOWN_STOP_REASON", ((Map<?, ?>) event.getDetails()).get("errorCode"));
        assertTrue(event.getText().contains("partial result"));
    }

    @Test
    void limitedChildRetainsProvenanceAndDoesNotEndTheParent() {
        AgentResultEvent child =
                new AgentResultEvent(message().withGenerateReason(GenerateReason.MAX_ITERATIONS));
        child.withSource("session/worker");
        AgentRuntimeEvent event = map(child).get(0);
        assertEquals(AgentRuntimeEvent.Type.SUBAGENT_RESULT, event.getType());
        assertEquals("error", event.getStatus());
        assertEquals("session/worker", event.getSource());
        assertTrue(event.getText().contains("执行次数限制"));
    }

    private Msg message() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("partial result").build();
    }

    private List<AgentRuntimeEvent> map(AgentResultEvent event) {
        return mapper.map(
                request,
                event,
                System.nanoTime(),
                new HashMap<>(),
                new ArtifactEventCollector(),
                new PresentationEventCollector(),
                new AskUserEventCollector(),
                RuntimeContext.builder().build());
    }
}
