package dev.horizen.agent.domain.workspace.release;

import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;

import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 只读审计访问；历史对象引用属于特定文件身份。 */
public interface WorkspaceAuditRepository {
    /**
     * 查询列表中的工作区审计仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param allFiles 全部文件集合的状态标记，用于选择当前组件的处理路径。
     * @param beforeSequence 当前工作区审计仓储使用的处理前序号，供其处理与状态记录使用。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<Operation> list(
            WorkspaceDocumentKey key, boolean allFiles, long beforeSequence, int limit);

    /**
     * 查找工作区审计仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<Operation> find(WorkspaceDocumentKey key, long sequence);

    /**
     * 检查ownsMemory对应的条件，供调用方选择后续处理分支。
     *
     * @param project 当前工作区审计仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param owner 当前工作区审计仓储使用的数据归属，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean ownsMemory(long project, String agent, String owner);

    /** 一条工作区修改操作的审计事实，供查询操作来源与结果。 */
    @Value
    class Operation {
        /** 当前记录在对应序列中的位置，用于排序或继续读取。 */
        long sequence;

        /** 文件在受管工作区内的相对路径。 */
        String filePath;

        /** 单次写入操作的标识，用于幂等提交和操作追踪。 */
        String operationId;

        /** 工作区修改的操作类别，供审计和写入策略解释。 */
        String operationType;

        /** 发起当前工作区修改的操作方类别。 */
        String actorType;

        /** 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。 */
        String actorId;

        /** 处理前的版本，供兼容或并发检查使用。 */
        Long beforeVersion;

        /** 处理后的版本，供兼容或并发检查使用。 */
        Long afterVersion;

        /** 操作之前的持久内容引用，用于回看原内容。 */
        String beforeReference;

        /** 操作之后的持久内容引用，用于回看提交结果。 */
        String afterReference;

        /** 操作前内容的校验摘要，供审计与一致性比较使用。 */
        String beforeChecksum;

        /** 操作后内容的校验摘要，供审计与一致性比较使用。 */
        String afterChecksum;

        /** 操作前内容的字节数。 */
        Long beforeSize;

        /** 操作后内容的字节数。 */
        Long afterSize;

        /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
        String mediaType;

        /** 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。 */
        String sessionId;

        /** 单次用户输入触发的执行标识，用于关联状态、消息和事件。 */
        String turnId;

        /** 一次工具调用的标识，用于配对参数、结果和审批事件。 */
        String toolCallId;

        /** 当前记录的创建时间。 */
        Instant createdAt;
    }
}
