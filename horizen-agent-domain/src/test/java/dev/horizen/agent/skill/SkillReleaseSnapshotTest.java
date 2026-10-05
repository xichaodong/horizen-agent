package dev.horizen.agent.skill;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SkillReleaseSnapshotTest {
    @Test
    void binaryResourcesAreImmutableAtEveryPublicBoundary() {
        byte[] original = {1, 2};
        var skill = SkillReleaseSnapshot.skill("# synthetic skill", Map.of("ref.bin", original));
        original[0] = 8;
        var exposed = skill.resources();
        exposed.get("ref.bin")[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, skill.resources().get("ref.bin"));
        assertThrows(UnsupportedOperationException.class, () -> exposed.put("extra", new byte[0]));
    }
}
