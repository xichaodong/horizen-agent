package dev.horizen.agent.application.workspace;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocument;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppend;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppendResult;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommit;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommitResult;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentReplace;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 按所有者隔离的长期记忆用例，以云端工作区文档为存储基础。 */
public final class CloudMemoryService {
    /** GLOBAL作用域使用的固定标识或协议文本。 */
    public static final String GLOBAL_SCOPE = "global";

    /** 记忆路径使用的固定标识或协议文本。 */
    public static final String MEMORY_PATH = "MEMORY.md";

    /** CAS重试的固定取值，用于相应策略和边界判断。 */
    private static final int CAS_RETRIES = 3;

    /** 按归属和作用域访问工作区文档的仓储。 */
    private final WorkspaceDocumentRepository documents;

    /** 时间来源，用于计算更新时间、过期时间或执行时限。 */
    private final Clock clock;

    /**
     * 创建云端记忆服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param documents 提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     */
    public CloudMemoryService(WorkspaceDocumentRepository documents) {
        this(documents, Clock.systemDefaultZone());
    }

    /**
     * 创建云端记忆服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param documents 提供文档集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param clock 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    public CloudMemoryService(WorkspaceDocumentRepository documents, Clock clock) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 当前有效记忆；结构化操作行用于保存审计历史。 */
    public Optional<WorkspaceDocument> current(String ownerKey, String agentKey) {
        return documents.find(key(ownerKey, agentKey, MEMORY_PATH));
    }

    /**
     * 在 Agent 与数据归属范围内追加结构化记忆，使用操作标识保证重复提交可识别。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次操作返回的工作区文档追加结果结果。
     */
    public WorkspaceDocumentAppendResult save(
            String ownerKey, String agentKey, String operationId, String content) {
        return save(ownerKey, agentKey, operationId, content, null, null, null);
    }

    /**
     * 在 Agent 与数据归属范围内追加结构化记忆，使用操作标识保证重复提交可识别。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次操作返回的工作区文档追加结果结果。
     */
    public WorkspaceDocumentAppendResult save(
            String ownerKey,
            String agentKey,
            String operationId,
            String content,
            String sessionId,
            String turnId,
            String toolCallId) {
        String normalized = requireContent(content);
        String baseOperationId = requireOperationId(operationId);
        WorkspaceDocumentKey memory = key(ownerKey, agentKey, MEMORY_PATH);
        if (documents.hasAppendOperation(memory, baseOperationId + ":memory")) {
            return new WorkspaceDocumentAppendResult(documents.find(memory).orElseThrow(), false);
        }
        Instant now = clock.instant();
        WorkspaceDocumentCommitResult result =
                documents.commit(
                        new WorkspaceDocumentCommit(
                                null,
                                List.of(
                                        new WorkspaceDocumentAppend(
                                                memory,
                                                baseOperationId + ":memory",
                                                "\n" + normalized.strip() + "\n",
                                                sessionId,
                                                turnId,
                                                toolCallId))));
        return new WorkspaceDocumentAppendResult(findDocument(result, memory), result.isApplied());
    }

    /**
     * 以精确旧内容定位待修改记忆行，避免模糊匹配误改其他内容。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param current 当前云端记忆服务使用的当前，供其处理与状态记录使用。
     * @param replacement 当前云端记忆服务使用的替换，供其处理与状态记录使用。
     */
    public void replaceExactLine(
            String ownerKey,
            String agentKey,
            String operationId,
            String current,
            String replacement) {
        replaceExactLine(ownerKey, agentKey, operationId, current, replacement, null, null, null);
    }

