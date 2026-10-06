package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.turn.ChatCommand;

import org.junit.jupiter.api.Test;

import java.util.List;

class ChatIdentifierBoundaryTest {
    @Test
    void httpAndApplicationKeysKeepThe128CharacterContract() {
        String max = "a".repeat(128);
        assertEquals(max, AgentRequestValidator.sessionId(max));
        assertEquals(max, AgentRequestValidator.turnId(max));
        assertEquals(max, new ChatCommand(max, "hello", max, List.of()).getSessionId());
        for (int length : new int[]{129, 191, 256}) {
            String tooLong = "a".repeat(length);
            assertThrows(ApiException.class, () -> AgentRequestValidator.sessionId(tooLong));
            assertThrows(ApiException.class, () -> AgentRequestValidator.turnId(tooLong));
            assertThrows(
                    ApplicationError.class,
                    () -> new ChatCommand(tooLong, "hello", "request", List.of()));
            assertThrows(
                    ApplicationError.class,
                    () -> new ChatCommand("session", "hello", tooLong, List.of()));
        }
    }
}
