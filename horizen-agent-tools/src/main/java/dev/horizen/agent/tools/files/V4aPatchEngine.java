package dev.horizen.agent.tools.files;

import dev.horizen.agent.common.process.ShellQuoteUtils;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V4A 多文件补丁引擎：先解析并校验全部操作，再应用变更。 */
final class V4aPatchEngine {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private V4aPatchEngine() {}

    /**
     * 应用4A补丁引擎。
     *
     * @param fs 当前4A补丁引擎持有的fs对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param patch 当前4A补丁引擎使用的补丁，供其处理与状态记录使用。
     * @return 本次操作返回的结果结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static Result apply(AbstractFilesystem fs, RuntimeContext context, String patch) {
        List<Operation> operations = parse(patch);
        Map<String, String> overlay = new LinkedHashMap<>();
        Set<String> deleted = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (Operation operation : operations) {
            try {
                simulate(fs, context, operation, overlay, deleted);
            } catch (IllegalArgumentException error) {
                errors.add(operation.path + ": " + error.getMessage());
            }
        }
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    "Patch validation failed (no files were modified): "
                            + String.join(" | ", errors));

        List<String> modified = new ArrayList<>();
        for (Operation operation : operations) {
            switch (operation.type) {
                case ADD ->
                        writeContent(
                                fs,
                                context,
                                operation.target(),
                                overlay.get(operation.target()),
                                false);
                case UPDATE -> {
                    String current = read(fs, context, operation.path, Map.of(), Set.of());
                    writeContent(
                            fs, context, operation.path, overlay.get(operation.target()), true);
                }
                case DELETE -> require(fs.delete(context, operation.path));
                case MOVE -> require(fs.move(context, operation.path, operation.newPath));
            }
            modified.add(
                    operation.type == Type.MOVE
                            ? operation.path + " -> " + operation.newPath
                            : operation.path);
        }
        return new Result(List.copyOf(modified));
    }

    /**
     * 完成当前操作的simulate步骤，按实现更新相应状态或依赖。
     *
     * @param fs 当前4A补丁引擎持有的fs对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param operation 当前4A补丁引擎持有的操作对象，供相应处理步骤使用。
     * @param overlay overlay的索引映射，供按键查找或归并当前组件的数据。
     * @param deleted 删除的去重集合，供成员查找或范围检查使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void simulate(
            AbstractFilesystem fs,
            RuntimeContext context,
            Operation operation,
            Map<String, String> overlay,
            Set<String> deleted) {
        switch (operation.type) {
            case ADD -> {
                if (exists(fs, context, operation.path, overlay, deleted)) {
                    throw new IllegalArgumentException("file already exists");
                }
                overlay.put(operation.path, operation.addContent());
            }
            case DELETE -> {
                read(fs, context, operation.path, overlay, deleted);
                overlay.remove(operation.path);
                deleted.add(operation.path);
            }
            case MOVE -> {
                String content = read(fs, context, operation.path, overlay, deleted);
                if (exists(fs, context, operation.newPath, overlay, deleted)) {
                    throw new IllegalArgumentException("destination already exists");
                }
                overlay.remove(operation.path);
                deleted.add(operation.path);
                overlay.put(operation.newPath, content);
                deleted.remove(operation.newPath);
            }
            case UPDATE -> {
                String content = read(fs, context, operation.path, overlay, deleted);
                for (Hunk hunk : operation.hunks) content = applyHunk(content, hunk);
                overlay.put(operation.path, content);
                deleted.remove(operation.path);
            }
        }
    }

    /**
     * 应用修改片段。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param hunk 当前4A补丁引擎持有的修改片段对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String applyHunk(String content, Hunk hunk) {
        List<String> search = new ArrayList<>();
        List<String> replacement = new ArrayList<>();
        for (HunkLine line : hunk.lines) {
            if (line.prefix != '+') search.add(line.content);
            if (line.prefix != '-') replacement.add(line.content);
        }
        String oldText = String.join("\n", search);
        String newText = String.join("\n", replacement);
        if (oldText.isEmpty()) {
            if (hunk.hint == null || hunk.hint.isBlank()) {
                return content.stripTrailing() + "\n" + newText + "\n";
            }
            int first = content.indexOf(hunk.hint);
            if (first < 0 || first != content.lastIndexOf(hunk.hint)) {
                throw new IllegalArgumentException(
                        "addition-only hunk context is missing or ambiguous: " + hunk.hint);
            }
            int end = content.indexOf('\n', first);
            if (end < 0) return content + "\n" + newText;
            return content.substring(0, end + 1) + newText + "\n" + content.substring(end + 1);
        }
        Match match = findUnique(content, oldText);
        if (match == null) {
            if (!newText.isBlank() && content.contains(newText) && !content.contains(oldText))
                return content;
            throw new IllegalArgumentException(
                    "hunk text not found or ambiguous"
                            + (hunk.hint == null ? "" : " near " + hunk.hint));
        }
        return content.substring(0, match.start)
                + reindent(content.substring(match.start, match.end), oldText, newText)
                + content.substring(match.end);
    }

    /**
     * 查找Unique。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param pattern 当前4A补丁引擎使用的校验模式，供其处理与状态记录使用。
     * @return 本次操作返回的匹配结果。
     */
    private static Match findUnique(String content, String pattern) {
        List<Match> exact = occurrences(content, pattern);
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null;
        String[] wanted = pattern.split("\n", -1);
        String[] actual = content.split("\n", -1);
        Match found = null;
        int offset = 0;
        int[] starts = new int[actual.length];
        for (int i = 0; i < actual.length; i++) {
            starts[i] = offset;
            offset += actual[i].length() + 1;
        }
        for (int i = 0; i + wanted.length <= actual.length; i++) {
            boolean same = true;
            for (int j = 0; j < wanted.length; j++) {
                if (!actual[i + j].strip().equals(wanted[j].strip())) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;
            int end = starts[i + wanted.length - 1] + actual[i + wanted.length - 1].length();
            if (found != null) return null;
            found = new Match(starts[i], end);
        }
        return found;
    }

    /**
     * 生成当前操作所需的reindent文本，供调用方继续处理。
     *
     * @param region 当前4A补丁引擎使用的区域，供其处理与状态记录使用。
     * @param oldText 当前4A补丁引擎使用的old文本，供其处理与状态记录使用。
     * @param newText 当前4A补丁引擎使用的新建文本，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String reindent(String region, String oldText, String newText) {
        String first = region.lines().findFirst().orElse("");
        String oldFirst = oldText.lines().findFirst().orElse("");
        String indent = first.substring(0, first.length() - first.stripLeading().length());
        String oldIndent =
                oldFirst.substring(0, oldFirst.length() - oldFirst.stripLeading().length());
        if (indent.equals(oldIndent)) return newText;
        return newText.lines()
                .map(line -> line.isBlank() ? line : indent + line.stripLeading())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * 计算或取得本方法声明的结果，供当前V4aPatchEngine处理步骤使用。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理得到的结果集合。
     */
    private static List<Match> occurrences(String content, String value) {
        List<Match> values = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = content.indexOf(value, from);
            if (start < 0) return values;
            values.add(new Match(start, start + value.length()));
            from = start + Math.max(1, value.length());
        }
    }

