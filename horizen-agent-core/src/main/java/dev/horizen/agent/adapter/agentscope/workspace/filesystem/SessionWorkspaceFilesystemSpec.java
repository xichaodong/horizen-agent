package dev.horizen.agent.adapter.agentscope.workspace.filesystem;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

import java.nio.file.Path;

/**
 * 使用受支持的文件系统配置入口，提供每次调用独立的云端执行视图。
 */
public final class SessionWorkspaceFilesystemSpec extends RemoteFilesystemSpec {
    /**
     * 转换为文件系统。
     *
     * @param root       当前操作允许使用的根路径。
     * @param agent      当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param namespaces 提供namespaces能力的依赖，具体实现由当前组件的组装方传入。
     * @return 本次操作返回的Abstract文件系统结果。
     */
    @Override
    public AbstractFilesystem toFilesystem(Path root, String agent, NamespaceFactory namespaces) {
        return new SessionWorkspaceFilesystem();
    }
}
