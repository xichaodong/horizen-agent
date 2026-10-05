package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.horizen.agent.web.bootstrap.runtime.ContextRuntimeConfigurer;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.util.List;

class ContextPropertiesTest {
    @Test
    void defaultsPreserveCurrentBatchCompactionAndAvoidEphemeralOffload() {
        ContextProperties properties = new ContextProperties();

        CompactionConfig config = new ContextRuntimeConfigurer(properties).toCompactionConfig();

        assertEquals(30, config.getTriggerMessages());
        assertEquals(0, config.getTriggerTokens());
        assertEquals(10, config.getKeepMessages());
        assertEquals(-1, config.getKeepTokens());
        assertFalse(config.isFlushBeforeCompact());
        assertFalse(config.isOffloadBeforeCompact());
        assertFalse(properties.isToolResultEvictionEnabled());
    }

    @Test
    void mapsToolPruningAndArgumentTruncationToAgentScope() {
        ContextProperties properties = new ContextProperties();
        properties.setTruncateArgumentsEnabled(true);
        properties.setPruneProtectTokens(12_000);
        properties.setPruneMinimumTokens(4_000);

        CompactionConfig config = new ContextRuntimeConfigurer(properties).toCompactionConfig();

        assertEquals(12_000, config.getPruneConfig().getProtectTokens());
        assertEquals(4_000, config.getPruneConfig().getMinimumTokens());
        assertEquals(2_000, config.getTruncateArgsConfig().getMaxArgLength());
    }

    @Test
    void assignsDedicatedCompressionModelToNormalAndEmergencyCompaction() {
        ContextProperties properties = new ContextProperties();
        WindowModel model = new WindowModel("compression", 100_000);

        assertSame(
                model,
                new ContextRuntimeConfigurer(properties).toCompactionConfig(model).getModel());
        assertSame(
                model,
                new ContextRuntimeConfigurer(properties)
                        .toEmergencyCompactionConfig(model)
                        .getModel());
    }

    @Test
    void dynamicThresholdUsesPrimaryWindowInsteadOfCompressionModelWindow() {
        ContextProperties properties = new ContextProperties();
        WindowModel primary = new WindowModel("primary", 100_000);
        WindowModel compression = new WindowModel("compression", 10_000);

        CompactionConfig config =
                new ContextRuntimeConfigurer(properties).toCompactionConfig(primary, compression);

        assertEquals(80_000, config.getTriggerTokens());
        assertEquals(8_000, config.getKeepTokens());
        assertSame(compression, config.getModel());
    }

    @Test
    void rejectsAnInvalidTailBudget() {
        ContextProperties properties = new ContextProperties();
        properties.setKeepTokensMin(9_000);
        properties.setKeepTokensMax(8_000);

        assertThrows(
                IllegalArgumentException.class,
                new ContextRuntimeConfigurer(properties)::toCompactionConfig);
    }

    private static final class WindowModel extends ChatModelBase {
        private final String name;

        private WindowModel(String name, int contextWindow) {
            this.name = name;
            setContextWindowSize(contextWindow);
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }
}
