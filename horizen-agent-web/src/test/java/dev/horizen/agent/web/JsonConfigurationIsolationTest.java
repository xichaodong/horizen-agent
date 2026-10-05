package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxClient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

class JsonConfigurationIsolationTest {
    @Test
    void adapterInitializationDoesNotChangeCommonJsonConfiguration() throws Exception {
        var mapper = JsonUtils.newMapper();
        var before = mapper.writeValueAsString(Instant.parse("2026-10-03T00:00:00Z"));
        var modules = Set.copyOf(mapper.getRegisteredModuleIds());
        Class.forName("dev.horizen.agent.evaluation.EvaluationEvidence");
        Class.forName("dev.horizen.agent.evaluation.EvaluationRunService");
        new HttpE2bSandboxClient(null, null);
        assertEquals(before, mapper.writeValueAsString(Instant.parse("2026-10-03T00:00:00Z")));
        assertEquals(modules, mapper.getRegisteredModuleIds());
    }
}
