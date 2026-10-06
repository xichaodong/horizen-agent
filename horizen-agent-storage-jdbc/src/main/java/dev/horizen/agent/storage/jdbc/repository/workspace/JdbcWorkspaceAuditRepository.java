package dev.horizen.agent.storage.jdbc.repository.workspace;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.release.WorkspaceAuditRepository;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceAuditMapper;
import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;

import java.util.*;
import java.util.Objects;
import java.util.function.Function;

import javax.sql.DataSource;

/** 工作区审计记录的 JDBC 适配器，保存操作事实并提供分页查询。 */
public class JdbcWorkspaceAuditRepository implements WorkspaceAuditRepository {
    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final WorkspaceAuditMapper mapper;

    /**
     * 创建JDBC工作区审计仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     */
    public JdbcWorkspaceAuditRepository(DataSource source) {
        this.mapper = MyBatisSessions.create(source).getMapper(WorkspaceAuditMapper.class);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcWorkspaceAuditRepository(WorkspaceAuditMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 查询列表中的JDBC工作区审计仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param allFiles 全部文件集合的状态标记，用于选择当前组件的处理路径。
     * @param before 当前JDBC工作区审计仓储使用的处理前，供其处理与状态记录使用。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public List<Operation> list(
            WorkspaceDocumentKey key, boolean allFiles, long before, int limit) {
        if (limit < 1 || limit > 101) throw new IllegalArgumentException("Invalid audit page size");
        if (allFiles)
            return mapper
                    .selectScopeOperations(
                            key.getOwnerKey(),
                            key.getAgentKey(),
                            key.getScopeKey(),
                            before <= 0 ? Long.MAX_VALUE : before,
                            limit)
                    .stream()
                    .map(MAPPER)
                    .toList();
        return mapper
                .selectPathOperations(
                        key.getOwnerKey(),
                        key.getAgentKey(),
                        key.getScopeKey(),
                        hash(key.getDocumentPath()),
                        before <= 0 ? Long.MAX_VALUE : before,
                        limit)
                .stream()
                .map(MAPPER)
                .toList();
    }

    /**
     * 查找JDBC工作区审计仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param sequence 当前记录在对应序列中的位置，用于排序或继续读取。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<Operation> find(WorkspaceDocumentKey key, long sequence) {
        return mapper
                .selectFind(
                        key.getOwnerKey(),
                        key.getAgentKey(),
                        key.getScopeKey(),
                        hash(key.getDocumentPath()),
                        sequence)
                .stream()
                .map(MAPPER)
                .toList()
                .stream()
                .findFirst();
    }

    /**
     * 检查ownsMemory对应的条件，供调用方选择后续处理分支。
     *
     * @param project 当前JDBC工作区审计仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param owner 当前JDBC工作区审计仓储使用的数据归属，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean ownsMemory(long project, String agent, String owner) {
        return !mapper.selectOwnsMemory(owner, project, agent).isEmpty();
    }

    /** 映射器的固定取值，用于相应策略和边界判断。 */
    private static final Function<WorkspaceOperationRow, Operation> MAPPER =
            r ->
                    new Operation(
                            r.getAuditSequence(),
                            r.getFilePath(),
                            r.getOperationId(),
                            r.getOperationType(),
                            r.getActorType(),
                            r.getActorId(),
                            r.getBeforeVersion(),
                            r.getAfterVersion(),
                            r.getBeforeRef(),
                            r.getAfterRef(),
                            r.getBeforeChecksum(),
                            r.getAfterChecksum(),
                            r.getBeforeSize(),
                            r.getAfterSize(),
                            r.getMediaType(),
                            r.getSessionId(),
                            r.getTurnId(),
                            r.getToolCallId(),
                            r.getCreatedAt().toInstant());

    /**
     * 计算摘要JDBC工作区审计仓储。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String hash(String value) {
        return DigestUtils.sha256Hex(value);
    }
}
