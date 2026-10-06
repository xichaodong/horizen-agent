package dev.horizen.agent.storage.jdbc.repository.workspace;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.domain.workspace.policy.WorkspaceFilePolicy;
import dev.horizen.agent.domain.workspace.release.WorkspaceCatalogRepository;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.WorkspaceCatalogMapper;
import dev.horizen.agent.storage.jdbc.model.WorkspaceOperationRow;
import dev.horizen.agent.storage.jdbc.model.WorkspaceReleaseRow;
import dev.horizen.agent.storage.jdbc.transaction.JdbcUnitOfWork;
import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.Objects;

import javax.sql.DataSource;

/** 在同一事务中提交聚合版本、文件引用和审计，不执行对象 I/O。 */
public class JdbcWorkspaceCatalogRepository implements WorkspaceCatalogRepository {
    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final WorkspaceCatalogMapper mapper;

    /** 当前短数据库工作单元的提交与回滚控制器。 */
    private final UnitOfWork transaction;

    /**
     * 创建JDBC工作区目录仓储，初始化该组件所需的状态、配置或依赖。
     *
     * @param source 待解析或转换的来源对象。
     */
    public JdbcWorkspaceCatalogRepository(DataSource source) {
        this.mapper = MyBatisSessions.create(source).getMapper(WorkspaceCatalogMapper.class);
        transaction = new JdbcUnitOfWork(source);
    }

    /**
     * 生成当前操作所需的owner文本，供调用方继续处理。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String owner(long project) {
        return "project:" + project;
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcWorkspaceCatalogRepository(WorkspaceCatalogMapper mapper, UnitOfWork transaction) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = Objects.requireNonNull(transaction);
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次操作返回的草稿结果。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public Draft draft(long project, String agent) {
        return transaction.execute(
                () -> {
                    var rows =
                            mapper.selectDraft(project, agent).stream()
                                    .map(r -> new Draft(r, List.of()))
                                    .toList();
                    // 在短时锁内读取聚合和文件引用，文件内容在释放锁后读取。
                    if (rows.isEmpty()) return new Draft(0, List.of());
                    var aggregate = rows.get(0);
                    return new Draft(aggregate.getVersion(), files(project, agent));
                });
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次处理得到的结果集合。
     */
    private List<File> files(long project, String agent) {
        return mapper.selectFiles(owner(project), agent).stream()
                .map(
                        r ->
                                new File(
                                        r.getFilePath(),
                                        r.getContentRef(),
                                        r.getChecksumSha256(),
                                        r.getSizeBytes(),
                                        r.getMediaType()))
                .toList();
    }

    /**
     * 完成当前操作的lock步骤，按实现更新相应状态或依赖。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    private void lock(long project, String agent) {
        mapper.insertWorkspaceIfAbsent(project, agent);
        mapper.selectWorkspaceForUpdate(project, agent);
    }

    /**
     * 保存JDBC工作区目录仓储。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expected 当前JDBC工作区目录仓储使用的预期，供其处理与状态记录使用。
     * @param files 文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param operator 当前JDBC工作区目录仓储使用的操作符，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public boolean save(
            long project, String agent, long expected, List<File> files, String operator) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        () -> {
                            lock(project, agent);
                            if (mapper.advanceWorkspaceVersion(operator, project, agent, expected)
                                    != 1) return false;
                            var old = files(project, agent);
                            Map<String, File> previous = new HashMap<>();
                            old.forEach(f -> previous.put(f.getPath(), f));
                            Set<String> paths = new HashSet<>();
                            for (var file : files) {
                                paths.add(file.getPath());
                                var prior = previous.get(file.getPath());
                                if (prior != null
                                        && prior.getChecksum().equals(file.getChecksum())
                                        && Objects.equals(
                                                prior.getMediaType(), file.getMediaType()))
                                    continue;
                                String kind = WorkspaceFilePolicy.kind(file.getPath());
                                long priorVersion =
                                        prior == null
                                                ? 0
                                                : fileVersion(project, agent, file.getPath());
                                mapper.upsertDraftFile(
                                        owner(project),
                                        agent,
                                        kind,
                                        hash(file.getPath()),
                                        file.getPath(),
                                        file.getReference(),
                                        file.getChecksum(),
                                        file.getSize(),
                                        file.getMediaType());
                                audit(
                                        project,
                                        agent,
                                        prior,
                                        file,
                                        "draft-" + (expected + 1),
                                        prior == null ? "CREATE" : "UPDATE",
                                        operator,
                                        priorVersion,
                                        priorVersion + 1);
                            }
                            for (var prior : old)
                                if (!paths.contains(prior.getPath())) {
                                    long priorVersion =
                                            fileVersion(project, agent, prior.getPath());
                                    mapper.deleteDraftFile(
                                            owner(project), agent, hash(prior.getPath()));
                                    audit(
                                            project,
                                            agent,
                                            prior,
                                            null,
                                            "draft-" + (expected + 1),
                                            "DELETE",
                                            operator,
                                            priorVersion,
                                            null);
                                }
                            return true;
                        }));
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的长整型结果。
     */
    private long fileVersion(long project, String agent, String path) {
        return mapper.selectFileVersion(owner(project), agent, hash(path));
    }

