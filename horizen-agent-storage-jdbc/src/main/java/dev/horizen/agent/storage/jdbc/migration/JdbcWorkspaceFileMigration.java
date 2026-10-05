package dev.horizen.agent.storage.jdbc.migration;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.document.WorkspaceContentRepository;
import dev.horizen.agent.domain.workspace.document.WorkspaceDocumentKey;
import dev.horizen.agent.domain.workspace.policy.WorkspaceFilePolicy;
import dev.horizen.agent.domain.workspace.policy.WorkspaceFilePolicy.Classification;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceFileMigrationMapper;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

/** 将旧内联文档迁移为云端对象和审计行；迁移需显式执行，且可中断后重新启动。 */
public class JdbcWorkspaceFileMigration {
    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final WorkspaceFileMigrationMapper mapper;

    /** 执行短数据库工作单元的事务边界；外部网络调用不属于该工作单元。 */
    private final UnitOfWork transactions;

    /** 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。 */
    private final WorkspaceContentRepository contents;

    /**
     * 创建JDBC工作区文件迁移，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     * @param contents 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public JdbcWorkspaceFileMigration(DataSource source, WorkspaceContentRepository contents) {
        this.mapper = MyBatisSessions.create(source).getMapper(WorkspaceFileMigrationMapper.class);
        this.transactions = new JdbcUnitOfWork(source);
        this.contents = Objects.requireNonNull(contents);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceFileMigration处理步骤使用。
     *
     * @param limit 本次处理或返回数量上限。
     * @return 本次操作返回的整数结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public int migrateBatch(int limit) {
        if (limit <= 0 || limit > 1000)
            throw new IllegalArgumentException("Migration batch limit is invalid");
        List<Legacy> rows =
                mapper.selectMigrateBatch(limit).stream()
                        .map(
                                rs ->
                                        new Legacy(
                                                new WorkspaceDocumentKey(
                                                        rs.getOwnerKey(),
                                                        rs.getAgentKey(),
                                                        rs.getScopeKey(),
                                                        rs.getDocumentPath()),
                                                rs.getContent(),
                                                rs.getSizeBytes(),
                                                rs.getVersion(),
                                                rs.getCreatedAt().toInstant(),
                                                rs.getUpdatedAt().toInstant()))
                        .toList();
        int migrated = 0;
        for (Legacy row : rows) {
            if (row.key.getDocumentPath().startsWith("memory/")) migrateLegacyDaily(row);
            else migrateFile(row);
            migrated++;
        }
        return migrated;
    }

    /**
     * 完成当前操作的migrateFile步骤，按实现更新相应状态或依赖。
     *
     * @param row 当前JDBC工作区文件迁移持有的存储记录对象，供相应处理步骤使用。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void migrateFile(Legacy row) {
        byte[] bytes = row.content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != row.sizeBytes)
            throw new IllegalStateException("Legacy workspace size mismatch");
        String reference = contents.upload(row.key, bytes);
        try {
            transactions.executeWithoutResult(
                    () -> {
                        mapper.updateMigrateFile(
                                row.key.getOwnerKey(),
                                row.key.getAgentKey(),
                                row.key.getScopeKey(),
                                fileClass(row.key).getArea(),
                                fileClass(row.key).getKind(),
                                fileClass(row.key).getPolicy(),
                                pathHash(row.key),
                                row.key.getDocumentPath(),
                                reference,
                                hash(bytes),
                                (long) bytes.length,
                                row.version,
                                Timestamp.from(row.createdAt),
                                Timestamp.from(row.updatedAt));
                        insertAudit(row, "LEGACY_IMPORT", Map.of("imported", row.content));
                        migrateOperationIds(row);
                    });
        } catch (DuplicateKeyException completedElsewhere) {
            contents.delete(reference);
        } catch (RuntimeException error) {
            try {
                contents.delete(reference);
            } catch (RuntimeException ignored) {
            }
            throw error;
        }
    }

    /**
     * 完成当前操作的migrateLegacyDaily步骤，按实现更新相应状态或依赖。
     *
     * @param row 当前JDBC工作区文件迁移持有的存储记录对象，供相应处理步骤使用。
     */
    private void migrateLegacyDaily(Legacy row) {
        transactions.executeWithoutResult(
                () -> {
                    insertAudit(row, "LEGACY_DAILY_IMPORT", Map.of("legacyMarkdown", row.content));
                    migrateOperationIds(row);
                });
    }

