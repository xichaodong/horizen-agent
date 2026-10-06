package dev.horizen.agent.web.bootstrap.workspace;

import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;
import dev.horizen.agent.storage.bos.BosWorkspaceContentRepository;
import dev.horizen.agent.storage.jdbc.migration.JdbcWorkspaceFileMigration;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 显式离线迁移命令，不删除旧表，也不输出凭据。
 */
public final class WorkspaceFileMigrationCommand {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private WorkspaceFileMigrationCommand() {
    }

    /**
     * 完成当前操作的main步骤，按实现更新相应状态或依赖。
     *
     * @param args 当前工作区文件迁移命令持有的参数集合对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <config.yml>");
        Properties settings = load(Path.of(args[0]));
        var source =
                new DriverManagerDataSource(
                        required(settings, "horizen.agent.storage.jdbc-url"),
                        required(settings, "horizen.agent.storage.jdbc-username"),
                        settings.getProperty("horizen.agent.storage.jdbc-password", ""));
        String prefix =
                settings.getProperty(
                                "horizen.agent.workspace-storage.key-prefix",
                                "agentFiles/horizen-workspace-files")
                        .trim();
        if (prefix.isEmpty()) throw new IllegalArgumentException("Workspace file prefix is empty");
        var config =
                new BosArtifactContentStoreConfig(
                        required(settings, "horizen.agent.artifact.bos.endpoint"),
                        required(settings, "horizen.agent.artifact.bos.bucket"),
                        required(settings, "horizen.agent.artifact.bos.access-key"),
                        required(settings, "horizen.agent.artifact.bos.secret-key"),
                        prefix,
                        1024L * 1024L);
        int total = 0;
        try (var contents = new BosWorkspaceContentRepository(config)) {
            var migration = new JdbcWorkspaceFileMigration(source, contents);
            int batch;
            while ((batch = migration.migrateBatch(100)) > 0) {
                total += batch;
                System.out.println("Workspace files migrated: " + total);
            }
        }
        System.out.println(
                "Workspace file migration completed: "
                        + total
                        + " legacy documents processed; source tables retained");
    }

    /**
     * 加载工作区文件迁移命令。
     *
     * @param path 需要读取、写入或校验的路径。
     * @return 本次操作返回的配置结果。
     */
    private static Properties load(Path path) throws Exception {
        var p = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            p.putAll(YamlConfigFiles.load(input));
        }
        return p;
    }

    /**
     * 生成当前操作所需的required文本，供调用方继续处理。
     *
     * @param p   当前工作区文件迁移命令持有的参数对象，供相应处理步骤使用。
     * @param key 当前对象的查找或写入键。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing configuration: " + key);
        return value;
    }
}
