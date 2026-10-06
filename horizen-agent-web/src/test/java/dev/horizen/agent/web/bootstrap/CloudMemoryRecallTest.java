package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.storage.jdbc.repository.workspace.JdbcWorkspaceDocumentRepository;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;
import dev.horizen.agent.tool.governance.ToolDescriptorRegistry;
import dev.horizen.agent.web.bootstrap.model.ScriptedWebModel;
import dev.horizen.agent.web.bootstrap.runtime.AgentRuntimeFactory;
import dev.horizen.agent.web.bootstrap.runtime.AgentToolRegistry;
import dev.horizen.agent.web.bootstrap.runtime.WorkspaceRuntimeConfigurer;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.MultimodalProperties;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

class CloudMemoryRecallTest {
    @TempDir
    Path workspace;

    @Test
    void correctedAndForgottenFactsAreNotRecalledFromStructuredAudit() {
        var documents = documents();
        var memory = new CloudMemoryService(documents);
        memory.save("owner", AgentRuntimeFactory.AGENT_KEY, "save", "- output_preference: alpha");
        memory.replaceExactLine(
                "owner",
                AgentRuntimeFactory.AGENT_KEY,
                "update",
                "- output_preference: alpha",
                "- output_preference: beta");
        var agent = agent(documents);
        assertFalse(
                invoke(agent, "memory_search", Map.of("query", "alpha"), "owner")
                        .contains("output_preference: alpha"));
        assertTrue(
                invoke(agent, "memory_search", Map.of("query", "beta"), "owner").contains("beta"));
        memory.replaceExactLine(
                "owner", AgentRuntimeFactory.AGENT_KEY, "forget", "- output_preference: beta", "");
        assertFalse(
                invoke(agent, "memory_search", Map.of("query", "beta"), "owner")
                        .contains("output_preference: beta"));
        assertFalse(
                invoke(
                        agent,
                        "memory_get",
                        Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 50),
                        "owner")
                        .contains("beta"));
        assertTrue(
                documents
                        .list("owner", AgentRuntimeFactory.AGENT_KEY, "global", "memory/", 10, 0)
                        .isEmpty());
    }

    @Test
    void recallIsOwnerScopedAndOperationAuditCannotBeUsedAsCurrentMemory() {
        var documents = documents();
        new CloudMemoryService(documents)
                .save("owner", AgentRuntimeFactory.AGENT_KEY, "save", "- private-fact");
        var agent = agent(documents);
        assertFalse(
                invoke(agent, "memory_search", Map.of("query", "private-fact"), "other-owner")
                        .contains("private-fact"));
        String audit =
                invoke(
                        agent,
                        "memory_get",
                        Map.of("path", "memory/2026-10-03.md", "startLine", 1, "endLine", 20),
                        "owner");
        assertTrue(audit.contains("only recalls current MEMORY.md"));
        assertFalse(audit.contains("private-fact"));
        assertTrue(
                invoke(
                        agent,
                        "memory_get",
                        Map.of("path", "../MEMORY.md", "startLine", 1, "endLine", 20),
                        "owner")
                        .contains("only recalls current MEMORY.md"));
    }

    @Test
    void recallHasBoundedMatchesAndLineRanges() {
        var documents = documents();
        new CloudMemoryService(documents)
                .save(
                        "owner",
                        AgentRuntimeFactory.AGENT_KEY,
                        "save",
                        ("- matching-fact " + "x".repeat(300) + "\n").repeat(300));
        var agent = agent(documents);
        String matches = invoke(agent, "memory_search", Map.of("query", "matching-fact"), "owner");
        assertEquals(20, matches.lines().filter(line -> line.startsWith("MEMORY.md#")).count());
        assertTrue(
                invoke(
                        agent,
                        "memory_get",
                        Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 201),
                        "owner")
                        .contains("at most 200 lines"));
        assertTrue(
                invoke(
                        agent,
                        "memory_get",
                        Map.of("path", "MEMORY.md", "startLine", 1, "endLine", 200),
                        "owner")
                        .length()
                        <= 32_768);
        assertTrue(
                invoke(agent, "memory_search", Map.of("query", ""), "owner")
                        .contains("query must contain"));
        assertTrue(
                invoke(agent, "memory_search", Map.of("query", "x".repeat(257)), "owner")
                        .contains("query must contain"));
    }

    @Test
    void reusedModelCallIdsAcrossSessionsAndTurnsDoNotSuppressDistinctWrites() {
        var documents = documents();
        var agent = agent(documents);
        write(agent, "memory_save", Map.of("content", "- first-fact"), "session-a", "turn-a");
        write(agent, "memory_save", Map.of("content", "- first-fact"), "session-a", "turn-a");
        write(agent, "memory_save", Map.of("content", "- second-fact"), "session-b", "turn-a");
        write(agent, "memory_save", Map.of("content", "- third-fact"), "session-b", "turn-b");
        String content =
                new CloudMemoryService(documents)
                        .current("owner", AgentRuntimeFactory.AGENT_KEY)
                        .orElseThrow()
                        .getContent();
        assertEquals(1, content.lines().filter("- first-fact"::equals).count());
        assertTrue(content.contains("- second-fact"));
        assertTrue(content.contains("- third-fact"));
        write(
                agent,
                "memory_manage",
                Map.of("current", "- first-fact", "replacement", "- first-updated"),
                "session-a",
                "turn-a");
        write(
                agent,
                "memory_manage",
                Map.of("current", "- second-fact", "replacement", "- second-updated"),
                "session-b",
                "turn-a");
        content =
                new CloudMemoryService(documents)
                        .current("owner", AgentRuntimeFactory.AGENT_KEY)
                        .orElseThrow()
                        .getContent();
        assertTrue(content.contains("- first-updated"));
        assertTrue(content.contains("- second-updated"));
    }

    @Test
    void committedMemoryOperationKeysRemainReplayableAfterRemovingTheToolJournal() {
        var documents = documents();
        // 这是上一版本已提交记录使用的键格式，不是重新计算的键。
        String committed = "4ae29377a6a79ccb77f07f185cf7976d201ca5fd091b7a04c60d51b6e9a45f26";
        new CloudMemoryService(documents)
                .save("owner", AgentRuntimeFactory.AGENT_KEY, committed, "- existing-fact");
        write(
                agent(documents),
                "memory_save",
                Map.of("content", "- existing-fact"),
                "session-a",
                "turn-a");
        String content =
                new CloudMemoryService(documents)
                        .current("owner", AgentRuntimeFactory.AGENT_KEY)
                        .orElseThrow()
                        .getContent();
        assertEquals(1, content.lines().filter("- existing-fact"::equals).count());
    }

    @Test
    void storageFailureAndMissingOwnerAreNotReportedAsEmptyMemory() {
        var unavailable = Mockito.mock(WorkspaceDocumentRepository.class);
        Mockito.when(unavailable.find(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("unavailable"));
        var agent = agent(unavailable);
        assertTrue(
                invoke(agent, "memory_search", Map.of("query", "fact"), "owner")
                        .contains("memory_recall_failed"));
        assertTrue(
                invoke(agent, "memory_search", Map.of("query", "fact"), null)
                        .contains("owner identity is required"));
    }

    private HarnessAgent agent(WorkspaceDocumentRepository documents) {
        var model = new ScriptedWebModel();
        var toolkit = new Toolkit();
        AgentToolRegistry.createToolGroups(toolkit);
        var agent =
                HarnessAgent.builder()
                        .name(AgentRuntimeFactory.AGENT_KEY)
                        .model(model)
                        .workspace(workspace)
                        .toolkit(toolkit)
                        .filesystemRoute(
                                "MEMORY.md",
                                WorkspaceRuntimeConfigurer.cloudMemoryFilesystem(documents, "root"))
                        .disableMemoryHooks()
                        .disableSubagents()
                        .disableShellTool()
                        .disableTranscript()
                        .build();
        AgentToolRegistry.registerRuntimeTools(
                agent,
                model,
                null,
                null,
                null,
                new E2bSandboxProperties(false, "", "", "", "", "", null, null, null, null, null),
                new MultimodalProperties(null, null, null, null),
                new ToolDescriptorRegistry(),
                new CloudMemoryService(documents));
        return agent;
    }

    private static void write(
            HarnessAgent agent,
            String name,
            Map<String, Object> input,
            String session,
            String turn) {
        var context =
                RuntimeContext.builder()
                        .userId("owner")
                        .sessionId(session)
                        .put(
                                ToolInvocationScope.class,
                                new ToolInvocationScope("owner", session, turn))
                        .build();
        var result =
                agent.getToolkit()
                        .getTool(name)
                        .callAsync(
                                ToolCallParam.builder()
                                        .input(input)
                                        .runtimeContext(context)
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("reused-call-id")
                                                        .name(name)
                                                        .input(input)
                                                        .build())
                                        .build())
                        .block();
        assertNotEquals(ToolResultState.ERROR, result.getState());
    }

    private static String invoke(
            HarnessAgent agent, String name, Map<String, Object> input, String owner) {
        var result =
                agent.getToolkit()
                        .getTool(name)
                        .callAsync(
                                ToolCallParam.builder()
                                        .input(input)
                                        .runtimeContext(
                                                RuntimeContext.builder()
                                                        .userId(owner)
                                                        .sessionId("fresh-session")
                                                        .build())
                                        .build())
                        .block();
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static JdbcWorkspaceDocumentRepository documents() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:recall_"
                                + UUID.randomUUID().toString().replace("-", "")
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql")).execute(source);
        return new JdbcWorkspaceDocumentRepository(
                source, InMemoryWorkspaceContentRepository.shared(source));
    }
}
