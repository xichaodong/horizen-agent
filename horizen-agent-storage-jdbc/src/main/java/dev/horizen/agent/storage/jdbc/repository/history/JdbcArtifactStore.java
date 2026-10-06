package dev.horizen.agent.storage.jdbc.repository.history;

import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactKind;
import dev.horizen.agent.domain.artifact.ArtifactReference;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactSource;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.ArtifactMapper;
import dev.horizen.agent.storage.jdbc.model.ArtifactRow;
import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;
import dev.horizen.agent.storage.jdbc.support.RowValues;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

/** JDBC Artifact 元数据存储；文件字节由外部内容存储 Provider 管理。 */
public class JdbcArtifactStore implements ArtifactStore {
    /** 产物映射器的固定取值，用于相应策略和边界判断。 */
    private static final Function<ArtifactRow, Artifact> ARTIFACT_MAPPER =
            rs ->
                    new Artifact(
                            rs.getArtifactId(),
                            rs.getOwnerKey(),
                            ArtifactKind.valueOf(rs.getKind()),
                            ArtifactState.valueOf(rs.getStatus()),
                            rs.getTitle(),
                            rs.getMediaType(),
                            rs.getContentRef(),
                            rs.getSizeBytes(),
                            rs.getChecksumSha256(),
                            rs.getParentArtifactId(),
                            ArtifactSource.valueOf(rs.getSource()),
                            rs.getSourceRef(),
                            RowValues.nullableInstant(rs.getExpiresAt()),
                            RowValues.instant(rs.getCreatedAt()),
                            RowValues.instant(rs.getUpdatedAt()),
                            RowValues.nullableInstant(rs.getDeletedAt()),
                            rs.getVersion());

    /** 引用映射器的固定取值，用于相应策略和边界判断。 */
    private static final Function<ConversationHistoryRow, ArtifactReference> REFERENCE_MAPPER =
            rs -> {
                var payload = JdbcHistoryJson.object(rs.getPayloadJson());
                var call = payload.get("toolCallId");
                return new ArtifactReference(
                        rs.getRecordId(),
                        rs.getOwnerKey(),
                        rs.getArtifactId(),
                        rs.getSessionId(),
                        rs.getTurnId(),
                        ArtifactReferenceRole.valueOf(rs.getArtifactRole()),
                        call == null || call.isNull() ? null : call.asText(),
                        RowValues.instant(rs.getCreatedAt()));
            };

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final ArtifactMapper mapper;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /**
     * 创建JDBC产物存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcArtifactStore(DataSource dataSource) {
        this.mapper = MyBatisSessions.create(dataSource).getMapper(ArtifactMapper.class);
        this.transactions = new JdbcUnitOfWork(dataSource);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcArtifactStore(ArtifactMapper mapper, UnitOfWork transactions) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /**
     * 创建JDBC产物存储。
     *
     * @param artifact 当前JDBC产物存储持有的产物对象，供相应处理步骤使用。
     * @return 本次操作返回的产物结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public Artifact create(Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (artifact.getVersion() != 0) {
            throw new IllegalArgumentException("new Artifact version must be zero");
        }
        mapper.insertArtifact(
                artifact.getOwnerKey(),
                artifact.getArtifactId(),
                artifact.getKind().name(),
                artifact.getState().name(),
                artifact.getTitle(),
                artifact.getMediaType(),
                artifact.getContentRef(),
                artifact.getSizeBytes(),
                artifact.getChecksumSha256(),
                artifact.getParentArtifactId(),
                artifact.getSource().name(),
                artifact.getSourceRef(),
                timestamp(artifact.getExpiresAt()),
                timestamp(artifact.getCreatedAt()),
                timestamp(artifact.getUpdatedAt()),
                timestamp(artifact.getDeletedAt()));
        return find(artifact.getOwnerKey(), artifact.getArtifactId()).orElseThrow();
    }

    /**
     * 查找JDBC产物存储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<Artifact> find(String ownerKey, String artifactId) {
        List<Artifact> values =
                mapper.selectFind(ownerKey, artifactId).stream().map(ARTIFACT_MAPPER).toList();
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 更新JDBC产物存储。
     *
     * @param artifact 当前JDBC产物存储持有的产物对象，供相应处理步骤使用。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次操作返回的产物结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public Artifact update(Artifact artifact, long expectedVersion) {
        Objects.requireNonNull(artifact, "artifact");
        Artifact current =
                find(artifact.getOwnerKey(), artifact.getArtifactId())
                        .orElseThrow(() -> new IllegalArgumentException("artifact not found"));
        if (current.getVersion() != expectedVersion) {
            throw new IllegalStateException("artifact version changed");
        }
        if (current.getState() != artifact.getState()
                && !current.getState().canTransitionTo(artifact.getState())) {
            throw new IllegalArgumentException("invalid artifact state transition");
        }
        int updated =
                mapper.updateArtifact(
                        artifact.getKind().name(),
                        artifact.getState().name(),
                        artifact.getTitle(),
                        artifact.getMediaType(),
                        artifact.getContentRef(),
                        artifact.getSizeBytes(),
                        artifact.getChecksumSha256(),
                        artifact.getParentArtifactId(),
                        artifact.getSource().name(),
                        artifact.getSourceRef(),
                        timestamp(artifact.getExpiresAt()),
                        timestamp(artifact.getUpdatedAt()),
                        timestamp(artifact.getDeletedAt()),
                        artifact.getOwnerKey(),
                        artifact.getArtifactId(),
                        expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException("artifact version changed");
        }
        return find(artifact.getOwnerKey(), artifact.getArtifactId()).orElseThrow();
    }

    /**
     * 增加引用。
     *
     * @param reference 当前JDBC产物存储持有的引用对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public void addReference(ArtifactReference reference) {
        Objects.requireNonNull(reference, "reference");
        transactions.executeWithoutResult(
                () -> {
                    if (find(reference.getOwnerKey(), reference.getArtifactId()).isEmpty()) {
                        throw new IllegalArgumentException("referenced artifact not found");
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("artifactId", reference.getArtifactId());
                    payload.put("role", reference.getRole().name());
                    payload.put("toolCallId", reference.getToolCallId());
                    try {
                        mapper.insertArtifactReference(
                                reference.getOwnerKey(),
                                reference.getSessionId(),
                                reference.getTurnId(),
                                reference.getReferenceId(),
                                reference.getArtifactId(),
                                reference.getRole().name(),
                                JdbcHistoryJson.encode(payload),
                                timestamp(reference.getCreatedAt()),
                                timestamp(reference.getCreatedAt()));
                    } catch (DuplicateKeyException ignored) {
                        var existing =
                                mapper
                                        .selectArtifactReference(
                                                reference.getOwnerKey(), reference.getReferenceId())
                                        .stream()
                                        .map(REFERENCE_MAPPER)
                                        .toList();
                        if (existing.size() != 1 || !sameReference(existing.get(0), reference)) {
                            throw new IllegalStateException("artifact reference identity changed");
                        }
                    }
                    if (reference.getRole() == ArtifactReferenceRole.INPUT)
                        attachToUserMessage(reference);
                });
    }

    /**
     * 关联目标用户消息。
     *
     * @param reference 当前JDBC产物存储持有的引用对象，供相应处理步骤使用。
     */
    private void attachToUserMessage(ArtifactReference reference) {
        // 并发 load_artifact 调用仅串行执行此小型元数据更新，事务中不执行远程 I/O。
        var messages =
                mapper.selectUserMessagesForTurn(
                        reference.getOwnerKey(), reference.getSessionId(), reference.getTurnId());
        for (var row : messages) {
            var payload = JdbcHistoryJson.object((String) row.get("payload_json"));
            if (!"USER".equals(payload.path("role").asText())) continue;
            // 锁定具体主键行，避免锁定仍可能插入引用记录的二级索引范围。
            String locked =
                    mapper.selectMessagePayloadForUpdate(
                            row.get("history_sequence"), reference.getOwnerKey());
            payload = JdbcHistoryJson.object(locked);
            var ids = payload.withArray("artifactIds");
            boolean exists = false;
            for (var id : ids) if (reference.getArtifactId().equals(id.asText())) exists = true;
            if (exists) continue;
            ids.add(reference.getArtifactId());
            mapper.updateMessagePayload(
                    JdbcHistoryJson.encode(payload), reference.getOwnerKey(), row.get("record_id"));
        }
    }