    /**
     * 检查是否存在4A补丁引擎。
     *
     * @param fs 当前4A补丁引擎持有的fs对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @param overlay overlay的索引映射，供按键查找或归并当前组件的数据。
     * @param deleted 删除的去重集合，供成员查找或范围检查使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean exists(
            AbstractFilesystem fs,
            RuntimeContext context,
            String path,
            Map<String, String> overlay,
            Set<String> deleted) {
        if (overlay.containsKey(path)) return true;
        return !deleted.contains(path) && fs.exists(context, path);
    }

    /**
     * 读取4A补丁引擎。
     *
     * @param fs 当前4A补丁引擎持有的fs对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @param overlay overlay的索引映射，供按键查找或归并当前组件的数据。
     * @param deleted 删除的去重集合，供成员查找或范围检查使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String read(
            AbstractFilesystem fs,
            RuntimeContext context,
            String path,
            Map<String, String> overlay,
            Set<String> deleted) {
        if (overlay.containsKey(path)) return overlay.get(path);
        if (deleted.contains(path)) throw new IllegalArgumentException("file not found");
        var result = fs.read(context, path, 0, Integer.MAX_VALUE);
        if (!result.isSuccess() || result.fileData() == null)
            throw new IllegalArgumentException("file not found");
        return result.fileData().content();
    }

    /**
     * 取得并校验4A补丁引擎。
     *
     * @param result 本次处理已有的结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void require(WriteResult result) {
        if (!result.isSuccess()) throw new IllegalStateException(result.error());
    }

    /**
     * 取得并校验4A补丁引擎。
     *
     * @param result 本次处理已有的结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void require(EditResult result) {
        if (!result.isSuccess()) throw new IllegalStateException(result.error());
    }

    /**
     * 写入正文。
     *
     * @param fs 当前4A补丁引擎持有的fs对象，供相应处理步骤使用。
     * @param context 当前执行上下文，提供关联标识和宿主绑定信息。
     * @param path 需要读取、写入或校验的路径。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param overwrite overwrite的状态标记，用于选择当前组件的处理路径。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static void writeContent(
            AbstractFilesystem fs,
            RuntimeContext context,
            String path,
            String content,
            boolean overwrite) {
        if (fs instanceof AbstractSandboxFilesystem sandbox) {
            String encoded =
                    Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            String command =
                    (overwrite ? "" : "test ! -e " + quote(path) + " || exit 17; ")
                            + "mkdir -p "
                            + quote(parent(path))
                            + "; printf %s '"
                            + encoded
                            + "' | base64 -d > "
                            + quote(path);
            var result = sandbox.execute(context, command, 60);
            if (!result.isSuccess()) throw new IllegalStateException(result.output());
            return;
        }
        if (!overwrite) {
            require(fs.write(context, path, content));
            return;
        }
        String current = read(fs, context, path, Map.of(), Set.of());
        require(fs.edit(context, path, current, content, false));
    }

    /**
     * 生成当前操作所需的parent文本，供调用方继续处理。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次处理生成或读取的文本。
     */
    private static String parent(String path) {
        Path parent = Path.of(path).getParent();
        return parent == null ? "." : parent.toString();
    }

