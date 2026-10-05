package dev.horizen.agent.sandbox.e2b.http;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class WorkspaceArchiveValidatorTest {
    @TempDir Path root;

    @Test
    void rejectsTraversalDevicesAndSymlinksEscapingWorkspace() throws Exception {
        for (String name : new String[] {"../../etc/config", "/etc/config", "C:/config"}) {
            Path archive = archive(new TarArchiveEntry(name, true));
            assertThrows(
                    IOException.class,
                    () -> WorkspaceArchiveValidator.validate(archive, 65536, 10));
        }
        var link = new TarArchiveEntry("link", TarConstants.LF_SYMLINK);
        link.setLinkName("../outside");
        Path archive = archive(link);
        assertThrows(
                IOException.class, () -> WorkspaceArchiveValidator.validate(archive, 65536, 10));
        Path device = archive(new TarArchiveEntry("device", TarConstants.LF_CHR));
        assertThrows(
                IOException.class, () -> WorkspaceArchiveValidator.validate(device, 65536, 10));
    }

    @Test
    void permitsInternalPackageSymlinksButRejectsPathsUnderThemAndEntryOverflow() throws Exception {
        var link = new TarArchiveEntry("node_modules/.bin/tool", TarConstants.LF_SYMLINK);
        link.setLinkName("../tool/bin.js");
        WorkspaceArchiveValidator.validate(archive(link), 65536, 10);
        Path nested = archive(link, new TarArchiveEntry("node_modules/.bin/tool/child"));
        assertThrows(
                IOException.class, () -> WorkspaceArchiveValidator.validate(nested, 65536, 10));
        Path many = archive(new TarArchiveEntry("a"), new TarArchiveEntry("b"));
        assertThrows(IOException.class, () -> WorkspaceArchiveValidator.validate(many, 65536, 1));
    }

    private Path archive(TarArchiveEntry... entries) throws IOException {
        Path path = Files.createTempFile(root, "archive-", ".tar");
        try (var output = new TarArchiveOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries) {
                output.putArchiveEntry(entry);
                output.closeArchiveEntry();
            }
            output.finish();
        }
        return path;
    }
}