    /**
     * 检查sameReference对应的条件，供调用方选择后续处理分支。
     *
     * @param left 当前JDBC产物存储持有的left对象，供相应处理步骤使用。
     * @param right 当前JDBC产物存储持有的right对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean sameReference(ArtifactReference left, ArtifactReference right) {
        return left.getArtifactId().equals(right.getArtifactId())
                && left.getSessionId().equals(right.getSessionId())
                && left.getTurnId().equals(right.getTurnId())
                && left.getRole() == right.getRole()
                && Objects.equals(left.getToolCallId(), right.getToolCallId());
    }

    /**
     * 查询列表中的引用集合。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<ArtifactReference> listReferences(String ownerKey, String artifactId) {
        return mapper.selectListReferences(ownerKey, artifactId).stream()
                .map(REFERENCE_MAPPER)
                .toList();
    }

    /**
     * 查询列表中的引用集合目标范围会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<ArtifactReference> listReferencesForSession(String ownerKey, String sessionId) {
        return mapper.selectListReferencesForSession(ownerKey, sessionId).stream()
                .map(REFERENCE_MAPPER)
                .toList();
    }

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<Artifact> listForSession(String ownerKey, String sessionId, int limit) {
        return listReady(ownerKey, sessionId, limit, false);
    }

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<Artifact> listForSession(String ownerKey, String sessionId, int limit, int offset) {
        return listReady(ownerKey, sessionId, limit, false, offset);
    }

    /**
     * 查询列表中的RecentOutputs。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<Artifact> listRecentOutputs(String ownerKey, String sessionId, int limit) {
        return listReady(ownerKey, sessionId, limit, true);
    }

    /**
     * 查询列表中的就绪。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param limit 本次处理或返回数量上限。
     * @param outputsOnly outputs只读的状态标记，用于选择当前组件的处理路径。
     * @return 本次处理得到的结果集合。
     */
    private List<Artifact> listReady(
            String ownerKey, String sessionId, int limit, boolean outputsOnly) {
        return listReady(ownerKey, sessionId, limit, outputsOnly, 0);
    }

    /**
     * 查询列表中的就绪。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param limit 本次处理或返回数量上限。
     * @param outputsOnly outputs只读的状态标记，用于选择当前组件的处理路径。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private List<Artifact> listReady(
            String ownerKey, String sessionId, int limit, boolean outputsOnly, int offset) {
        if (limit <= 0) return List.of();
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        // 先按标识分组再关联元数据，避免同一文件的多次使用占用结果数量上限。
        return mapper
                .selectReady(
                        ownerKey, sessionId, ArtifactState.READY.name(), limit, offset, outputsOnly)
                .stream()
                .map(ARTIFACT_MAPPER)
                .toList();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcArtifactStore处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
