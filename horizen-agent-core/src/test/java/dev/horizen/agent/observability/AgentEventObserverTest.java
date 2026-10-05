package dev.horizen.agent.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.agentscope.core.event.TextBlockDeltaEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

class AgentEventObserverTest {
    @Test
    void textCaptureIsOptInAndEventsRemainCorrelated() {
        var events = new ArrayList<TraceEvent>();
        var observer = new AgentEventObserver("turn-1", "session-1", events::add);
        observer.accept(new TextBlockDeltaEvent("reply-1", "block-1", "confidential input"));
        observer.onError(new IllegalStateException("sensitive exception detail"));
        observer.onCancel();
        assertEquals(3, events.size());
        assertEquals(1, events.stream().map(TraceEvent::getTraceId).distinct().count());
        assertTrue(events.stream().allMatch(event -> event.getTurnId().equals("turn-1")));
        assertFalse(events.get(0).getAttributes().containsKey("text"));
        assertFalse(
                events.get(1).getAttributes().toString().contains("sensitive exception detail"));
        assertEquals("EXECUTION_CANCELLED", events.get(2).getType());
    }
}
