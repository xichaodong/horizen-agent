package dev.horizen.agent.sandbox.e2b.http;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 解压前校验完整 tar，保留归档内部安全链接的可用性。
 */
final class WorkspaceArchiveValidator {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private WorkspaceArchiveValidator() {
    }

    /**
     * 验证工作区归档的条目数量、总容量与路径，拒绝越界路径和不允许的归档内容。
     *
     * @param archive    当前工作区归档校验器持有的归档对象，供相应处理步骤使用。
     * @param maxBytes   本次处理或传输允许的最大字节数。
     * @param maxEntries 归档或目录中允许处理的条目数量上限。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static void validate(Path archive, long maxBytes, int maxEntries) throws IOException {
        if (Files.size(archive) > maxBytes)
            throw new IOException("workspace archive exceeds byte limit");
        Set<Path> names = new HashSet<>();
        Set<Path> links = new HashSet<>();
        long bytes = 0;
        int count = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(archive))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (++count > maxEntries)
                    throw new IOException("workspace archive exceeds entry limit");
                Path name = relative(entry.getName());
                if (!names.add(name)) throw new IOException("duplicate workspace archive path");
                byte type = entry.getLinkFlag();
                if (entry.isSparse()
                        || !(type == TarConstants.LF_NORMAL
                        || type == TarConstants.LF_OLDNORM
                        || type == TarConstants.LF_DIR
                        || type == TarConstants.LF_SYMLINK)) {
                    throw new IOException("unsupported workspace archive entry");
                }
                bytes += entry.getSize();
                if (bytes < 0 || bytes > maxBytes)
                    throw new IOException("workspace archive exceeds expanded limit");
                if (entry.isSymbolicLink()) {
                    Path target = Path.of(entry.getLinkName());
                    if (target.isAbsolute()) throw new IOException("absolute workspace symlink");
                    Path parent = name.getParent();
                    relative((parent == null ? target : parent.resolve(target)).toString());
                    links.add(name);
                }
                long actual = tar.transferTo(OutputStream.nullOutputStream());
                if (actual != entry.getSize())
                    throw new IOException("truncated workspace archive entry");
            }
        }
        for (Path name : names) {
            for (Path parent = name.getParent(); parent != null; parent = parent.getParent()) {
                if (links.contains(parent))
                    throw new IOException("workspace archive path traverses a symlink");
            }
        }
    }

    /**
     * 规范化归档条目的相对路径并拒绝绝对路径或目录穿越。
     *
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的路径结果。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Path relative(String name) throws IOException {
        if (name == null
                || name.indexOf('\0') >= 0
                || name.contains("\\")
                || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("invalid workspace archive path");
        }
        Path path = Path.of(name).normalize();
        if (path.isAbsolute() || path.startsWith(".."))
            throw new IOException("workspace archive path escapes root");
        return path;
    }
}
