package dev.horizen.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class ConfiguredCompactionModelTest {
    @Test
    void appliesCompactionDefaultsAndPreservesExplicitOverrides() {
        AtomicReference<GenerateOptions> captured = new AtomicReference<>();
        Model delegate =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        captured.set(options);
                        return Flux.just(ChatResponse.builder().content(List.of()).build());
                    }

                    @Override
                    public String getModelName() {
                        return "compression-test";
                    }
                };
        ConfiguredCompactionModel model =
                new ConfiguredCompactionModel(delegate, 512, 0, Duration.ofSeconds(30), 2);

        model.stream(List.of(), List.of(), GenerateOptions.builder().maxTokens(128).build())
                .collectList()
                .block(Duration.ofSeconds(1));

        GenerateOptions options = captured.get();
        assertEquals(128, options.getMaxTokens());
        assertEquals(0.0, options.getTemperature());
        assertEquals(Duration.ofSeconds(30), options.getExecutionConfig().getTimeout());
        assertEquals(2, options.getExecutionConfig().getMaxAttempts());
    }
}
