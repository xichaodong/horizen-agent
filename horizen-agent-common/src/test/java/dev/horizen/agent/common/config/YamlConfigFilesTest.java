package dev.horizen.agent.common.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

class YamlConfigFilesTest {
    @Test
    void keepsQuotedCredentialsUnicodeAndNestedConfiguration() throws Exception {
        var config =
                YamlConfigFiles.load(
                        new StringReader(
                                """
                                        horizen:
                                          agent:
                                            storage:
                                              jdbc-password: 'value:#= 中文'
                                              mode: LOCAL
                                            gateway:
                                              allowed-tools: [read_file, memory_search]
                                        empty: null
                                        enabled: false
                                        """));
        assertEquals("value:#= 中文", config.getProperty("horizen.agent.storage.jdbc-password"));
        assertEquals("LOCAL", config.getProperty("horizen.agent.storage.mode"));
        assertEquals("memory_search", config.getProperty("horizen.agent.gateway.allowed-tools[1]"));
        assertEquals("", config.getProperty("empty"));
        assertEquals("false", config.getProperty("enabled"));
    }

    @Test
    void rejectsLegacyKeyValueSyntaxAndDuplicateYamlKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> YamlConfigFiles.load(new StringReader("mode=LOCAL\npassword=example\n")));
        assertThrows(
                Exception.class,
                () -> YamlConfigFiles.load(new StringReader("mode: LOCAL\nmode: DISTRIBUTED\n")));
    }
}
