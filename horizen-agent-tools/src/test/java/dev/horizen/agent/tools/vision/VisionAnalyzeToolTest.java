package dev.horizen.agent.tools.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

class VisionAnalyzeToolTest {
    @Test
    void sendsOwnedImageArtifactAsAnImageBlockToTheModel() {
        Artifact image =
                new Artifact(
                        "image-1",
                        "owner",
                        ArtifactKind.FILE,
                        ArtifactState.READY,
                        "image.png",
                        "image/png",
                        "memory:image",
                        8L,
                        null,
                        null,
                        ArtifactSource.USER,
                        null,
                        null,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        null,
                        0);
        AtomicBoolean sawImage = new AtomicBoolean();
        Model model =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        List<ImageBlock> images =
                                messages.get(0).getContentBlocks(ImageBlock.class);
                        sawImage.set(
                                images.size() == 1
                                        && images.get(0).getSource() instanceof URLSource source
                                        && source.getUrl().startsWith("https://bos.example.test/"));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("a blue chart")
                                                                .build()))
                                        .build());
                    }

                    @Override
                    public String getModelName() {
                        return "vision-test";
                    }
                };
        VisionAnalyzeTool tool =
                new VisionAnalyzeTool(model, new SingleArtifactStore(image), new BytesStore());
        ToolResultBlock result =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(
                                                RuntimeContext.builder()
                                                        .userId("owner")
                                                        .sessionId("session")
                                                        .build())
                                        .input(
                                                Map.of(
                                                        "artifact_id",
                                                        "image-1",
                                                        "question",
                                                        "what is shown?"))
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("call")
                                                        .name("vision_analyze")
                                                        .input(Map.of())
                                                        .content("{}")
                                                        .build())
                                        .build())
                        .block();
        assertTrue(sawImage.get());
        assertEquals("a blue chart", ((TextBlock) result.getOutput().get(0)).getText());
    }

    private static final class BytesStore implements ArtifactContentStore {
        @Override
        public ArtifactContent put(ArtifactContentWrite request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] get(String ref) {
            throw new AssertionError("URL transport must not download bytes");
        }

        @Override
        public URI createDownloadUrl(String ref, int expires) {
            return URI.create("https://bos.example.test/image?expires=" + expires);
        }

        @Override
        public void delete(String ref) {
        }
    }

    private static final class SingleArtifactStore implements ArtifactStore {
        private final Artifact image;

        SingleArtifactStore(Artifact image) {
            this.image = image;
        }

        @Override
        public Artifact create(Artifact a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Artifact> find(String owner, String id) {
            return owner.equals("owner") && id.equals("image-1")
                    ? Optional.of(image)
                    : Optional.empty();
        }

        @Override
        public Artifact update(Artifact a, long version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addReference(ArtifactReference r) {
        }

        @Override
        public List<ArtifactReference> listReferences(String owner, String id) {
            return List.of();
        }
    }
}
