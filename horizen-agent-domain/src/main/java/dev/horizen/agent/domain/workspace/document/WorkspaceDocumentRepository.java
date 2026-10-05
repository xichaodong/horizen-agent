package dev.horizen.agent.domain.workspace.document;

import java.util.List;
import java.util.Optional;

/** 支持版本管理的工作区小文档接口。 */
public interface WorkspaceDocumentRepository {
    /**
     * 查找工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<WorkspaceDocument> find(WorkspaceDocumentKey key);

    /**
     * 查询列表中的工作区文档仓储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param scopeKey 当前工作区文档仓储使用的作用域键，供其处理与状态记录使用。
     * @param pathPrefix 路径使用的公共前缀，用于分组或存储寻址。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    List<WorkspaceDocument> list(
            String ownerKey,
            String agentKey,
            String scopeKey,
            String pathPrefix,
            int limit,
            int offset);

    /**
     * 创建条件Absent。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean createIfAbsent(WorkspaceDocumentKey key, String content);

    /**
     * 替换工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean replace(WorkspaceDocumentKey key, String content, long expectedVersion);

    /**
     * 追加工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次操作返回的工作区文档追加结果结果。
     */
    WorkspaceDocumentAppendResult append(
            WorkspaceDocumentKey key, String operationId, String content);

    /** 在同一作用域内原子提交 CAS 替换及其审计和追加记录。 */
    WorkspaceDocumentCommitResult commit(WorkspaceDocumentCommit commit);

    /**
     * 判断是否存在追加操作。
     *
     * @param key 当前对象的查找或写入键。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean hasAppendOperation(WorkspaceDocumentKey key, String operationId);

    /**
     * 删除工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean delete(WorkspaceDocumentKey key, long expectedVersion);
}
