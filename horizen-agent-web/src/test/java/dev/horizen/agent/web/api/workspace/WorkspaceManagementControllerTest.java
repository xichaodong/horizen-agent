package dev.horizen.agent.web.api.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.adapter.agentscope.workspace.release.AgentScopePublicationValidator;
import dev.horizen.agent.adapter.skill.horizen.HorizenSkillReleaseClient;
import dev.horizen.agent.adapter.workspace.horizen.HorizenAgentReleaseRepository;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.application.workspace.WorkspaceArchiveService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.application.workspace.WorkspacePublicationService;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;
import dev.horizen.agent.domain.workspace.release.WorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceCatalogRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.api.ApiExceptionHandler;
import dev.horizen.agent.web.config.WorkspaceManagementProperties;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

class WorkspaceManagementControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private WorkspaceManagementService service;
    private JdbcWorkspaceCatalogRepository catalog;
    private JdbcDataSource source;
    private InMemoryWorkspaceContentRepository objects;

    @BeforeEach
    void setup() {
        source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:workspace-admin-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        objects = new InMemoryWorkspaceContentRepository();
        catalog = new JdbcWorkspaceCatalogRepository(source);
        service = new WorkspaceManagementService(catalog, objects);
        var config = new WorkspaceManagementProperties();
        config.setToken("synthetic-management-token");
        config.setProjectIds(Set.of(7L));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new WorkspaceManagementController(
                                        service,
                                        new WorkspacePublicationService(
                                                service,
                                                catalog,
                                                objects,
                                                new AgentScopePublicationValidator(),
                                                new WorkspaceArchiveService(service)),
                                        config,
                                        json))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    private JsonNode call(String operation, Map<String, Object> body, int code) throws Exception {
        String response =
                mvc.perform(
                                post("/api/internal/workspaces/" + operation)
                                        .header(
                                                "Authorization",
                                                "Bearer synthetic-management-token")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().is(code))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return json.readTree(response);
    }

    private Map<String, Object> body() {
        return new HashMap<>(
                Map.of("projectId", 7, "agentKey", "test-agent", "operator", "tester"));
    }

    private JsonNode save(long version, String text, int status) throws Exception {
        var body = body();
        body.put(
                "draft",
                Map.of(
                        "version",
                        version,
                        "skills",
                        List.of(),
                        "assets",
                        List.of(Map.of("path", "AGENTS.md", "content", text))));
        return call("save", body, status);
    }

    @Test
    void requiresServiceCredentialAndProjectAllowlist() throws Exception {
        mvc.perform(
                        post("/api/internal/workspaces/draft")
                                .contentType("application/json")
                                .content(json.writeValueAsBytes(body())))
                .andExpect(status().isUnauthorized());
        var other = body();
        other.put("projectId", 8);
        call("draft", other, 403);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace", Integer.class));
    }

    @Test
    void commitsMetadataAndAuditRejectsStaleSaveAndKeepsMemoryInsideAgent() throws Exception {
        assertEquals(1, save(0, "policy-v1", 200).path("version").asInt());
        save(0, "stale", 409);
        assertEquals(
                "policy-v1",
                call("draft", body(), 200).path("assets").get(0).path("content").asText());
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_operation", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_name='ha_workspace' AND"
                                + " column_name='skill_selection_json'",
                        Integer.class));
        new CloudMemoryService(new JdbcWorkspaceDocumentRepository(source, objects))
                .save("owner-a", "test-agent", "call-a", "remembered fact");
        assertEquals(
                2, jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_file", Integer.class));
        assertEquals(1, service.draft(7, "test-agent").getFiles().size());
    }

    @Test
    void immutableReleaseSurvivesDraftEditAndRuntimeReadsWithoutCallingServer() throws Exception {
        JsonNode first = save(0, "policy-v1", 200);
        var asset = first.path("assets").get(0).deepCopy();
        ((ObjectNode) asset).remove("content");
        String skillHash = WorkspaceManagementService.hash(new byte[0]);
        String releaseHash =
                WorkspaceManagementService.hash(
                        ("7\ntest-agent\nAGENTS.md\0"
                                + asset.path("sha256").asText()
                                + "\0"
                                + asset.path("size").asLong()
                                + "\n"
                                + skillHash)
                                .getBytes(StandardCharsets.UTF_8));
        var manifest =
                Map.of(
                        "projectId",
                        7,
                        "agentKey",
                        "test-agent",
                        "releaseNo",
                        1,
                        "releaseHash",
                        releaseHash,
                        "assets",
                        List.of(asset),
                        "skillRelease",
                        Map.of(
                                "projectId",
                                7,
                                "releaseId",
                                1,
                                "releaseNo",
                                1,
                                "releaseHash",
                                skillHash,
                                "skillCount",
                                0,
                                "items",
                                List.of()));
        var publish = body();
        publish.put("expectedVersion", 1);
        publish.put("manifest", manifest);
        publish.put("notes", "test");
        JsonNode release = call("publish", publish, 200);
        save(1, "policy-v2", 200);
        assertEquals(
                release.path("releaseHash").asText(),
                call("current", body(), 200).path("releaseHash").asText());
        assertNotNull(
                jdbc.queryForObject("SELECT current_release_id FROM ha_workspace", Long.class));
        var cache = Files.createTempDirectory("workspace-release-test-");
        var client =
                new HorizenSkillReleaseClient(
                        URI.create("http://127.0.0.1:1/unreachable"),
                        "",
                        Set.of("127.0.0.1"),
                        true,
                        Duration.ofSeconds(1),
                        20L * 1024 * 1024);
        var repository =
                new HorizenAgentReleaseRepository(
                        null, cache, 100L * 1024 * 1024, catalog, objects);
        try (var snapshot =
                     repository.acquire(
                             repository
                                     .findCurrent(new AgentCatalogKey(7, "test-agent"))
                                     .orElseThrow());
             var input = snapshot.open("AGENTS.md")) {
            assertEquals("policy-v1", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            HorizenAgentReleaseRepository.delete(cache);
        }
    }

    @Test
    void completeSkillDirectoryUsesSameFilesSnapshotAndAuditIncludingBinaryResources()
            throws Exception {
        byte[] binary = new byte[]{0, 1, 2, (byte) 255};
        String markdown =
                "---\n"
                        + "name: example\n"
                        + "description: Synthetic file analysis\n"
                        + "---\n"
                        + "Read references/rules.md and use scripts/run.py when needed.\n";
        var save = body();
        save.put(
                "draft",
                Map.of(
                        "version",
                        0,
                        "assets",
                        List.of(
                                Map.of("path", "AGENTS.md", "content", "Synthetic policy"),
                                Map.of("path", "skills/example/SKILL.md", "content", markdown),
                                Map.of(
                                        "path",
                                        "skills/example/scripts/run.py",
                                        "content",
                                        "print('synthetic')\n"),
                                Map.of(
                                        "path",
                                        "skills/example/references/rules.md",
                                        "content",
                                        "Original rule"),
                                Map.of(
                                        "path",
                                        "skills/example/assets/template.xlsx",
                                        "base64",
                                        Base64.getEncoder().encodeToString(binary),
                                        "mediaType",
                                        "application/octet-stream"))));
        assertEquals(1, call("save", save, 200).path("version").asInt());
        var publish = body();
        publish.put("expectedVersion", 1);
        JsonNode first = call("publish", publish, 200);
        assertEquals("workspace-files-v1", first.path("manifest").path("format").asText());
        assertEquals(5, first.path("manifest").path("assets").size());
        assertEquals(1, first.path("manifest").path("archives").size());
        assertEquals(
                5, jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_file", Integer.class));
        assertEquals(
                10,
                jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_operation", Integer.class));
        var cache = Files.createTempDirectory("unified-workspace-skill-");
        var client =
                new HorizenSkillReleaseClient(
                        URI.create("http://127.0.0.1:1/unused"),
                        "",
                        Set.of("127.0.0.1"),
                        true,
                        Duration.ofSeconds(1),
                        20L * 1024 * 1024);
        var repository =
                new HorizenAgentReleaseRepository(
                        client, cache, 100L * 1024 * 1024, catalog, objects);
        try (var snapshot =
                     repository.acquire(
                             repository
                                     .findCurrent(new AgentCatalogKey(7, "test-agent"))
                                     .orElseThrow())) {
            assertEquals(List.of("example"), snapshot.getSkills().skillNames());
            assertEquals(markdown, snapshot.getSkills().markdown("example").orElseThrow());
            assertEquals(
                    "Original rule",
                    snapshot.getSkills().read("example", "references/rules.md").orElseThrow());
            assertArrayEquals(
                    binary,
                    snapshot.getSkills()
                            .readBinary("example", "assets/template.xlsx")
                            .orElseThrow());
            assertTrue(
                    snapshot.getSkills()
                            .read("example", "scripts/run.py")
                            .orElseThrow()
                            .contains("synthetic"));
            var audit =
                    new JdbcWorkspaceAuditRepository(source)
                            .list(
                                    new WorkspaceDocumentKey(
                                            "project:7",
                                            "test-agent",
                                            "draft",
                                            "skills/example/SKILL.md"),
                                    false,
                                    0,
                                    25);
            assertEquals(
                    List.of("PUBLISH", "CREATE"),
                    audit.stream()
                            .map(WorkspaceAuditRepository.Operation::getOperationType)
                            .toList());
        } finally {
            HorizenAgentReleaseRepository.delete(cache);
        }
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'ha_skill%'",
                        Integer.class));
    }

    @Test
    void rejectsIndependentSkillSelectionsAndIncompleteDirectories() throws Exception {
        var old = body();
        old.put(
                "draft",
                Map.of(
                        "version",
                        0,
                        "assets",
                        List.of(),
                        "skills",
                        List.of(Map.of("skillId", 1, "versionId", 1))));
        call("save", old, 400);
        var missing = body();
        missing.put(
                "draft",
                Map.of(
                        "version",
                        0,
                        "assets",
                        List.of(
                                Map.of("path", "AGENTS.md", "content", "Synthetic policy"),
                                Map.of(
                                        "path",
                                        "skills/example/scripts/run.py",
                                        "content",
                                        "print('test')"))));
        call("save", missing, 200);
        var publish = body();
        publish.put("expectedVersion", 1);
        call("publish", publish, 400);
        assertEquals(
                0, jdbc.queryForObject("SELECT COUNT(*) FROM ha_workspace_release", Integer.class));
    }
}