    /**
     * 完成当前操作的migrateOperationIds步骤，按实现更新相应状态或依赖。
     *
     * @param row 当前JDBC工作区文件迁移持有的存储记录对象，供相应处理步骤使用。
     */
    private void migrateOperationIds(Legacy row) {
        mapper.selectMigrateOperationIds(
                        row.key.getOwnerKey(),
                        row.key.getAgentKey(),
                        row.key.getScopeKey(),
                        pathHash(row.key))
                .forEach(
                        value -> {
                            try {
                                mapper.updateMigrateOperationIds(
                                        row.key.getOwnerKey(),
                                        row.key.getAgentKey(),
                                        row.key.getScopeKey(),
                                        pathHash(row.key),
                                        row.key.getDocumentPath(),
                                        value.get("operation_id"),
                                        value.get("applied_version"),
                                        value.get("created_at"));
                            } catch (DuplicateKeyException replay) {
                            }
                        });
    }

    /**
     * 完成当前操作的insertAudit步骤，按实现更新相应状态或依赖。
     *
     * @param row 当前JDBC工作区文件迁移持有的存储记录对象，供相应处理步骤使用。
     * @param type 当前操作使用的目标类型或类别。
     * @param change change的索引映射，供按键查找或归并当前组件的数据。
     */
    private void insertAudit(Legacy row, String type, Map<String, String> change) {
        String operation =
                "migration-"
                        + hash(
                                (row.key.getOwnerKey()
                                                + '\0'
                                                + row.key.getAgentKey()
                                                + '\0'
                                                + row.key.getScopeKey()
                                                + '\0'
                                                + row.key.getDocumentPath())
                                        .getBytes(StandardCharsets.UTF_8));
        try {
            mapper.updateInsertAudit(
                    row.key.getOwnerKey(),
                    row.key.getAgentKey(),
                    row.key.getScopeKey(),
                    pathHash(row.key),
                    row.key.getDocumentPath(),
                    operation,
                    type,
                    JdbcHistoryJson.encode(change),
                    row.version,
                    Timestamp.from(row.updatedAt));
        } catch (DuplicateKeyException replay) {
        }
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcWorkspaceFileMigration(
            WorkspaceFileMigrationMapper mapper,
            UnitOfWork transactions,
            WorkspaceContentRepository contents) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.contents = Objects.requireNonNull(contents);
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
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceFileMigration处理步骤使用。
     *
     * @param key 当前对象的查找或写入键。
     * @return 本次操作返回的分类结果。
     */
    private static Classification fileClass(WorkspaceDocumentKey key) {
        return WorkspaceFilePolicy.classify(key.getDocumentPath());
    }

    /**
     * 计算摘要JDBC工作区文件迁移。
     *
     * @param bytes 当前操作处理的内容字节。
     * @return 本次处理生成或读取的文本。
     */
    private static String hash(byte[] bytes) {
        return DigestUtils.sha256Hex(bytes);
    }

    /** JDBC工作区文件迁移内部的旧格式，封装该步骤需要的状态或输入输出。 */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class Legacy {
        /** 组合资源归属与作用域的定位键，供仓储查询和更新使用。 */
        final WorkspaceDocumentKey key;

        /** 当前记录或资源的正文内容；与资源标识和存储引用分开保存。 */
        final String content;

        /** 记录版本，用于乐观并发控制或区分协议版本。 */
        /** 内容大小，单位为字节。 */
        final long sizeBytes, version;

        /** 当前记录最近一次更新的时间。 */
        /** 当前记录的创建时间。 */
        final Instant createdAt, updatedAt;
    }
}
