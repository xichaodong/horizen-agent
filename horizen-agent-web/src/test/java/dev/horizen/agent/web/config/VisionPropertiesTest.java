package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.web.bootstrap.model.AgentModelFactory;
import org.junit.jupiter.api.Test;

class VisionPropertiesTest {
    @Test
    void disabledVisionDoesNotFallBackToThePrimaryModel() {
        var primary = new AgentProperties("primary-test-key", "https://model.example.test/v1", "text-main", null, null, null, null);
        var vision = new VisionProperties(null, null, null, null);
        assertFalse(vision.isEnabled());
        assertNull(AgentModelFactory.vision(primary, vision));
        assertThrows(IllegalArgumentException.class, () -> new VisionProperties(true, null, null, " "));
    }

    @Test
    void independentNameIsRequiredButProviderCredentialsCanBeShared() {
        var primary = new AgentProperties("primary-test-key", "https://model.example.test/v1", "text-main", null, null, null, null);
        var vision = new VisionProperties(true, "", "", "image-worker");
        var model = AgentModelFactory.vision(primary, vision);
        assertEquals("image-worker", model.getModelName());
        assertNotEquals(primary.getModelName(), model.getModelName());
    }

    @Test
    void switchingProviderCannotReuseThePrimaryCredentialImplicitly() {
        var primary = new AgentProperties("primary-test-key", "https://model.example.test/v1", "text-main", null, null, null, null);
        var vision = new VisionProperties(true, "", "https://another.example.test/v1", "image-worker");
        assertThrows(IllegalArgumentException.class, () -> AgentModelFactory.vision(primary, vision));
    }

    @Test
    void invalidAddressDoesNotEchoEmbeddedCredentials() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new VisionProperties(true, "", "https://user:secret@vision.example.test/v1", "image-worker"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void scriptedDemoCannotCallARealVisionProvider() {
        var primary = new AgentProperties(null, null, null, AgentProperties.ModelMode.SCRIPTED, null, null, null);
        assertNull(AgentModelFactory.vision(primary, new VisionProperties(true, "vision-test-key", "https://vision.example.test/v1", "image-worker")));
    }

    @Test
    void credentialIsExcludedFromConfigurationPrintingAndRedactedFromDiagnostics() {
        var vision = new VisionProperties(true, "vision-test-key", "https://vision.example.test/v1", "image-worker");
        assertFalse(vision.toString().contains("vision-test-key"));
        assertEquals("failed with ***", vision.redact("failed with vision-test-key"));
        assertNull(vision.redact(null));
    }
}
