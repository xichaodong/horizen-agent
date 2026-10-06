package dev.horizen.agent.common;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.io.BoundedStreams;
import dev.horizen.agent.common.process.ShellQuoteUtils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

class CommonUtilsTest {
    @Test
    void preservesDigestAndShellArgumentContracts() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DigestUtils.sha256Hex("abc"));
        assertEquals("'a'\"'\"'b'", ShellQuoteUtils.quote("a'b"));
    }

    @Test
    void byteLimitRejectsTheFirstByteOverCapacity() throws Exception {
        assertArrayEquals(
                new byte[]{1, 2},
                BoundedStreams.read(new ByteArrayInputStream(new byte[]{1, 2}), 2));
        assertThrows(
                IOException.class,
                () -> BoundedStreams.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), 2));
    }
}
