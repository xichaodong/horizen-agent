package dev.horizen.agent.storage.bos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizen.agent.domain.artifact.ArtifactContentWrite;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

class BosArtifactContentStoreTest {
    @Test
    void keepsOwnerOutOfObjectKeyAndRejectsForeignContentRefs() {
        FakeBosClient client = new FakeBosClient();
        BosArtifactContentStore store = new BosArtifactContentStore(config(), client);

        var stored =
                store.put(
                        new ArtifactContentWrite(
                                "merchant:secret-owner",
                                "art-report-1",
                                bytes("report"),
                                "text/markdown"));

        assertTrue(stored.getContentRef().startsWith("horizen-artifacts/"));
        assertTrue(!stored.getContentRef().contains("secret-owner"));
        assertEquals(6, stored.getSizeBytes());
        assertArrayEquals(bytes("report"), store.get(stored.getContentRef()));
        assertEquals(
                "https://download.example/" + stored.getContentRef(),
                store.createDownloadUrl(stored.getContentRef(), 300).toString());
        assertThrows(IllegalArgumentException.class, () -> store.get("another-prefix/object"));

        store.delete(stored.getContentRef());
        assertTrue(client.objects.isEmpty());
    }

    @Test
    void appliesConfiguredMaximumBeforeCallingBos() {
        FakeBosClient client = new FakeBosClient();
        BosArtifactContentStore store =
                new BosArtifactContentStore(
                        new BosArtifactContentStoreConfig(
                                "bos.example",
                                "bucket",
                                "access",
                                "secret",
                                "horizen-artifacts",
                                3),
                        client);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.put(
                                new ArtifactContentWrite(
                                        "owner", "art-1", bytes("four"), "text/plain")));
        assertTrue(client.objects.isEmpty());
    }

    private static BosArtifactContentStoreConfig config() {
        return new BosArtifactContentStoreConfig(
                "bos.example", "bucket", "access", "secret", "horizen-artifacts", 1024);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeBosClient implements BosObjectClient {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(
                String bucket,
                String key,
                byte[] content,
                String mediaType,
                String checksumSha256) {
            objects.put(key, content.clone());
        }

        @Override
        public byte[] get(String bucket, String key) {
            byte[] value = objects.get(key);
            if (value == null) {
                throw new IllegalArgumentException("object not found");
            }
            return value.clone();
        }

        @Override
        public URI createDownloadUrl(String bucket, String key, int expiresInSeconds) {
            return URI.create("https://download.example/" + key);
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(key);
        }
    }
}