    /**
     * 转义4A补丁引擎。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String quote(String value) {
        return ShellQuoteUtils.quote(value);
    }

    /**
     * 解析4A补丁引擎。
     *
     * @param patch 当前4A补丁引擎使用的补丁，供其处理与状态记录使用。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static List<Operation> parse(String patch) {
        if (patch == null || patch.isBlank())
            throw new IllegalArgumentException("patch content required");
        String[] lines = patch.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Operation> operations = new ArrayList<>();
        Operation current = null;
        Hunk hunk = null;
        for (String line : lines) {
            if (line.matches("^\\*\\*\\*\\s*Begin\\s+Patch\\s*$")
                    || line.matches("^\\*\\*\\*\\s*End\\s+Patch\\s*$")) continue;
            Type type = null;
            String path = null;
            String newPath = null;
            if (line.matches("^\\*\\*\\*\\s*Update\\s+File:.*")) {
                type = Type.UPDATE;
                path = header(line);
            } else if (line.matches("^\\*\\*\\*\\s*Add\\s+File:.*")) {
                type = Type.ADD;
                path = header(line);
            } else if (line.matches("^\\*\\*\\*\\s*Delete\\s+File:.*")) {
                type = Type.DELETE;
                path = header(line);
            } else if (line.matches("^\\*\\*\\*\\s*Move\\s+File:.*")) {
                type = Type.MOVE;
                String value = header(line);
                int arrow = value.indexOf("->");
                if (arrow < 0) throw new IllegalArgumentException("move requires src -> dst");
                path = value.substring(0, arrow).trim();
                newPath = value.substring(arrow + 2).trim();
            }
            if (type != null) {
                if (current != null) {
                    if (hunk != null && !hunk.lines.isEmpty()) current.hunks.add(hunk);
                    operations.add(current);
                }
                current = new Operation(type, safe(path), newPath == null ? null : safe(newPath));
                hunk = null;
                if (type == Type.DELETE || type == Type.MOVE) {
                    operations.add(current);
                    current = null;
                }
                continue;
            }
            if (current == null) continue;
            if (line.startsWith("@@")) {
                if (hunk != null && !hunk.lines.isEmpty()) current.hunks.add(hunk);
                String hint = line.replaceFirst("^@@\\s*", "").replaceFirst("\\s*@@.*$", "");
                hunk = new Hunk(hint.isBlank() ? null : hint);
                continue;
            }
            if (line.isEmpty()) continue;
            if (hunk == null) hunk = new Hunk(null);
            char prefix = line.charAt(0);
            hunk.lines.add(
                    new HunkLine(
                            prefix == '+' || prefix == '-' || prefix == ' ' ? prefix : ' ',
                            prefix == '+' || prefix == '-' || prefix == ' '
                                    ? line.substring(1)
                                    : line));
        }
        if (current != null) {
            if (hunk != null && !hunk.lines.isEmpty()) current.hunks.add(hunk);
            operations.add(current);
        }
        if (operations.isEmpty())
            throw new IllegalArgumentException("patch contains no operations");
        for (Operation operation : operations) {
            if (operation.type == Type.UPDATE && operation.hunks.isEmpty())
                throw new IllegalArgumentException("update has no hunks: " + operation.path);
        }
        return operations;
    }

    /**
     * 生成当前操作所需的header文本，供调用方继续处理。
     *
     * @param line 本次处理的文本行。
     * @return 本次处理生成或读取的文本。
     */
    private static String header(String line) {
        return line.substring(line.indexOf(':') + 1).trim();
    }