    /**
     * 完成当前操作的audit步骤，按实现更新相应状态或依赖。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param before 当前JDBC工作区目录仓储持有的处理前对象，供相应处理步骤使用。
     * @param after 当前JDBC工作区目录仓储持有的处理后对象，供相应处理步骤使用。
     * @param operation 当前JDBC工作区目录仓储使用的操作，供其处理与状态记录使用。
     * @param type 当前操作使用的目标类型或类别。
     * @param operator 当前JDBC工作区目录仓储使用的操作符，供其处理与状态记录使用。
     * @param beforeVersion 处理前的版本，供兼容或并发检查使用。
     * @param afterVersion 处理后的版本，供兼容或并发检查使用。
     */
    private void audit(
            long project,
            String agent,
            File before,
            File after,
            String operation,
            String type,
            String operator,
            long beforeVersion,
            Long afterVersion) {
        File file = after == null ? before : after;
        mapper.insertWorkspaceOperation(
                WorkspaceOperationRow.builder()
                        .ownerKey(owner(project))
                        .agentKey(agent)
                        .pathHash(hash(file.getPath()))
                        .filePath(file.getPath())
                        .operationId(operation + "-" + type)
                        .operationType(type)
                        .changeJson(JdbcHistoryJson.encode(Map.of("operator", operator)))
                        .appliedVersion(afterVersion == null ? beforeVersion + 1 : afterVersion)
                        .actorId(operator)
                        .beforeVersion(beforeVersion)
                        .afterVersion(afterVersion)
                        .beforeRef(before == null ? null : before.getReference())
                        .afterRef(after == null ? null : after.getReference())
                        .beforeChecksum(before == null ? null : before.getChecksum())
                        .afterChecksum(after == null ? null : after.getChecksum())
                        .beforeSize(before == null ? null : before.getSize())
                        .afterSize(after == null ? null : after.getSize())
                        .mediaType(file.getMediaType())
                        .build());
    }

    /**
     * 发布JDBC工作区目录仓储。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expected 当前JDBC工作区目录仓储使用的预期，供其处理与状态记录使用。
     * @param manifest 当前JDBC工作区目录仓储使用的清单，供其处理与状态记录使用。
     * @param hash 内容或索引的摘要值，供去重、校验或缓存寻址使用。
     * @param notes 当前JDBC工作区目录仓储使用的说明集合，供其处理与状态记录使用。
     * @param operator 当前JDBC工作区目录仓储使用的操作符，供其处理与状态记录使用。
     * @return 本次操作返回的发布结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public Release publish(
            long project,
            String agent,
            long expected,
            String manifest,
            String hash,
            String notes,
            String operator) {
        return transaction.execute(
                () -> {
                    lock(project, agent);
                    long version = mapper.selectWorkspaceVersion(project, agent);
                    if (version != expected)
                        throw new IllegalStateException("Workspace version conflict");
                    var prior = current(project, agent).orElse(null);
                    if (prior != null && prior.getReleaseHash().equals(hash))
                        throw new IllegalStateException("Workspace content already published");
                    long number = prior == null ? 1 : prior.getReleaseNo() + 1;
                    mapper.insertWorkspaceRelease(
                            project, agent, number, hash, manifest, notes, operator);
                    var release = currentByNumber(project, agent, number);
                    mapper.setCurrentRelease(release.getId(), project, agent, expected);
                    for (var file : files(project, agent)) {
                        long fileVersion = fileVersion(project, agent, file.getPath());
                        audit(
                                project,
                                agent,
                                file,
                                file,
                                "release-" + number,
                                "PUBLISH",
                                operator,
                                fileVersion,
                                fileVersion);
                    }
                    return release;
                });
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param number 当前JDBC工作区目录仓储使用的number，供其处理与状态记录使用。
     * @return 本次操作返回的发布结果。
     */
    private Release currentByNumber(long project, String agent, long number) {
        return mapper.selectByNumber(project, agent, number).stream()
                .map(JdbcWorkspaceCatalogRepository::releaseView)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<Release> current(long project, String agent) {
        return mapper.selectCurrent(project, agent).stream()
                .map(JdbcWorkspaceCatalogRepository::releaseView)
                .findFirst();
    }

    /**
     * 释放JDBC工作区目录仓储。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param id 目标对象的标识。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<Release> release(long project, String agent, long id) {
        return mapper.selectById(project, agent, id).stream()
                .map(JdbcWorkspaceCatalogRepository::releaseView)
                .findFirst();
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcWorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前JDBC工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent 当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<Release> releases(long project, String agent) {
        return mapper.selectReleases(project, agent).stream()
                .map(JdbcWorkspaceCatalogRepository::releaseView)
                .toList();
    }

    /**
     * 释放视图。
     *
     * @param row 当前JDBC工作区目录仓储持有的存储记录对象，供相应处理步骤使用。
     * @return 本次操作返回的发布结果。
     */
    private static Release releaseView(WorkspaceReleaseRow row) {
        return new Release(
                row.getId(),
                row.getReleaseNo(),
                row.getReleaseHash(),
                row.getManifestJson(),
                row.getNotes(),
                row.getCreatedBy(),
                row.getCreatedAt().toInstant());
    }

    /**
     * 计算摘要JDBC工作区目录仓储。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次处理生成或读取的文本。
     */
    private static String hash(String path) {
        return DigestUtils.sha256Hex(path);
    }
}
