package dev.horizen.agent.adapter.agentscope.artifact;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.regex.Pattern;

/** 将产物保存到按 owner 和 session 隔离的本地目录，适合单机宿主和开发环境。 */
public final class OwnerScopedLocalArtifactTarget implements ArtifactDeliveryTarget {
    /** 校验安全键的模式，限定允许接受的输入形式。 */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    /** 本组件使用的根路径或根对象，限定后续读取与定位范围。 */
    private final Path root;

    /** 最大产物的字节数，用于容量或传输限制。 */
    private final long maxArtifactBytes;

    /**
     * 创建数据归属隔离范围内本地产物目标，初始化该组件所需的状态、配置或依赖。
     *
     * @param root 当前操作允许使用的根路径。
     * @param maxArtifactBytes 最大产物的字节数，用于容量或传输限制。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public OwnerScopedLocalArtifactTarget(Path root, long maxArtifactBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxArtifactBytes <= 0) {
            throw new IllegalArgumentException("maxArtifactBytes 必须为正数");
        }
        this.maxArtifactBytes = maxArtifactBytes;
    }

    /**
     * 在归属限定的本地开发存储中登记并写入产物，返回可引用的资源描述。
     *
     * @param runtimeContext 当前数据归属隔离范围内本地产物目标持有的运行时上下文对象，供相应处理步骤使用。
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物交付结果结果。
     */
    @Override
    public ArtifactDeliveryResult deliver(
            RuntimeContext runtimeContext, ArtifactDeliveryRequest request) {
        if (runtimeContext == null || request == null) {
            return ArtifactDeliveryResult.fail("缺少运行上下文或产物请求");
        }
        String ownerKey = runtimeContext.getUserId();
        String sessionId = runtimeContext.getSessionId();
        if (!safe(ownerKey) || !safe(sessionId)) {
            return ArtifactDeliveryResult.fail("缺少安全的 owner/session 隔离键");
        }
        if (!plainFileName(request.fileName())) {
            return ArtifactDeliveryResult.fail("产物文件名不安全");
        }
        byte[] content = request.content();
        if (content == null) {
            return ArtifactDeliveryResult.fail("产物内容为空");
        }
        if (content.length > maxArtifactBytes) {
            return ArtifactDeliveryResult.fail("产物超过大小限制");
        }

        Path directory = root.resolve(ownerKey).resolve(sessionId).normalize();
        Path destination = directory.resolve(request.fileName()).normalize();
        if (!directory.startsWith(root) || !destination.startsWith(directory)) {
            return ArtifactDeliveryResult.fail("产物路径越界");
        }
        try {
            Files.createDirectories(directory);
            if (!request.force()) {
                try {
                    Files.write(
                            destination,
                            content,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException conflict) {
                    return ArtifactDeliveryResult.conflict("同名产物已存在");
                }
            } else {
                replaceAtomically(directory, destination, content);
            }
            return ArtifactDeliveryResult.success("stored as " + request.fileName());
        } catch (IOException error) {
            return ArtifactDeliveryResult.fail("写入产物失败：" + error.getClass().getSimpleName());
        }
    }

    /**
     * 在原归属范围内解析已登记产物的本地内容位置。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param fileName 文件名称，用于内容识别与交付展示。
     * @return 本次操作返回的路径结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Path resolve(String ownerKey, String sessionId, String fileName) {
        if (!safe(ownerKey) || !safe(sessionId) || !plainFileName(fileName)) {
            throw new IllegalArgumentException("产物定位参数不安全");
        }
        Path result = root.resolve(ownerKey).resolve(sessionId).resolve(fileName).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("产物路径越界");
        }
        return result;
    }

    /**
     * 用准备好的内容替换目标文件，避免读取到部分写入的结果。
     *
     * @param directory 当前资源目录，供内容准备、读取与清理使用。
     * @param destination 当前数据归属隔离范围内本地产物目标持有的destination对象，供相应处理步骤使用。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     */
    private static void replaceAtomically(Path directory, Path destination, byte[] content)
            throws IOException {
        Path temporary = Files.createTempFile(directory, ".artifact-", ".tmp");
        try {
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 检查safe对应的条件，供调用方选择后续处理分支。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean safe(String value) {
        return value != null && SAFE_KEY.matcher(value).matches();
    }

    /**
     * 从交付名称中取得不包含目录穿越的普通文件名。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean plainFileName(String value) {
        return value != null
                && !value.isBlank()
                && !value.equals(".")
                && !value.equals("..")
                && !value.contains("/")
                && !value.contains("\\")
                && value.indexOf('\0') < 0;
    }
}
