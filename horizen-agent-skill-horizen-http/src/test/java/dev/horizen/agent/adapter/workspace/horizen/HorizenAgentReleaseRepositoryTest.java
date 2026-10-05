package dev.horizen.agent.adapter.workspace.horizen;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import dev.horizen.agent.adapter.skill.horizen.AgentScopeSkillRepositoryAdapter;
import dev.horizen.agent.adapter.skill.horizen.HorizenSkillReleaseClient;
import dev.horizen.agent.application.workspace.AgentReleaseService;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.AgentReleaseManifest;
import dev.horizen.agent.domain.workspace.release.AgentReleaseSnapshot;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceRelease;
import dev.horizen.agent.domain.workspace.release.SessionWorkspaceReleaseRepository;
import dev.horizen.agent.runtime.skill.SkillReleaseContext;

import io.agentscope.core.agent.RuntimeContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

class HorizenAgentReleaseRepositoryTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pinsMultipleSkillsPerSessionAndReloadsTheOldPublicationAfterCacheClear() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, byte[]> objects = new HashMap<>();
        AtomicReference<Map<String, Object>> published =
                new AtomicReference<>(manifest(base, objects, 1));
        AtomicInteger status = new AtomicInteger(200);
        Map<Long, Map<String, Object>> history = new HashMap<>();
        history.put(1L, published.get());
        server.createContext(
                "/current",
                e -> {
                    assertEquals(
                            "Bearer synthetic-test-token",
                            e.getRequestHeaders().getFirst("Authorization"));
                    byte[] bytes =
                            JSON.writeValueAsBytes(
                                    Map.of("code", status.get(), "data", published.get()));
                    e.sendResponseHeaders(status.get(), bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/by-id",
                e -> {
                    assertEquals(
                            "Bearer synthetic-test-token",
                            e.getRequestHeaders().getFirst("Authorization"));
                    long id = JSON.readTree(e.getRequestBody()).path("releaseId").asLong();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("code", status.get());
                    result.put("data", history.get(id));
                    byte[] bytes = JSON.writeValueAsBytes(result);
                    e.sendResponseHeaders(status.get(), bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.createContext(
                "/objects/",
                e -> {
                    byte[] bytes = objects.get(e.getRequestURI().getPath());
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var transport =
                    new HorizenSkillReleaseClient(
                            URI.create(base + "/current"),
                            "synthetic-test-token",
                            Set.of("127.0.0.1"),
                            true,
                            Duration.ofSeconds(3),
                            20L * 1024 * 1024);
            var repo = new HorizenAgentReleaseRepository(transport, root, 128L * 1024 * 1024);
            Map<String, SessionWorkspaceRelease> values = new ConcurrentHashMap<>();
            SessionWorkspaceReleaseRepository bindings =
                    new SessionWorkspaceReleaseRepository() {
                        public Optional<SessionWorkspaceRelease> find(
                                String owner, String session) {
                            return Optional.ofNullable(values.get(owner + "/" + session));
                        }

                        public SessionWorkspaceRelease bindIfAbsent(
                                String owner, String session, SessionWorkspaceRelease selected) {
                            return values.computeIfAbsent(owner + "/" + session, k -> selected);
                        }
                    };
            var service =
                    new AgentReleaseService(new AgentCatalogKey(7, "test-agent"), repo, bindings);
            try (var first = service.beginExecution("owner", "old-session", false)) {
                assertEquals("AGENTS R1", read(first, "AGENTS.md"));
                published.set(manifest(base, objects, 2));
                history.put(2L, published.get());
                try (var second = service.beginExecution("owner", "new-session", false)) {
                    assertEquals("AGENTS R1", read(first, "AGENTS.md"));
                    assertEquals("AGENTS R2", read(second, "AGENTS.md"));
                    assertTrue(first.getSkills().markdown("sample").orElseThrow().contains("R1"));
                    var adapter = new AgentScopeSkillRepositoryAdapter();
                    var context =
                            RuntimeContext.builder()
                                    .put(
                                            SkillReleaseContext.class,
                                            new SkillReleaseContext(first.getSkills()))
                                    .build();
                    assertEquals(2, adapter.getAllSkills(context).size());
                    assertEquals(
                            "sample R1",
                            adapter.resourcesFor("sample", context)
                                    .read("refs/guide.txt")
                                    .orElseThrow());
                    assertEquals(
                            "second R1",
                            adapter.resourcesFor("second", context)
                                    .read("refs/guide.txt")
                                    .orElseThrow());
                    assertTrue(adapter.getAllSkills().isEmpty());
                    assertTrue(second.getSkills().markdown("sample").orElseThrow().contains("R2"));
                    assertThrows(
                            FileNotFoundException.class, () -> second.open("knowledge/removed.md"));
                    assertEquals("old knowledge", read(first, "knowledge/removed.md"));
                }
            }
            HorizenAgentReleaseRepository.delete(root); // 模拟删除缓存后的刷新和重启。
            try (var latest =
                    new AgentReleaseService(
                                    new AgentCatalogKey(7, "test-agent"),
                                    new HorizenAgentReleaseRepository(
                                            transport, root, 128L * 1024 * 1024),
                                    bindings)
                            .beginExecution("owner", "old-session", true)) {
                assertEquals("AGENTS R1", read(latest, "AGENTS.md"));
                assertEquals("old knowledge", read(latest, "knowledge/removed.md"));
            }
            status.set(503);
            assertThrows(
                    IllegalStateException.class,
                    () -> service.beginExecution("owner", "old-session", true));
            status.set(403);
            assertThrows(
                    SecurityException.class,
                    () -> service.beginExecution("owner", "old-session", true));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesTamperedManifestAndTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AgentReleaseManifest.Asset(
                                "knowledge/../AGENTS.md",
                                "https://assets.example/a",
                                "a".repeat(64),
                                1,
                                "text/plain"));
        assertEquals(
                "MEMORY.md",
                new AgentReleaseManifest.Asset(
                                "MEMORY.md",
                                "https://assets.example/a",
                                "a".repeat(64),
                                1,
                                "text/plain")
                        .getPath());
    }

    private static String read(AgentReleaseSnapshot s, String path) throws Exception {
        try (var input = s.open(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> manifest(
            String base, Map<String, byte[]> objects, int version) throws Exception {
        List<Map<String, Object>> assets = new ArrayList<>();
        assets.add(asset(base, objects, "AGENTS.md", "AGENTS R" + version));
        if (version == 1) assets.add(asset(base, objects, "knowledge/removed.md", "old knowledge"));
        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder skillCanonical = new StringBuilder();
        int id = 0;
        for (String name : List.of("sample", "second")) {
            id++;
            byte[] zip;
            try (var buffer = new ByteArrayOutputStream();
                    var output = new ZipOutputStream(buffer)) {
                output.putNextEntry(new ZipEntry("SKILL.md"));
                output.write(
                        ("---\nname: "
                                        + name
                                        + "\ndescription: synthetic example\n---\nR"
                                        + version)
                                .getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.putNextEntry(new ZipEntry("refs/guide.txt"));
                output.write((name + " R" + version).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.finish();
                zip = buffer.toByteArray();
            }
            String zipHash = hash(zip);
            objects.put("/objects/" + zipHash, zip);
            items.add(
                    Map.of(
                            "skillId",
                            id,
                            "skillKey",
                            name,
                            "versionId",
                            version,
                            "version",
                            "1." + version,
                            "runtimeName",
                            name,
                            "packageUrl",
                            base + "/objects/" + zipHash,
                            "sha256",
                            zipHash,
                            "packageSize",
                            zip.length,
                            "minAgentVersion",
                            ""));
            skillCanonical
                    .append(id)
                    .append(':')
                    .append(version)
                    .append(':')
                    .append(name)
                    .append(':')
                    .append(zipHash)
                    .append(':')
                    .append(zip.length)
                    .append(":\n");
        }
        String skillHash = hash(skillCanonical.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> skillRelease =
                Map.of(
                        "projectId",
                        7,
                        "releaseId",
                        version,
                        "releaseNo",
                        version,
                        "releaseHash",
                        skillHash,
                        "skillCount",
                        2,
                        "items",
                        items);
        StringBuilder canonical = new StringBuilder("7\ntest-agent\n");
        assets.stream()
                .sorted(Comparator.comparing(a -> a.get("path").toString()))
                .forEach(
                        a ->
                                canonical
                                        .append(a.get("path"))
                                        .append('\0')
                                        .append(a.get("sha256"))
                                        .append('\0')
                                        .append(a.get("size"))
                                        .append('\n'));
        canonical.append(skillHash);
        return Map.of(
                "projectId",
                7,
                "agentKey",
                "test-agent",
                "releaseId",
                version,
                "releaseNo",
                version,
                "releaseHash",
                hash(canonical.toString().getBytes(StandardCharsets.UTF_8)),
                "assets",
                assets,
                "skillRelease",
                skillRelease);
    }

    private static Map<String, Object> asset(
            String base, Map<String, byte[]> objects, String path, String content)
            throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = hash(bytes);
        objects.put("/objects/" + hash, bytes);
        return Map.of(
                "path",
                path,
                "url",
                base + "/objects/" + hash,
                "sha256",
                hash,
                "size",
                bytes.length,
                "mediaType",
                "text/plain");
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
