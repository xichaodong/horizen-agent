package dev.horizen.agent.domain.workspace.release;

import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 由 Agent 拥有的工作区聚合；文件内容通过 WorkspaceContentRepository 访问。
 */
public interface WorkspaceCatalogRepository {
    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次操作返回的草稿结果。
     */
    Draft draft(long project, String agent);

    /**
     * 保存工作区目录仓储。
     *
     * @param project         当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent           当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @param files           文件集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param operator        当前工作区目录仓储使用的操作符，供其处理与状态记录使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean save(
            long project, String agent, long expectedVersion, List<File> files, String operator);

    /**
     * 发布工作区目录仓储。
     *
     * @param project         当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent           当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param expectedVersion 调用方观察到的版本，更新时用于识别并发修改。
     * @param manifestJson    清单的 JSON 表示，供持久化或协议转换使用。
     * @param releaseHash     发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @param notes           当前工作区目录仓储使用的说明集合，供其处理与状态记录使用。
     * @param operator        当前工作区目录仓储使用的操作符，供其处理与状态记录使用。
     * @return 本次操作返回的发布结果。
     */
    Release publish(
            long project,
            String agent,
            long expectedVersion,
            String manifestJson,
            String releaseHash,
            String notes,
            String operator);

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<Release> current(long project, String agent);

    /**
     * 释放工作区目录仓储。
     *
     * @param project 当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param id      目标对象的标识。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<Release> release(long project, String agent, long id);

    /**
     * 计算或取得本方法声明的结果，供当前WorkspaceCatalogRepository处理步骤使用。
     *
     * @param project 当前工作区目录仓储使用的Project，供其处理与状态记录使用。
     * @param agent   当前配置的 Agent 实例，承担模型与工具循环执行。
     * @return 本次处理得到的结果集合。
     */
    List<Release> releases(long project, String agent);

    /**
     * 尚未发布的工作区草稿元数据。
     */
    @Value
    class Draft {
        /**
         * 记录版本，用于乐观并发控制或区分协议版本。
         */
        long version;

        /**
         * 当前工作区目录中的文件内容访问端口。
         */
        List<File> files;
    }

    /**
     * 工作区目录中的文件元数据与内容引用。
     */
    @Value
    class File {
        /**
         * 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。
         */
        String path;

        /**
         * 内容对象的持久引用，供后续读取实际字节。
         */
        String reference;

        /**
         * 内容校验值，用于确认传输或存储后的内容一致。
         */
        String checksum;

        /**
         * 当前内容或集合的大小，计量方式由所属资源协议定义。
         */
        long size;

        /**
         * 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。
         */
        String mediaType;
    }

    /**
     * 一条完整工作区发布的目录记录。
     */
    @Value
    class Release {
        /**
         * 当前发布的定位标识。
         */
        long id;

        /**
         * 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
         */
        long releaseNo;

        /**
         * 发布内容哈希，用于完整性校验和锁定会话的发布内容。
         */
        String releaseHash;

        /**
         * 清单的 JSON 表示，供持久化或协议转换使用。
         */
        String manifestJson;

        /**
         * 发布的可读说明，不参与运行时身份推断。
         */
        String notes;

        /**
         * 创建该记录的操作方标识，用于审计来源。
         */
        String createdBy;

        /**
         * 当前记录的创建时间。
         */
        Instant createdAt;
    }
}
