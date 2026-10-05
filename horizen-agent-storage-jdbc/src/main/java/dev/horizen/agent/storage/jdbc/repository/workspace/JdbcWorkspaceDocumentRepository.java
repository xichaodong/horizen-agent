package dev.horizen.agent.storage.jdbc.repository.workspace;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocument;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppend;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentAppendResult;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommit;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentCommitResult;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceTextDiff;
import dev.horizen.agent.domain.workspace.policy.WorkspaceFilePolicy;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceDocumentMapper;
import dev.horizen.agent.storage.jdbc.model.WorkspaceFileRow;
import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * 组合云端文件元数据与不可变对象内容；网络 I/O 不在 SQL 事务中执行。
 */
public class JdbcWorkspaceDocumentRepository implements WorkspaceDocumentRepository {
    /** 默认最大文档字节的固定取值，用于相应策略和边界判断。 */
    public static final long DEFAULT_MAX_DOCUMENT_BYTES = 1024L * 1024L;

    /** 重试的固定取值，用于相应策略和边界判断。 */
    private static final int RETRIES = 4;

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final WorkspaceDocumentMapper mapper;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /** 保存工作区实际内容字节的对象存储访问端口。 */
    private final WorkspaceObjectContent objects;

    /**
     * 创建JDBC工作区文档仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public JdbcWorkspaceDocumentRepository(DataSource source, WorkspaceContentRepository contents) {
        this(source, contents, DEFAULT_MAX_DOCUMENT_BYTES);
    }

    /**
     * 创建JDBC工作区文档仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public JdbcWorkspaceDocumentRepository(
            DataSource source, WorkspaceContentRepository contents, long maximumBytes) {
        this.mapper = MyBatisSessions.create(source).getMapper(WorkspaceDocumentMapper.class);
        transactions = new JdbcUnitOfWork(source);
        this.objects = new WorkspaceObjectContent(Objects.requireNonNull(contents), maximumBytes);
        if (maximumBytes <= 0) throw new IllegalArgumentException("Invalid file limit");
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcWorkspaceDocumentRepository(
            WorkspaceDocumentMapper mapper,
            UnitOfWork transactions,
            WorkspaceContentRepository contents,
            long maximumBytes) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.objects = new WorkspaceObjectContent(Objects.requireNonNull(contents), maximumBytes);
        if (maximumBytes <= 0) throw new IllegalArgumentException("Invalid file limit");
    }

    /**
     * 查找JDBC工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<WorkspaceDocument> find(WorkspaceDocumentKey key) {
        return metadata(key).map(this::read);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceDocumentRepository处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private Optional<Metadata> metadata(WorkspaceDocumentKey key) {
        var rows =
                mapper
                        .selectMetadata(
                                key.getOwnerKey(),
                                key.getAgentKey(),
                                key.getScopeKey(),
                                hash(key.getDocumentPath().getBytes(StandardCharsets.UTF_8)))
                        .stream()
                        .map(
                                rs ->
                                        new Metadata(
                                                new WorkspaceDocumentKey(
                                                        rs.getOwnerKey(),
                                                        rs.getAgentKey(),
                                                        rs.getScopeKey(),
                                                        rs.getFilePath()),
                                                rs.getContentRef(),
                                                rs.getChecksumSha256(),
                                                rs.getSizeBytes(),
                                                rs.getVersion(),
                                                rs.getCreatedAt().toInstant(),
                                                rs.getUpdatedAt().toInstant()))
                        .toList();
        return rows.stream().findFirst();
    }

    /**
     * 读取JDBC工作区文档仓储。
     *
     * @param m 当前JDBC工作区文档仓储持有的m对象，供相应处理步骤使用。
     * @return 本次操作返回的工作区文档结果。
     */
    private WorkspaceDocument read(Metadata m) {
        String content = objects.read(m.reference, m.checksum, m.bytes);
        return new WorkspaceDocument(m.key, content, m.bytes, m.version, m.created, m.updated);
    }