    /**
     * 生成当前操作所需的safe文本，供调用方继续处理。
     *
     * @param raw 当前4A补丁引擎使用的原始，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String safe(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("\\"))
            throw new IllegalArgumentException("invalid patch path");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().contains(".git")) {
            throw new IllegalArgumentException("patch path must stay inside workspace: " + raw);
        }
        return path.toString().replace('\\', '/');
    }

    /** 4A补丁引擎使用的状态或策略分类，具体分支按枚举成员区分。 */
    enum Type {
        /** 新增目标文件或内容。 */
        ADD,
        /** 修改已有内容。 */
        UPDATE,
        /** 删除指定目标内容。 */
        DELETE,
        /** 移动目标文件并维护相应路径。 */
        MOVE
    }

    /** 4A补丁引擎内部的操作，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Operation {
        /** 本对象的协议类别，用于选择对应的解析或呈现规则。 */
        final Type type;

        /** 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。 */
        final String path;

        /** 补丁移动或创建操作采用的目标相对路径。 */
        final String newPath;

        /** hunks的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        final List<Hunk> hunks = new ArrayList<>();

        /**
         * 生成当前操作所需的target文本，供调用方继续处理。
         *
         * @return 本次处理生成或读取的文本。
         */
        String target() {
            return type == Type.MOVE ? newPath : path;
        }

        /**
         * 增加正文。
         *
         * @return 本次处理生成或读取的文本。
         */
        String addContent() {
            return hunks.stream()
                    .flatMap(h -> h.lines.stream())
                    .filter(l -> l.prefix == '+')
                    .map(l -> l.content)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }
    }

    /** 4A补丁引擎内部的修改片段，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Hunk {
        /** 定位补丁修改片段的上下文提示。 */
        final String hint;

        /** 行集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        final List<HunkLine> lines = new ArrayList<>();
    }

    /** 4A补丁引擎内部的修改片段行，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class HunkLine {
        /** 当前补丁行的操作前缀，区分原文、增加与删除。 */
        final char prefix;

        /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
        final String content;
    }

    /** 4A补丁引擎内部的匹配，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Match {
        /** 当前匹配片段在原内容中的起始位置。 */
        final int start;

        /** 当前匹配片段在原内容中的结束位置。 */
        final int end;
    }

    /** 的结果对象，供调用方判断实际处理结果。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Result {
        /** modified的有序集合，保留当前组件处理或协议输出所需的顺序。 */
        @Getter(AccessLevel.PACKAGE)
        final List<String> modified;
    }
}
