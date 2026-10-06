package dev.horizen.agent.adapter.agentscope.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.presentation.PresentationBlock;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class PresentationToolResultMapperTest {
    @Test
    void extractsOnlyExplicitPresentationEnvelope() {
        ToolResultBlock result =
                ToolResultBlock.text(
                                """
                                        {"safeResult":{"answer":"ok","presentation":{"schemaVersion":1,"blocks":[
                                          {"type":"conclusion","severity":"P1","title":"流量下降"},
                                          {"type":"action","data":{"title":"回滚主图","actionLabel":"去修改"}}
                                        ]}}}
                                        """)
                        .withState(ToolResultState.SUCCESS);

        List<PresentationBlock> blocks = PresentationToolResultMapper.extract(result, "call-1");

        assertEquals(2, blocks.size());
        assertEquals("conclusion", blocks.get(0).getType());
        assertEquals("流量下降", blocks.get(0).getData().get("title"));
        assertEquals("回滚主图", blocks.get(1).getData().get("title"));
        assertTrue(blocks.get(0).getBlockId().startsWith("ui_"));
        assertEquals(
                blocks.get(0).getBlockId(),
                PresentationToolResultMapper.extract(result, "call-1").get(0).getBlockId());
    }

    @Test
    void doesNotGuessOrdinaryToolJsonAsCards() {
        ToolResultBlock result =
                ToolResultBlock.text(
                        """
                                {"title":"looks like a card","metrics":[{"value":42}]}
                                """);
        assertTrue(PresentationToolResultMapper.extract(result, "call-1").isEmpty());
    }

    @Test
    void malformedOrFailedPresentationIsNotPublished() {
        ToolResultBlock malformed =
                ToolResultBlock.text(
                        """
                                {"presentation":{"schemaVersion":1,"blocks":[{"title":"missing type"}]}}
                                """);
        ToolResultBlock failed = ToolResultBlock.error("failed");
        var originalState = malformed.getState();
        var notified = new AtomicBoolean();
        assertTrue(
                PresentationToolResultMapper.extract(malformed, "call-1", () -> notified.set(true))
                        .isEmpty());
        assertTrue(notified.get());
        assertEquals(originalState, malformed.getState());
        assertTrue(PresentationToolResultMapper.extract(failed, "call-2").isEmpty());
    }
}
