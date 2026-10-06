package dev.horizen.agent.web;

import dev.horizen.agent.common.config.YamlConfigFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * 真实环境测试共用宿主 YAML 配置，不在 Shell 中导出秘密。
 */
public final class LiveConfiguration {
    private LiveConfiguration() {
    }

    public static Properties aliases(Properties local) {
        local.stringPropertyNames().stream()
                .filter(key -> key.startsWith("horizen.agent.sandbox.e2b."))
                .toList()
                .forEach(
                        key ->
                                local.putIfAbsent(
                                        "AGENT_E2B_"
                                                + key.substring(
                                                        "horizen.agent.sandbox.e2b."
                                                                .length())
                                                .toUpperCase(Locale.ROOT)
                                                .replace('-', '_'),
                                        local.getProperty(key)));
        for (String name : new String[]{"ARK_API_KEY", "ARK_BASE_URL", "ARK_MODEL", "AGENT_VISION_API_KEY", "AGENT_VISION_BASE_URL", "AGENT_VISION_MODEL"}) {
            String configured = local.getProperty(propertyName(name));
            if (configured != null) local.putIfAbsent(name, configured);
        }
        return local;
    }

    public static String value(Properties local, String name) {
        String environment = System.getenv(name);
        if (environment != null) return environment;
        return local.getProperty(name, local.getProperty(propertyName(name), ""));
    }

    public static String environment(String name) {
        if (!Boolean.getBoolean("horizen.e2b.browser.live")
                && !Boolean.getBoolean("horizen.e2b.browser.artifact.live")) return null;
        String environment = System.getenv(name);
        if (environment != null) return environment;
        Path path = Path.of("..", ".env.yml");
        if (!Files.isRegularFile(path)) return null;
        try {
            String configured = value(YamlConfigFiles.load(path), name);
            return configured.isEmpty() ? null : configured;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read local YAML configuration", error);
        }
    }

    private static String propertyName(String name) {
        if (name.startsWith("AGENT_E2B_")) {
            return "horizen.agent.sandbox.e2b."
                    + name.substring("AGENT_E2B_".length())
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '-');
        }
        return switch (name) {
            case "ARK_API_KEY" -> "horizen.agent.api-key";
            case "ARK_BASE_URL" -> "horizen.agent.base-url";
            case "ARK_MODEL" -> "horizen.agent.model-name";
            case "AGENT_VISION_MODEL" -> "horizen.agent.vision.model-name";
            case "AGENT_VISION_BASE_URL" -> "horizen.agent.vision.base-url";
            case "AGENT_VISION_API_KEY" -> "horizen.agent.vision.api-key";
            default -> name;
        };
    }
}
