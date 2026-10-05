package dev.horizen.agent.common.error;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ExceptionsTest {
    @Test
    void resolvesNestedCauseWithoutLoopingOnCyclicChains() {
        var deepest = new IllegalArgumentException("root");
        assertSame(deepest, Exceptions.rootCause(new IllegalStateException("outer", deepest)));
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> assertSame(second, Exceptions.rootCause(first)));
    }
}
