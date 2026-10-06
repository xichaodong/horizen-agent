package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.horizen.agent.web.api.status.StatusApi.AgentStatus;
import dev.horizen.agent.web.bootstrap.WorkspaceTestConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

@Import(WorkspaceTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "horizen.local-config=",
                "horizen.agent.api-key=",
                "horizen.agent.gateway.url=",
                "horizen.agent.gateway.token=",
                "horizen.agent.gateway.mode=remote",
                "horizen.trace.enabled=false",
                "horizen.agent.skill-release.enabled=false",
                "ARK_API_KEY=",
                "AGENT_E2B_ENABLED=false",
                "AGENT_STORAGE_MODE=LOCAL",
                "HORIZEN_SNAPSHOT_ENABLED=false",
                "HORIZEN_TRACE_ENABLED=false",
                "HORIZEN_SKILL_RELEASE_ENABLED=false",
                "AGENT_TOOL_GATEWAY_MODE=remote",
                "AGENT_TOOL_GATEWAY_URL=",
                "AGENT_TOOL_GATEWAY_TOKEN=",
                "horizen.agent.artifact.bos.enabled=false"
        })
class AgentWebApplicationTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void startsAndReportsMissingOptionalCredential() {
        AgentStatus status = restTemplate.getForObject("/api/status", AgentStatus.class);

        assertFalse(status.isReady());
        assertFalse(status.getModelName().isBlank());
        assertFalse(status.getGateway().isConfigured());
        assertFalse(status.getTracing().isEnabled());
        assertFalse(status.getSkillRelease().isEnabled());
        assertFalse(status.getSandbox().isEnabled());
    }
}
