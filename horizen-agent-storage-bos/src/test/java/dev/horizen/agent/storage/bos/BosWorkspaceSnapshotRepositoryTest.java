package dev.horizen.agent.storage.bos;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class BosWorkspaceSnapshotRepositoryTest {
    @Test
    void anotherInstanceDownloadsTheSameSlotAndBadUploadPreservesPreviousObject() throws Exception {
        FakeClient objects = new FakeClient();
        try (var a = repository(objects);
             var b = repository(objects)) {
            assertFalse(a.exists("slot-a"));
            a.upload("slot-a", new ByteArrayInputStream(new byte[]{1, 2, 3}));
            assertTrue(b.exists("slot-a"));
            try (var data = b.download("slot-a")) {
                assertArrayEquals(new byte[]{1, 2, 3}, data.readAllBytes());
            }
            assertThrows(
                    IOException.class,
                    () -> a.upload("slot-a", new ByteArrayInputStream(new byte[1025])));
            objects.failUpload = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> a.upload("slot-a", new ByteArrayInputStream(new byte[]{4})));
            assertArrayEquals(new byte[]{1, 2, 3}, objects.values.get("snapshots/slot-a.tar"));
            assertFalse(a.exists("slot-b"));
        }
    }

    @Test
    void downloadHoldsOneCapacitySlotUntilClosedAndOversizeFails() throws Exception {
        FakeClient client = new FakeClient();
        var repository = repository(client);
        client.values.put("snapshots/one.tar", new byte[]{1});
        try (InputStream stream = repository.download("one")) {
            assertThrows(IOException.class, () -> repository.download("one"));
        }
        assertTrue(repository.exists("one"));
        client.values.put("snapshots/one.tar", new byte[1025]);
        try (InputStream stream = repository.download("one")) {
            assertThrows(IOException.class, stream::readAllBytes);
        }
        assertTrue(repository.exists("one"));
        assertThrows(IllegalArgumentException.class, () -> repository.exists("../foreign"));
    }

    @Test
    void existsPropagatesOutages() {
        FakeClient client = new FakeClient();
        client.failExists = true;
        assertThrows(IllegalStateException.class, () -> repository(client).exists("slot"));
    }

    private static BosWorkspaceSnapshotRepository repository(FakeClient client) {
        return new BosWorkspaceSnapshotRepository(
                new BosArtifactContentStoreConfig(
                        "https://bos.example.test",
                        "test",
                        "test-ak",
                        "test-sk",
                        "snapshots",
                        1024),
                client,
                1);
    }

    private static final class FakeClient implements BosSnapshotObjectClient {
        final Map<String, byte[]> values = new HashMap<>();
        boolean failUpload;
        boolean failExists;

        @Override
        public void put(String bucket, String key, Path path, String digest) {
            if (failUpload) throw new IllegalStateException("upload unavailable");
            try {
                values.put(key, Files.readAllBytes(path));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override
        public InputStream get(String bucket, String key) {
            return new ByteArrayInputStream(values.get(key));
        }

        @Override
        public boolean exists(String bucket, String key) {
            if (failExists) throw new IllegalStateException("storage unavailable");
            return values.containsKey(key);
        }
    }
}
