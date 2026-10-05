package dev.horizen.agent.storage.bos;

import static org.junit.jupiter.api.Assertions.*;

import com.baidubce.BceServiceException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

class BceBosSnapshotObjectClientTest {
    @Test
    void emptyHead404MeansMissingObjectOnlyWhenBucketExists() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger bucketStatus = new AtomicInteger(200);
        AtomicInteger objectStatus = new AtomicInteger(404);
        server.createContext(
                "/",
                exchange -> {
                    if (exchange.getRequestMethod().equals("GET") && objectStatus.get() == 200) {
                        byte[] content = new byte[256 * 1024];
                        exchange.sendResponseHeaders(200, content.length);
                        exchange.getResponseBody().write(content);
                    } else {
                        exchange.sendResponseHeaders(
                                exchange.getRequestURI().getPath().endsWith("/fixture.tar")
                                        ? objectStatus.get()
                                        : bucketStatus.get(),
                                -1);
                    }
                    exchange.close();
                });
        server.start();
        var config =
                new BosArtifactContentStoreConfig(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-bucket",
                        "fixture-ak",
                        "fixture-sk",
                        "snapshot-fixtures",
                        1024);
        try (var client = new BceBosSnapshotObjectClient(config, 1, 5)) {
            assertFalse(client.exists("test-bucket", "fixture.tar"));
            bucketStatus.set(404);
            assertThrows(
                    BceServiceException.class, () -> client.exists("test-bucket", "fixture.tar"));
            bucketStatus.set(200);
            objectStatus.set(403);
            assertThrows(
                    BceServiceException.class, () -> client.exists("test-bucket", "fixture.tar"));
            objectStatus.set(200);
            var partial = client.get("test-bucket", "fixture.tar");
            assertEquals(0, partial.read());
            assertDoesNotThrow(partial::close);
        } finally {
            server.stop(0);
        }
    }
}