    /**
     * 以精确旧内容定位待修改记忆行，避免模糊匹配误改其他内容。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @param current 当前云端记忆服务使用的当前，供其处理与状态记录使用。
     * @param replacement 当前云端记忆服务使用的替换，供其处理与状态记录使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void replaceExactLine(
            String ownerKey,
            String agentKey,
            String operationId,
            String current,
            String replacement,
            String sessionId,
            String turnId,
            String toolCallId) {
        String expectedLine = requireContent(current).strip();
        String baseOperationId = requireOperationId(operationId);
        String replacementLine = replacement == null ? "" : replacement.strip();
        WorkspaceDocumentKey memory = key(ownerKey, agentKey, MEMORY_PATH);
        if (documents.hasAppendOperation(memory, baseOperationId + ":replace")) return;
        for (int attempt = 0; attempt < CAS_RETRIES; attempt++) {
            if (documents.hasAppendOperation(memory, baseOperationId + ":replace")) return;
            WorkspaceDocument document =
                    documents
                            .find(memory)
                            .orElseThrow(
                                    () ->
                                            new ApplicationError(
                                                    ApplicationError.Code.NOT_FOUND,
                                                    "MEMORY.md is empty"));
            long matches =
                    document.getContent()
                            .lines()
                            .filter(line -> line.strip().equals(expectedLine))
                            .count();
            if (matches != 1) {
                if (documents.hasAppendOperation(memory, baseOperationId + ":replace")) return;
                throw new ApplicationError(
                        ApplicationError.Code.CONFLICT,
                        "memory_manage requires exactly one matching line; found " + matches);
            }
            String updated = replaceLine(document.getContent(), expectedLine, replacementLine);
            Instant now = clock.instant();
            WorkspaceDocumentCommitResult result =
                    documents.commit(
                            new WorkspaceDocumentCommit(
                                    new WorkspaceDocumentReplace(
                                            memory, updated, document.getVersion()),
                                    List.of(
                                            new WorkspaceDocumentAppend(
                                                    memory,
                                                    baseOperationId + ":replace",
                                                    "",
                                                    sessionId,
                                                    turnId,
                                                    toolCallId))));
            if (result.isApplied()
                    || documents.hasAppendOperation(memory, baseOperationId + ":replace")) return;
        }
        throw new ApplicationError(
                ApplicationError.Code.CONFLICT,
                "MEMORY.md changed concurrently; retry the operation");
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的工作区文档键结果。
     */
    private static WorkspaceDocumentKey key(String ownerKey, String agentKey, String path) {
        return new WorkspaceDocumentKey(ownerKey, agentKey, GLOBAL_SCOPE, path);
    }

    /**
     * 取得当前归属与 Agent 范围内的持久记忆文档。
     *
     * @param result 本次处理已有的结果。
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的工作区文档结果。
     */
    private static WorkspaceDocument findDocument(
            WorkspaceDocumentCommitResult result, WorkspaceDocumentKey key) {
        return result.getDocuments().stream()
                .filter(value -> value.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("memory commit lost document"));
    }

    /**
     * 校验结构化记忆修改的幂等操作标识。
     *
     * @param operationId 单次写入操作的标识，用于幂等提交和操作追踪。
     * @return 本次处理生成或读取的文本。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 180) {
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "invalid memory operation id");
        }
        return operationId;
    }

    /**
     * 校验允许写入的记忆正文及其容量。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireContent(String value) {
        if (value == null || value.isBlank()) {
            throw new ApplicationError(
                    ApplicationError.Code.INVALID_ARGUMENT, "content is required");
        }
        return value;
    }

    /**
     * 根据原正文与精确匹配条件生成替换后的文档内容。
     *
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param current 当前云端记忆服务使用的当前，供其处理与状态记录使用。
     * @param replacement 当前云端记忆服务使用的替换，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String replaceLine(String content, String current, String replacement) {
        StringBuilder result = new StringBuilder();
        content.lines()
                .forEach(
                        line -> {
                            if (!line.strip().equals(current)) result.append(line).append('\n');
                            else if (!replacement.isBlank())
                                result.append(replacement).append('\n');
                        });
        return result.toString();
    }
}
