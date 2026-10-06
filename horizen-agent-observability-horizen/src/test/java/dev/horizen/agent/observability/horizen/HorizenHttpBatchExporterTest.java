package dev.horizen.agent.observability.horizen;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class HorizenHttpBatchExporterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void postsCompatibleBatchToBasePathAndPreservesBaseQuery() throws Exception {
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        var paths = new CopyOnWriteArrayList<String>();
        var auth = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/proxy/api/v1/sdk/traces/batch",
                exchange -> {
                    paths.add(exchange.getRequestURI().toString());
                    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    bodies.add(mapper.readTree(exchange.getRequestBody()));
                    byte[] response =
                            "{\"code\":200,\"data\":{},\"errors\":[]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI base =
                    URI.create(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/proxy?env=offline");
            var config = config(base, "test-token");
            try (var exporter = new HorizenHttpBatchExporter(config)) {
                assertTrue(exporter.submit(batch()));
                assertTrue(exporter.flush(Duration.ofSeconds(3)));
                assertEquals(1, exporter.uploadedCount());
                assertEquals(0, exporter.failedCount());
            }
            assertEquals(List.of("/proxy/api/v1/sdk/traces/batch?env=offline"), paths);
            assertEquals(List.of("Bearer test-token"), auth);
            JsonNode body = bodies.get(0);
            assertEquals(1, body.path("contractVersion").asInt());
            assertEquals(7, body.path("projectId").asLong());
            assertEquals("horizen-agent", body.path("source").asText());
            assertEquals("trace-1", body.at("/trace/traceId").asText());
            assertEquals("LLM", body.at("/spans/0/spanType").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpFailureIsCountedWithoutThrowingToSubmitter() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/sdk/traces/batch",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (var exporter = new HorizenHttpBatchExporter(config(base, null))) {
                assertTrue(exporter.submit(batch()));
                assertTrue(exporter.flush(Duration.ofSeconds(3)));
                assertEquals(0, exporter.uploadedCount());
                assertEquals(1, exporter.failedCount());
                assertEquals("HTTP 503", exporter.lastFailure());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void businessFailureInsideHttp200IsCountedAsFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/sdk/traces/batch",
                exchange -> {
                    byte[] response =
                            "{\"code\":500,\"data\":null,\"errors\":[]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (var exporter = new HorizenHttpBatchExporter(config(base, null))) {
                assertTrue(exporter.submit(batch()));
                assertTrue(exporter.flush(Duration.ofSeconds(3)));
                assertEquals(0, exporter.uploadedCount());
                assertEquals(1, exporter.failedCount());
                assertEquals("BUSINESS 500", exporter.lastFailure());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientFailuresWithIdenticalEnvelopeAndStableIds() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/sdk/traces/batch",
                exchange -> {
                    requests.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    if (requests.size() < 3) {
                        exchange.getResponseHeaders().add("Retry-After", "0");
                        exchange.sendResponseHeaders(requests.size() == 1 ? 503 : 429, -1);
                    } else {
                        byte[] response = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                    exchange.close();
                });
        server.start();
        try (var exporter =
                     new HorizenHttpBatchExporter(
                             config(
                                     URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                     null))) {
            exporter.submit(batch());
            assertTrue(exporter.flush(Duration.ofSeconds(3)));
            assertEquals(3, requests.size());
            assertEquals(1, requests.stream().distinct().count());
            assertEquals(2, exporter.retriedCount());
            assertEquals(1, exporter.uploadedCount());
            assertEquals(0, exporter.failedCount());
            assertNull(exporter.lastFailure());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permanentErrorsAreNotRetried() throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/sdk/traces/batch",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                });
        server.start();
        try (var exporter =
                     new HorizenHttpBatchExporter(
                             config(
                                     URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                     null))) {
            exporter.submit(batch());
            assertTrue(exporter.flush(Duration.ofSeconds(3)));
            assertEquals(1, calls.get());
            assertEquals(0, exporter.retriedCount());
            assertEquals(1, exporter.failedCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void zeroShutdownTimeoutDoesNotJoinForeverAndRejectsNewSnapshots() {
        var config = config(URI.create("http://127.0.0.1:1"), null);
        config.setShutdownTimeout(Duration.ZERO);
        config.setFlushInterval(Duration.ofSeconds(30));
        var exporter = new HorizenHttpBatchExporter(config);
        exporter.submit(batch());
        assertTimeoutPreemptively(Duration.ofSeconds(1), exporter::close);
        assertFalse(exporter.submit(batch()));
    }

    @Test
    void endpointPreservesEncodedBaseQuery() {
        URI endpoint =
                HorizenHttpBatchExporter.endpoint(
                        URI.create("https://example.test/gateway?env=off%20line&route=a%2Fb"),
                        "/api/v1/sdk/traces/batch");
        assertEquals(
                "https://example.test/gateway/api/v1/sdk/traces/batch?env=off%20line&route=a%2Fb",
                endpoint.toString());
    }

    private static HorizenTraceConfig config(URI base, String token) {
        return new HorizenTraceConfig(
                base,
                7,
                token,
                "horizen-agent",
                "horizen-agent-java",
                "test",
                "test-agent",
                "test-executor",
                "test",
                null,
                true,
                Duration.ofSeconds(2),
                Duration.ZERO,
                8,
                Duration.ofSeconds(2));
    }

    private static HorizenTraceBatch batch() {
        var trace =
                new HorizenTraceBatch.Trace(
                        "trace-1",
                        null,
                        null,
                        "session-1",
                        "user-1",
                        "demo",
                        "COMPLETED",
                        1L,
                        2L,
                        Map.of("text", "in"),
                        Map.of("text", "out"),
                        Map.of("runId", "run-1"),
                        null,
                        null);
        var span =
                new HorizenTraceBatch.Span(
                        "span-1",
                        null,
                        "LLM",
                        "llm.call.1",
                        "OK",
                        1L,
                        2L,
                        1L,
                        "scripted",
                        "test",
                        Map.of(),
                        Map.of(),
                        Map.of("inputTokens", 1, "outputTokens", 1, "totalTokens", 2),
                        null,
                        null,
                        null,
                        null,
                        Map.of());
        return new HorizenTraceBatch(
                1,
                7,
                "horizen-agent",
                "horizen-agent-java",
                "test",
                trace,
                List.of(span),
                List.of());
    }
}
