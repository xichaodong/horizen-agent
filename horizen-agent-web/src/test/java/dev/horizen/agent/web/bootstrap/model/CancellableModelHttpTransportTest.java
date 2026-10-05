package dev.horizen.agent.web.bootstrap.model;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpTransportConfig;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

class CancellableModelHttpTransportTest {
    @Test
    void cancellingAStalledStreamReturnsPromptlyAndLeavesAnotherStreamRunning() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newFixedThreadPool(2);
        var release = new CountDownLatch(1);
        var received = new CountDownLatch(2);
        var cancelWorker = Executors.newSingleThreadExecutor();
        server.setExecutor(workers);
        server.createContext(
                "/stream",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try (var output = exchange.getResponseBody()) {
                        output.write("data: ready\n\n".getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        release.await(10, TimeUnit.SECONDS);
                        output.write(
                                "data: finished\n\ndata: [DONE]\n\n"
                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } catch (IOException disconnected) {
                        // 取消流后，其 Socket 连接应被关闭。
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var config = HttpTransportConfig.defaults();
        try (var transport =
                new CancellableModelHttpTransport(
                        new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(20)).build(),
                        config)) {
            var request =
                    HttpRequest.builder()
                            .url("http://127.0.0.1:" + server.getAddress().getPort() + "/stream")
                            .method("GET")
                            .build();
            var first = transport.stream(request).subscribe(value -> received.countDown());
            var remaining =
                    transport.stream(request)
                            .doOnNext(
                                    value -> {
                                        if ("ready".equals(value)) received.countDown();
                                    })
                            .collectList()
                            .toFuture();
            assertTrue(received.await(5, TimeUnit.SECONDS));
            cancelWorker.submit(first::dispose).get(2, TimeUnit.SECONDS);
            assertFalse(remaining.isDone(), "Cancelling one request must not cancel the other");
            release.countDown();
            assertEquals(List.of("ready", "finished"), remaining.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
            cancelWorker.shutdownNow();
        }
    }
}
