package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.adapter.agentscope.runtime.HarnessAgentRuntime;
import dev.horizen.agent.adapter.agentscope.runtime.SubagentInteractionMiddleware;
import dev.horizen.agent.adapter.agentscope.runtime.SubagentResultForwardingMiddleware;
import dev.horizen.agent.adapter.agentscope.workspace.release.PublicationStateMiddleware;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.runtime.api.AgentTurnRequest;
import dev.horizen.agent.runtime.api.ToolApprovalDecision;
import dev.horizen.agent.runtime.api.ToolApprovalRequest;
import dev.horizen.agent.web.LiveConfiguration;
import dev.horizen.agent.web.bootstrap.model.AgentModelFactory;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.*;
import io.agentscope.harness.agent.tool.AgentSpawnTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Mono;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 LLM 交接验收；获批操作仅修改进程内的合成计数器。
 */
@EnabledIfSystemProperty(named = "horizen.subagent.model.live", matches = "true")
class SubagentInteractionConfiguredLiveTest {
    @TempDir
    Path workspace;

    @Test
    void realModelDelegatesThenAsksTheParentToApproveBeforeExecuting() throws Exception {
        Properties local = new Properties();
        try (var input = Files.newInputStream(Path.of("..", ".env.yml"))) {
            local.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        var properties =
                new AgentProperties(
                        value(local, "ARK_API_KEY"),
                        value(local, "ARK_BASE_URL"),
                        value(local, "ARK_MODEL"),
                        AgentProperties.ModelMode.REMOTE,
                        5,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1));
        assertTrue(properties.ready(), "Existing model credentials are required");
        var model = AgentModelFactory.primary(properties, new ContextProperties());
        var count = new AtomicInteger();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("record_sample")
                                .description("将固定验收标记写入合成计数器；仅测试用途，必须先审批。")
                                .readOnly(false)
                                .inputSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("value", Map.of("type", "string")),
                                                "required",
                                                List.of("value"),
                                                "additionalProperties",
                                                false))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(
                                PermissionDecision.ask(
                                        "Safety: explicit approval required for the sample action"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        assertEquals("SUBAGENT_ACCEPTANCE", param.getInput().get("value"));
                        count.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("synthetic sample recorded"));
                    }
                });
        Set<String> tools = Set.of("record_sample");
        var agent =
                HarnessAgent.builder()
                        .name("acceptance-parent")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(workspace)
                        .sysPrompt("你是主 Agent，必须先通过 agent_spawn 委派 worker，再根据子任务返回的事项处理。回答简洁。")
                        .maxIters(5)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(
                                PermissionContextState.builder()
                                        .addAllowRule(
                                                "agent_spawn",
                                                new PermissionRule(
                                                        "agent_spawn",
                                                        null,
                                                        PermissionBehavior.ALLOW,
                                                        "acceptance delegation"))
                                        .build())
                        .subagent(
                                SubagentDeclaration.builder()
                                        .name("worker")
                                        .description("执行合成标记任务的测试工作者")
                                        .workspaceMode(WorkspaceMode.SHARED)
                                        .inlineAgentsBody(
                                                "使用 record_sample 完成任务；需要审批时交给主 Agent，未执行时不得声称完成。")
                                        .tools(new ArrayList<>(tools))
                                        .steps(3)
                                        .build())
                        .middleware(
                                new PublicationStateMiddleware(
                                        null, toolkit.getActiveGroups(), Map.of("worker", tools)))
                        .middleware(new SubagentResultForwardingMiddleware())
                        .middleware(new SubagentInteractionMiddleware())
                        .disableWorkspaceContext()
                        .disableMemoryHooks()
                        .disableMemoryTools()
                        .disableShellTool()
                        .disableTranscript()
                        .build();
        try (var runtime =
                     new HarnessAgentRuntime(
                             agent,
                             context -> {
                                 context.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
                                 context.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, 90);
                             },
                             null)) {
            var events =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("synthetic-owner")
                                            .sessionId("subagent-model-session")
                                            .turnId("subagent-model-turn")
                                            .message(
                                                    "请先委派 worker，目标是调用 record_sample，参数 value 必须是 SUBAGENT_ACCEPTANCE。"
                                                            + "子任务需要审批时，由你使用现有工具流程申请审批，不能自行批准。")
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(120));
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.SUBAGENT_STARTED));
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e.getType() == AgentRuntimeEvent.Type.SUBAGENT_RESULT
                                                    && "needs_parent".equals(e.getStatus())));
            var required =
                    events.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.APPROVAL_REQUIRED)
                            .findFirst()
                            .orElseThrow();
            assertNull(required.getSource());
            assertFalse(
                    events.stream()
                            .anyMatch(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED));
            assertEquals(0, count.get());
            var pending = (ToolApprovalRequest) ((List<?>) required.getDetails()).get(0);
            assertEquals("record_sample", pending.getToolName());
            assertEquals(Map.of("value", "SUBAGENT_ACCEPTANCE"), pending.getInput());
            var resumed =
                    runtime.stream(
                                    AgentTurnRequest.builder()
                                            .ownerKey("synthetic-owner")
                                            .sessionId("subagent-model-session")
                                            .turnId("subagent-model-turn")
                                            .message("approved")
                                            .approvalDecisions(
                                                    List.of(
                                                            new ToolApprovalDecision(
                                                                    pending.getToolCallId(),
                                                                    pending.getToolName(),
                                                                    pending.getContent(),
                                                                    pending.getInput(),
                                                                    true)))
                                            .build())
                            .collectList()
                            .block(Duration.ofSeconds(60));
            assertEquals(1, count.get());
            assertEquals(
                    1,
                    resumed.stream()
                            .filter(e -> e.getType() == AgentRuntimeEvent.Type.TURN_COMPLETED)
                            .count());
            System.out.println(
                    "Subagent real model: delegated request, parent approval, frozen input and one synthetic"
                            + " execution passed");
        }
    }

    private static String value(Properties local, String key) {
        return LiveConfiguration.value(local, key);
    }
}
