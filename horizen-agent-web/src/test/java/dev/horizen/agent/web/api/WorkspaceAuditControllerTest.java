package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.*;

import dev.horizen.agent.adapter.agentscope.workspace.release.AgentScopePublicationValidator;
import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.application.workspace.WorkspaceArchiveService;
import dev.horizen.agent.application.workspace.WorkspaceAuditService;
import dev.horizen.agent.application.workspace.WorkspaceManagementService;
import dev.horizen.agent.application.workspace.WorkspacePublicationService;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceCatalogRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.api.workspace.WorkspaceManagementController;
import dev.horizen.agent.web.config.WorkspaceManagementProperties;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.*;

class WorkspaceAuditControllerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;
    private WorkspaceManagementService management;
    private CloudMemoryService memories;
    private JdbcTemplate jdbc;
    private InMemoryWorkspaceContentRepository objects;

    @BeforeEach
    void setup() {
        var source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:file-audit-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        objects = new InMemoryWorkspaceContentRepository();
        var documents = new JdbcWorkspaceDocumentRepository(source, objects);
        var catalog = new JdbcWorkspaceCatalogRepository(source);
        management = new WorkspaceManagementService(catalog, objects);
        memories = new CloudMemoryService(documents);
        var properties = new WorkspaceManagementProperties();
        properties.setToken("synthetic-audit-token");
        properties.setProjectIds(Set.of(7L));
        var controller =
                new WorkspaceManagementController(
                        management,
                        new WorkspacePublicationService(
                                management,
                                catalog,
                                objects,
                                new AgentScopePublicationValidator(),
                                new WorkspaceArchiveService(management)),
                        properties,
                        json);
        controller.setAudit(
                new WorkspaceAuditService(
                        new JdbcWorkspaceAuditRepository(source), objects, documents));
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    private JsonNode call(String method, Map<String, Object> params, int code) throws Exception {
        var body = new HashMap<>(params);
        body.put("projectId", 7);
        body.put("agentKey", "test-agent");
        return json.readTree(
                mvc.perform(
                                post("/api/internal/workspaces/" + method)
                                        .header("Authorization", "Bearer synthetic-audit-token")
                                        .contentType("application/json")
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().is(code))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private void save(long version, String text) {
        management.save(
                7,
                "test-agent",
                version,
                "[]",
                List.of(
                        new WorkspaceManagementService.Input(
                                "AGENTS.md",
                                text.getBytes(StandardCharsets.UTF_8),
                                null,
                                "text/plain")),
                "tester");
    }

    @Test
    void immutableSnapshotsSurviveEditsAndDeletionAndPagesDoNotRepeat() throws Exception {
        save(0, "first");
        save(1, "second");
        management.save(7, "test-agent", 2, "[]", List.of(), "tester");
        JsonNode page = call("audit-list", Map.of("path", "AGENTS.md", "pageSize", 1), 200);
        long deletion = page.path("items").get(0).path("sequence").asLong();
        assertEquals("DELETE", page.path("items").get(0).path("operationType").asText());
        JsonNode detail =
                call("audit-detail", Map.of("path", "AGENTS.md", "sequence", deletion), 200);
        assertEquals("second", detail.path("before").path("content").asText());
        assertEquals("", detail.path("after").path("content").asText());
        JsonNode second =
                call(
                        "audit-list",
                        Map.of(
                                "path",
                                "AGENTS.md",
                                "pageSize",
                                1,
                                "beforeSequence",
                                page.path("nextCursor").asLong()),
                        200);
        assertTrue(second.path("items").get(0).path("sequence").asLong() < deletion);
        detail =
                call(
                        "audit-detail",
                        Map.of(
                                "path",
                                "AGENTS.md",
                                "sequence",
                                second.path("items").get(0).path("sequence").asLong()),
                        200);
        assertEquals("first", detail.path("before").path("content").asText());
        assertEquals("second", detail.path("after").path("content").asText());
        call("audit-detail", Map.of("path", "knowledge/wrong.md", "sequence", deletion), 404);
        assertEquals(2, objects.objectCount());
        assertEquals(3, call("audit-list", Map.of("path", ""), 200).path("items").size());
    }

    @Test
    void memoryIsReadOnlyScopedToProjectAndReplayCreatesNoExtraAudit() throws Exception {
        jdbc.update(
                "INSERT INTO"
                        + " ha_session(owner_key,session_id,status,created_by,title,last_message_at,created_at,updated_at,project_id,agent_key)"
                        + " VALUES('user-a','session-a','IDLE','user-a','synthetic',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,7,'test-agent')");
        memories.save(
                "user-a", "test-agent", "save-a", "first fact", "session-a", "turn-a", "tool-a");
        memories.save(
                "user-a", "test-agent", "save-a", "first fact", "session-a", "turn-a", "tool-a");
        memories.replaceExactLine(
                "user-a",
                "test-agent",
                "change-a",
                "first fact",
                "second fact",
                "session-a",
                "turn-b",
                "tool-b");
        JsonNode list = call("audit-list", Map.of("path", "MEMORY.md", "ownerKey", "user-a"), 200);
        assertEquals(2, list.path("items").size());
        JsonNode detail =
                call(
                        "audit-detail",
                        Map.of(
                                "path",
                                "MEMORY.md",
                                "ownerKey",
                                "user-a",
                                "sequence",
                                list.path("items").get(0).path("sequence").asLong()),
                        200);
        assertTrue(detail.path("before").path("content").asText().contains("first fact"));
        assertTrue(detail.path("after").path("content").asText().contains("second fact"));
        assertEquals("AGENT", detail.path("operation").path("actorType").asText());
        assertEquals("turn-b", detail.path("operation").path("turnId").asText());
        assertTrue(
                call("memory", Map.of("ownerKey", "user-a"), 200)
                        .path("content")
                        .asText()
                        .contains("second fact"));
        call("memory", Map.of("ownerKey", "other-user"), 403);
        call(
                "audit-detail",
                Map.of(
                        "path",
                        "MEMORY.md",
                        "ownerKey",
                        "other-user",
                        "sequence",
                        list.path("items").get(0).path("sequence").asLong()),
                403);
    }
}
