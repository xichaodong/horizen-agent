package dev.horizen.agent.web.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.evaluation.EvaluationProtocol;
import dev.horizen.agent.web.bootstrap.WorkspaceTestConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Import(WorkspaceTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "horizen.local-config=",
                "horizen.agent.model-mode=SCRIPTED",
                "AGENT_MODEL_MODE=SCRIPTED",
                "horizen.agent.api-key=",
                "horizen.agent.gateway.mode=remote",
                "horizen.agent.gateway.url=",
                "horizen.agent.gateway.token=",
                "horizen.agent.skill-release.enabled=false",
                "horizen.trace.enabled=false",
                "horizen.agent.sandbox.e2b.enabled=false",
                "AGENT_E2B_ENABLED=false",
                "HORIZEN_SKILL_RELEASE_ENABLED=false",
                "HORIZEN_TRACE_ENABLED=false",
                "AGENT_STORAGE_MODE=LOCAL",
                "horizen.agent.storage.mode=LOCAL",
                "horizen.agent.workspace-release.enabled=false",
                "horizen.agent.artifact.bos.enabled=false",
                "HORIZEN_SNAPSHOT_ENABLED=false",
                "horizen.agent.evaluation.enabled=true",
                "horizen.agent.evaluation.token=offline-test-token"
        })
class CloudEvaluationHttpTest {
    @Autowired
    TestRestTemplate http;

    @Test
    void
    authenticatedHttpSubmissionCompletesThroughProductionRuntimeAndRejectsUnauthenticatedAccess()
            throws Exception {
        var request = new EvaluationProtocol.Start();
        String executionId = "http-" + UUID.randomUUID();
        request.setExecutionId(executionId);
        request.setCaseRunId(12L);
        request.setInput(
                Map.of(
                        "case",
                        Map.of(
                                "steps",
                                List.of(
                                        Map.of("userInput", "hello evaluation"),
                                        Map.of("userInput", "follow up")))));
        assertEquals(
                401,
                http.postForEntity("/api/evaluation/v1/runs", request, String.class)
                        .getStatusCode()
                        .value());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("offline-test-token");
        var started =
                http.postForEntity(
                        "/api/evaluation/v1/runs",
                        new HttpEntity<>(request, headers),
                        EvaluationProtocol.Status.class);
        assertTrue(started.getStatusCode().is2xxSuccessful());
        EvaluationProtocol.Status result = started.getBody();
        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus(), result.getErrorMessage());
        assertFalse(result.getEvents().isEmpty());
        assertNotNull(result.getActualOutput().get("lastAgentMessage"));
        assertTrue(
                result.getEvents().stream().anyMatch(e -> "LLM_CALL".equals(e.getEventType())),
                result.getEvents().toString());
        assertEquals(
                404,
                http.exchange(
                                "/api/evaluation/v1/runs/" + executionId,
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                String.class)
                        .getStatusCode()
                        .value());
    }
}
