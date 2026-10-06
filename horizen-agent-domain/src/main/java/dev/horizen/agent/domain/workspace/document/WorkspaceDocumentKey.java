package dev.horizen.agent.domain.workspace.document;

import lombok.Data;

/**
 * 云端持久化工作区小文档的唯一身份。
 */
@Data
public final class WorkspaceDocumentKey {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    private final String agentKey;

    /**
     * 工作区内的作用域键，用于把会话任务文件与 Agent 共享文档分开定位。
     */
    private final String scopeKey;

    /**
     * 文档在当前作用域内的相对路径。
     */
    private final String documentPath;

    /**
     * 创建工作区文档键，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey     宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey     宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey     当前工作区文档键使用的作用域键，供其处理与状态记录使用。
     * @param documentPath 当前工作区文档键使用的文档路径，供其处理与状态记录使用。
     */
    public WorkspaceDocumentKey(
            String ownerKey, String agentKey, String scopeKey, String documentPath) {
        this.ownerKey = requireIdentifier(ownerKey, "ownerKey", 191);
        this.agentKey = requireIdentifier(agentKey, "agentKey", 128);
        this.scopeKey = requireIdentifier(scopeKey, "scopeKey", 160);
        this.documentPath = normalizePath(documentPath);
    }

    /**
     * 取得并校验Identifier。
     *
     * @param value     待校验、转换或保存的原始值。
     * @param field     当前工作区文档键使用的字段，供其处理与状态记录使用。
     * @param maxLength 当前工作区文档键使用的最大长度，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    /**
     * 规范化路径。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid documentPath");
        }
        String normalized = path.replace('\\', '/').strip();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty() || normalized.length() > 512) {
            throw new IllegalArgumentException("Invalid documentPath");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid documentPath");
            }
        }
        return normalized;
    }
}
