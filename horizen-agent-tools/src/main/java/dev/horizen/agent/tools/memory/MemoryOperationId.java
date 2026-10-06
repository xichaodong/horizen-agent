package dev.horizen.agent.tools.memory;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.tool.adapter.ToolInvocationScope;

import io.agentscope.core.tool.ToolCallParam;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 模型调用 ID 仅在单次执行内唯一，不在所有者的全部记忆中全局唯一。
 */
final class MemoryOperationId {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private MemoryOperationId() {
    }

    /**
     * 生成当前操作所需的of文本，供调用方继续处理。
     *
     * @param param    当前记忆操作标识持有的参数对象，供相应处理步骤使用。
     * @param owner    当前记忆操作标识使用的数据归属，供其处理与状态记录使用。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param toolName 可调用工具的注册名称，须与目录中声明的名称一致。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException    当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    static String of(ToolCallParam param, String owner, String agentKey, String toolName) {
        var context = param.getRuntimeContext();
        var scope = context == null ? null : context.get(ToolInvocationScope.class);
        if (scope == null
                || !owner.equals(scope.getOwnerKey())
                || param.getToolUseBlock() == null) {
            throw new IllegalArgumentException("trusted memory invocation scope is required");
        }
        // 子调用可以使用不同的会话 ID，同时继承根 Turn 的作用域。保留现有哈希格式，
        // 避免移除通用工具日志后改变已提交记忆操作的幂等键。
        try {
            var digest = DigestUtils.newSha256();
            for (String part :
                    new String[]{
                            owner,
                            context.getSessionId(),
                            scope.getTurnId(),
                            param.getToolUseBlock().getId(),
                            agentKey + ":" + toolName
                    }) {
                if (part == null
                        || part.isBlank()
                        || part.length() > 256
                        || part.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Invalid memory operation coordinates");
                }
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
