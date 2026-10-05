package dev.horizen.agent.application.turn;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.execution.turn.*;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalStore;
import dev.horizen.agent.runtime.api.AgentRuntime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class TurnServicesTest {
    @Test
    void rejectsPartialDistributedPortsBeforeExecution() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new TurnServices.PersistencePorts(
                                mock(SessionTurnStore.class),
                                mock(ApprovalStore.class),
                                mock(PresentationStore.class),
                                mock(TurnTimelineStore.class),
                                null));
    }

    @Test
    void rejectsMismatchedExecutionAndRecoveryIdentity() {
        var ports =
                new TurnServices.PersistencePorts(
                        mock(SessionTurnStore.class),
                        mock(ApprovalStore.class),
                        mock(PresentationStore.class),
                        mock(TurnTimelineStore.class),
                        mock(TurnEventChannel.class));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TurnServices.distributed(
                                policy("first"),
                                mock(AgentRuntime.class),
                                mock(TurnRequestFactory.class),
                                null,
                                ports,
                                new TurnRecoveryPolicy(
                                        "other", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                                new LeaseRenewalPolicy(10, 1, 0, Duration.ofMillis(10)),
                                mock(TurnControlChannel.class),
                                Object::toString));
    }

    @Test
    void standaloneLeavesRuntimeToHostAndRejectsWorkAfterClose() {
        var runtime = mock(AgentRuntime.class);
        var services =
                TurnServices.standalone(
                        policy(null), runtime, mock(TurnRequestFactory.class), null);
        services.close();
        services.close();
        verify(runtime, never()).close();
        var error =
                assertThrows(
                        ApplicationError.class,
                        () ->
                                services.start(
                                        new ExecutionIdentity("owner", "actor"),
                                        new ChatCommand("session", "hello", "request", List.of())));
        assertEquals(ApplicationError.Code.UNAVAILABLE, error.getCode());
    }

    private TurnExecutionPolicy policy(String instance) {
        return new TurnExecutionPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30), instance);
    }
}