    /**
     * 查询列表中的JDBC工作区文档仓储。
     *
     * @param owner 当前JDBC工作区文档仓储使用的数据归属，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param scope 当前JDBC工作区文档仓储使用的作用域，供其处理与状态记录使用。
     * @param prefix 当前JDBC工作区文档仓储使用的前缀，供其处理与状态记录使用。
     * @param limit 本次处理或返回数量上限。
     * @param offset 本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public List<WorkspaceDocument> list(
            String owner, String agent, String scope, String prefix, int limit, int offset) {
        if (limit <= 0 || offset < 0) return List.of();
        var key = new WorkspaceDocumentKey(owner, agent, scope, "placeholder");
        String normalized = prefix == null ? "" : prefix.replace('\\', '/');
        if (normalized.contains("..") || normalized.indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid prefix");
        return mapper.selectList(owner, agent, scope, normalized + "%", limit, offset).stream()
                .map(
                        row ->
                                new Metadata(
                                        new WorkspaceDocumentKey(
                                                row.getOwnerKey(),
                                                row.getAgentKey(),
                                                row.getScopeKey(),
                                                row.getFilePath()),
                                        row.getContentRef(),
                                        row.getChecksumSha256(),
                                        row.getSizeBytes(),
                                        row.getVersion(),
                                        row.getCreatedAt().toInstant(),
                                        row.getUpdatedAt().toInstant()))
                .map(this::read)
                .toList();
    }

    /**
     * 创建条件Absent。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean createIfAbsent(WorkspaceDocumentKey key, String content) {
        requireAgentWritable(key);
        if (metadata(key).isPresent()) return false;
        Prepared prepared = prepare(key, content, null);
        try {
            return Boolean.TRUE.equals(
                    transactions.execute(
                            () -> {
                                insert(prepared);
                                log(
                                        key,
                                        "create-" + UUID.randomUUID(),
                                        "CREATE",
                                        Map.of("added", content),
                                        1,
                                        prepared.at,
                                        null,
                                        prepared,
                                        null,
                                        null,
                                        null);
                                return true;
                            }));
        } catch (DuplicateKeyException existing) {
            discard(prepared);
            return false;
        } catch (RuntimeException error) {
            discard(prepared);
            throw error;
        }
    }

    /**
     * 替换JDBC工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean replace(WorkspaceDocumentKey key, String content, long expectedVersion) {
        requireAgentWritable(key);
        Metadata previous = metadata(key).orElse(null);
        var current = previous == null ? null : read(previous);
        if (current == null || expectedVersion <= 0 || current.getVersion() != expectedVersion)
            return false;
        Prepared prepared = prepare(key, content, current);
        try {
            boolean changed =
                    Boolean.TRUE.equals(
                            transactions.execute(
                                    () -> {
                                        update(prepared);
                                        log(
                                                key,
                                                "replace-" + UUID.randomUUID(),
                                                "UPDATE",
                                                WorkspaceTextDiff.difference(
                                                        current.getContent(), content),
                                                current.getVersion() + 1,
                                                prepared.at,
                                                previous,
                                                prepared,
                                                null,
                                                null,
                                                null);
                                        return true;
                                    }));
            return changed;
        } catch (Conflict changed) {
            discard(prepared);
            return false;
        } catch (RuntimeException error) {
            discard(prepared);
            throw error;
        }
    }

    /**
     * 追加JDBC工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param operation 当前JDBC工作区文档仓储使用的操作，供其处理与状态记录使用。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @return 本次操作返回的工作区文档追加结果结果。
     */
    @Override
    public WorkspaceDocumentAppendResult append(
            WorkspaceDocumentKey key, String operation, String content) {
        var result =
                commit(
                        new WorkspaceDocumentCommit(
                                null,
                                List.of(new WorkspaceDocumentAppend(key, operation, content))));
        return new WorkspaceDocumentAppendResult(result.getDocuments().get(0), result.isApplied());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param commit 当前JDBC工作区文档仓储持有的提交对象，供相应处理步骤使用。
     * @return 本次操作返回的工作区文档提交结果结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public WorkspaceDocumentCommitResult commit(WorkspaceDocumentCommit commit) {
        Set<WorkspaceDocumentKey> keys = new LinkedHashSet<>();
        if (commit.getReplacement() != null) keys.add(commit.getReplacement().getKey());
        commit.getAppends().forEach(a -> keys.add(a.getKey()));
        keys.forEach(this::requireAgentWritable);
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            long recorded =
                    commit.getAppends().stream()
                            .filter(a -> hasAppendOperation(a.getKey(), a.getOperationId()))
                            .count();
            if (recorded == commit.getAppends().size())
                return new WorkspaceDocumentCommitResult(false, current(keys));
            if (recorded > 0)
                throw new IllegalStateException("Partially applied workspace operation");
            Map<WorkspaceDocumentKey, WorkspaceDocument> before = new LinkedHashMap<>();
            Map<WorkspaceDocumentKey, Metadata> previousMetadata = new LinkedHashMap<>();
            Map<WorkspaceDocumentKey, String> after = new LinkedHashMap<>();
            for (var key : keys) {
                var m = metadata(key).orElse(null);
                var doc = m == null ? null : read(m);
                before.put(key, doc);
                previousMetadata.put(key, m);
                after.put(key, doc == null ? "" : doc.getContent());
            }
            var replacement = commit.getReplacement();
            if (replacement != null) {
                var old = before.get(replacement.getKey());
                if (old == null || old.getVersion() != replacement.getExpectedVersion())
                    return new WorkspaceDocumentCommitResult(false, current(keys));
                after.put(replacement.getKey(), replacement.getContent());
            }
            for (var append : commit.getAppends())
                after.compute(append.getKey(), (k, value) -> value + append.getContent());
            List<Prepared> prepared = new ArrayList<>();
            try {
                for (var entry :
                        after.entrySet().stream()
                                .sorted(Comparator.comparing(e -> e.getKey().getDocumentPath()))
                                .toList())
                    prepared.add(
                            prepare(entry.getKey(), entry.getValue(), before.get(entry.getKey())));
            } catch (RuntimeException error) {
                prepared.forEach(this::discard);
                throw error;
            }
            try {
                transactions.executeWithoutResult(
                        () -> {
                            for (var p : prepared) {
                                if (p.previous == null) insert(p);
                                else update(p);
                            }
                            for (var a : commit.getAppends()) {
                                var old = before.get(a.getKey());
                                long version = old == null ? 1 : old.getVersion() + 1;
                                boolean changed =
                                        replacement != null
                                                && replacement.getKey().equals(a.getKey());
                                log(
                                        a.getKey(),
                                        a.getOperationId(),
                                        changed ? "UPDATE" : "APPEND",
                                        changed
                                                ? WorkspaceTextDiff.difference(
                                                        old.getContent(), after.get(a.getKey()))
                                                : Map.of("added", a.getContent()),
                                        version,
                                        prepared.get(0).at,
                                        previousMetadata.get(a.getKey()),
                                        prepared.stream()
                                                .filter(p -> p.key.equals(a.getKey()))
                                                .findFirst()
                                                .orElseThrow(),
                                        a.getSessionId(),
                                        a.getTurnId(),
                                        a.getToolCallId());
                            }
                        });
                // 保留已提交的变更前后对象，作为不可变审计证据。
                return new WorkspaceDocumentCommitResult(
                        true, prepared.stream().map(Prepared::document).toList());
            } catch (Conflict | DuplicateKeyException changed) {
                prepared.forEach(this::discard);
                // 在事务外重新读取；重复操作以已提交记录为准，其他并发写入需要重新获取版本。
            } catch (RuntimeException error) {
                prepared.forEach(this::discard);
                throw error;
            }
        }
        throw new IllegalStateException("Workspace file changed concurrently; retry operation");
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceDocumentRepository处理步骤使用。
     *
     * @param keys 本次处理涉及的键集合。
     * @return 本次处理得到的结果集合。
     */
    private List<WorkspaceDocument> current(Set<WorkspaceDocumentKey> keys) {
        return keys.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    /**
     * 判断是否存在追加操作。
     *
     * @param key 当前对象的查找或写入键。
     * @param operation 当前JDBC工作区文档仓储使用的操作，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean hasAppendOperation(WorkspaceDocumentKey key, String operation) {
        Integer n =
                mapper.selectHasAppendOperation(
                        key.getOwnerKey(),
                        key.getAgentKey(),
                        key.getScopeKey(),
                        pathHash(key),
                        operation);
        return n != null && n > 0;
    }

    /**
     * 删除JDBC工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Override
    public boolean delete(WorkspaceDocumentKey key, long version) {
        requireAgentWritable(key);
        Metadata previousMetadata = metadata(key).orElse(null);
        var previous = previousMetadata == null ? null : read(previousMetadata);
        if (previous == null || previous.getVersion() != version) return false;
        boolean deleted =
                Boolean.TRUE.equals(
                        transactions.execute(
                                () -> {
                                    int n =
                                            mapper.updateDelete(
                                                    key.getOwnerKey(),
                                                    key.getAgentKey(),
                                                    key.getScopeKey(),
                                                    pathHash(key),
                                                    version);
                                    if (n == 1)
                                        log(
                                                key,
                                                "delete-" + UUID.randomUUID(),
                                                "DELETE",
                                                Map.of("removed", previous.getContent()),
                                                version + 1,
                                                Instant.now(),
                                                previousMetadata,
                                                null,
                                                null,
                                                null,
                                                null);
                                    return n == 1;
                                }));
        return deleted;
    }

    /**
     * 准备JDBC工作区文档仓储。
     *
     * @param key 当前对象的查找或写入键。
     * @param text 面向消息或事件消费者的文本内容。
     * @param previous 当前JDBC工作区文档仓储持有的previous对象，供相应处理步骤使用。
     * @return 本次操作返回的已准备结果。
     */
    private Prepared prepare(WorkspaceDocumentKey key, String text, WorkspaceDocument previous) {
        var uploaded = objects.upload(key, text);
        return new Prepared(
                key,
                text,
                uploaded.getReference(),
                uploaded.getChecksum(),
                uploaded.getBytes(),
                previous,
                Instant.now());
    }

    /**
     * 完成当前操作的insert步骤，按实现更新相应状态或依赖。
     *
     * @param p 当前JDBC工作区文档仓储持有的参数对象，供相应处理步骤使用。
     */
    private void insert(Prepared p) {
        var fileClass = WorkspaceFilePolicy.classify(p.key.getDocumentPath());
        mapper.updateInsert(
                WorkspaceFileRow.builder()
                        .ownerKey(p.key.getOwnerKey())
                        .agentKey(p.key.getAgentKey())
                        .scopeKey(p.key.getScopeKey())
                        .workspaceArea(fileClass.getArea())
                        .fileKind(fileClass.getKind())
                        .writePolicy(fileClass.getPolicy())
                        .pathHash(pathHash(p.key))
                        .filePath(p.key.getDocumentPath())
                        .contentRef(p.reference)
                        .checksumSha256(p.checksum)
                        .sizeBytes(p.bytes)
                        .createdAt(Timestamp.from(p.at))
                        .updatedAt(Timestamp.from(p.at))
                        .build());
    }

    /**
     * 更新JDBC工作区文档仓储。
     *
     * @param p 当前JDBC工作区文档仓储持有的参数对象，供相应处理步骤使用。
     * @throws Conflict 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void update(Prepared p) {
        int n =
                mapper.updateUpdate(
                        WorkspaceFileRow.builder()
                                .contentRef(p.reference)
                                .checksumSha256(p.checksum)
                                .sizeBytes(p.bytes)
                                .updatedAt(Timestamp.from(p.at))
                                .ownerKey(p.key.getOwnerKey())
                                .agentKey(p.key.getAgentKey())
                                .scopeKey(p.key.getScopeKey())
                                .pathHash(pathHash(p.key))
                                .version(p.previous.getVersion())
                                .build());
        if (n != 1) throw new Conflict();
    }

    /**
     * 完成当前操作的log步骤，按实现更新相应状态或依赖。
     *
     * @param key 当前对象的查找或写入键。
     * @param operation 当前JDBC工作区文档仓储使用的操作，供其处理与状态记录使用。
     * @param type 当前操作使用的目标类型或类别。
     * @param change change的索引映射，供按键查找或归并当前组件的数据。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @param time 当前JDBC工作区文档仓储持有的时间对象，供相应处理步骤使用。
     * @param before 当前JDBC工作区文档仓储持有的处理前对象，供相应处理步骤使用。
     * @param after 当前JDBC工作区文档仓储持有的处理后对象，供相应处理步骤使用。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     */
    private void log(
            WorkspaceDocumentKey key,
            String operation,
            String type,
            Map<String, String> change,
            long version,
            Instant time,
            Metadata before,
            Prepared after,
            String sessionId,
            String turnId,
            String toolCallId) {
        mapper.updateLog(
                WorkspaceOperationRow.builder()
                        .ownerKey(key.getOwnerKey())
                        .agentKey(key.getAgentKey())
                        .scopeKey(key.getScopeKey())
                        .pathHash(pathHash(key))
                        .filePath(key.getDocumentPath())
                        .operationId(operation)
                        .operationType(type)
                        .actorType("AGENT")
                        .sessionId(sessionId)
                        .turnId(turnId)
                        .toolCallId(toolCallId)
                        .changeJson(JdbcHistoryJson.encode(change))
                        .appliedVersion(version)
                        .createdAt(Timestamp.from(time))
                        .actorId(key.getAgentKey())
                        .beforeVersion(before == null ? 0L : before.version)
                        .afterVersion(after == null ? null : version)
                        .beforeRef(before == null ? null : before.reference)
                        .afterRef(after == null ? null : after.reference)
                        .beforeChecksum(before == null ? null : before.checksum)
                        .afterChecksum(after == null ? null : after.checksum)
                        .beforeSize(before == null ? null : before.bytes)
                        .afterSize(after == null ? null : after.bytes)
                        .build());
    }

    /**
     * 生成当前操作所需的currentReference文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private String currentReference(WorkspaceDocumentKey key) {
        return metadata(key).map(value -> value.reference).orElse(null);
    }

    /**
     * 完成当前操作的discard步骤，按实现更新相应状态或依赖。
     *
     * @param prepared 当前JDBC工作区文档仓储持有的已准备对象，供相应处理步骤使用。
     */
    private void discard(Prepared prepared) {
        discardReference(prepared.reference);
    }

    /**
     * 完成当前操作的discardReference步骤，按实现更新相应状态或依赖。
     *
     * @param reference 当前JDBC工作区文档仓储使用的引用，供其处理与状态记录使用。
     */
    private void discardReference(String reference) {
        objects.discard(reference);
    }

    /**
     * 取得并校验Agent可写。
     *
     * @param key 当前对象的查找或写入键。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireAgentWritable(WorkspaceDocumentKey key) {
        if (WorkspaceFilePolicy.serverOwned(key.getDocumentPath())) {
            throw new IllegalArgumentException(
                    "Agent cannot modify server-owned workspace file: " + key.getDocumentPath());
        }
    }

    /**
     * 生成当前操作所需的pathHash文本，供调用方继续处理。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     */
    private static String pathHash(WorkspaceDocumentKey key) {
        return hash(key.getDocumentPath().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算摘要JDBC工作区文档仓储。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次处理生成或读取的文本。
     */
    private static String hash(byte[] bytes) {
        return DigestUtils.sha256Hex(bytes);
    }

    /** JDBC工作区文档仓储内部的Conflict，封装该步骤需要的状态或输入输出。 */
    private static final class Conflict extends RuntimeException {}

    /** JDBC工作区文档仓储内部的元数据，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class Metadata {
        /** 组合资源归属与作用域的定位键，供仓储查询和更新使用。 */
        final WorkspaceDocumentKey key;

        /** 内容校验值，用于确认传输或存储后的内容一致。 */
        /** 内容对象的持久引用，供后续读取实际字节。 */
        final String reference, checksum;

        /** 记录版本，用于乐观并发控制或区分协议版本。 */
        /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
        final long bytes, version;

        /** 当前对象最近更新时的时间或版本信息。 */
        /** 当前对象首次创建时的时间或版本信息。 */
        final Instant created, updated;
    }

    /** JDBC工作区文档仓储内部的已准备，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class Prepared {
        /** 组合资源归属与作用域的定位键，供仓储查询和更新使用。 */
        final WorkspaceDocumentKey key;

        /** 内容校验值，用于确认传输或存储后的内容一致。 */
        /** 内容对象的持久引用，供后续读取实际字节。 */
        /** 面向消息或事件消费者的文本内容。 */
        final String text, reference, checksum;

        /** 当前内容字节或字节计数，用于传输、校验与容量控制。 */
        final long bytes;

        /** 当前操作之前的状态、内容引用或作用域，供恢复或回收使用。 */
        final WorkspaceDocument previous;

        /** 本次记录或提交所使用的时间点。 */
        final Instant at;

        /**
         * 构造并返回当前操作所需的结果对象。
         *
         * @return 本次操作返回的工作区文档结果。
         */
        WorkspaceDocument document() {
            return new WorkspaceDocument(
                    key,
                    text,
                    bytes,
                    previous == null ? 1 : previous.getVersion() + 1,
                    previous == null ? at : previous.getCreatedAt(),
                    at);
        }
    }
}
