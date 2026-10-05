package dev.horizen.agent.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

class GatewayPropertiesTest {
    @Test
    void configurationDoesNotExposeCredential() {
        GatewayProperties properties =
                new GatewayProperties(
                        "http://127.0.0.1:9000/api/agent/v1",
                        "example-test-token",
                        null,
                        Set.of("catalog_lookup"));

        assertTrue(properties.configured());
        assertEquals(Duration.ofSeconds(30), properties.getTimeout());
        assertFalse(properties.toString().contains("example-test-token"));
        assertEquals("Authorization: ***", properties.redact("Authorization: example-test-token"));
    }

    @Test
    void missingCredentialsLeaveGatewayDisabled() {
        assertFalse(new GatewayProperties(null, null, null, null).configured());
        assertFalse(new GatewayProperties("http://127.0.0.1:9000", "", null, null).configured());
    }

    @Test
    void refusesCredentialBearingUrlsAndInvalidTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GatewayProperties(
                                "http://user:password@127.0.0.1:9000", "test", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GatewayProperties(
                                "http://127.0.0.1:9000?token=secret", "test", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GatewayProperties("http://127.0.0.1:9000", "test", Duration.ZERO, null));
    }

    @Test
    void bindsHostAttributesWithoutAddingBusinessKnowledgeToRuntime() {
        var properties =
                new GatewayProperties(
                        "http://provider.example/api",
                        "synthetic-token",
                        null,
                        Set.of(),
                        GatewayProperties.Mode.REMOTE,
                        null,
                        Map.of("userId", "100", "businessSubjectId", "42"));
        assertEquals("100", properties.getCallerAttributes().get("userId"));
        assertFalse(properties.toString().contains("100"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GatewayProperties(
                                "http://provider.example/api",
                                "synthetic-token",
                                null,
                                Set.of(),
                                GatewayProperties.Mode.REMOTE,
                                null,
                                Map.of("token", "not-allowed")));
    }

    @Test
    void springConfigurationPreservesCallerAttributeKeys() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "horizen.agent.gateway.url", "https://provider.example/api",
                                "horizen.agent.gateway.token", "synthetic-token",
                                "horizen.agent.gateway.caller-attributes.[userId]", "100",
                                "horizen.agent.gateway.caller-attributes.[optId]", "101"));
        var properties =
                new Binder(source)
                        .bind("horizen.agent.gateway", Bindable.of(GatewayProperties.class))
                        .get();
        assertEquals(Map.of("userId", "100", "optId", "101"), properties.getCallerAttributes());
    }
}
