package dev.horizen.agent.skill;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.common.validation.Preconditions;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.util.List;

/** Horizen 返回的不可变发布元数据。 */
@Getter
@EqualsAndHashCode
@ToString
public class SkillReleaseManifest {
    /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
    private final long projectId;

    /** 发布记录标识，用于取得会话绑定的具体发布快照。 */
    private final long releaseId;

    /** 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。 */
    private final long releaseNo;

    /** 发布内容哈希，用于完整性校验和锁定会话的发布内容。 */
    private final String releaseHash;

    /** Skill的数量，供运行统计或容量控制使用。 */
    private final int skillCount;

    /** 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    private final List<Item> items;

    /**
     * 创建Skill发布清单，初始化该组件所需的状态、配置或依赖。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @param releaseNo 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @param skillCount Skill的数量，供运行统计或容量控制使用。
     * @param items 条目集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "projectId",
        "releaseId",
        "releaseNo",
        "releaseHash",
        "skillCount",
        "items"
    })
    public SkillReleaseManifest(
            long projectId,
            long releaseId,
            long releaseNo,
            String releaseHash,
            int skillCount,
            List<Item> items) {
        items = List.copyOf(items == null ? List.of() : items);
        if (projectId <= 0 || releaseId <= 0 || releaseNo <= 0) {
            throw new IllegalArgumentException("Skill release identifiers must be positive");
        }
        if (releaseHash == null || !Identifiers.SHA256.matcher(releaseHash).matches()) {
            throw new IllegalArgumentException("releaseHash must be 64 lowercase hex characters");
        }
        if (skillCount != items.size()) {
            throw new IllegalArgumentException("skillCount does not match release items");
        }

        this.projectId = projectId;
        this.releaseId = releaseId;
        this.releaseNo = releaseNo;
        this.releaseHash = releaseHash;
        this.skillCount = skillCount;
        this.items = items;
    }

    /** Skill 发布中的单个技能描述，包含名称、内容位置与完整性信息。 */
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class Item {
        /** Skill的标识，用于关联相应记录或执行。 */
        private final long skillId;

        /** Skill 目录中的稳定标识，用于版本和资源定位。 */
        private final String skillKey;

        /** 版本的标识，用于关联相应记录或执行。 */
        private final long versionId;

        /** 记录版本，用于乐观并发控制或区分协议版本。 */
        private final String version;

        /** 暴露给运行时的 Skill 名称，应与技能正文声明一致。 */
        private final String runtimeName;

        /** 发布制品的下载地址，供缓存准备流程取得内容。 */
        private final String packageUrl;

        /** 内容的 SHA-256 摘要，参与制品完整性验证。 */
        private final String sha256;

        /** 发布制品声明的字节数，用于校验下载内容与容量边界。 */
        private final long packageSize;

        /** 最小Agent的版本，供兼容或并发检查使用。 */
        private final String minAgentVersion;

        /**
         * 创建条目，初始化该组件所需的状态、配置或依赖。
         *
         * @param skillId Skill的标识，用于关联相应记录或执行。
         * @param skillKey 当前条目使用的Skill键，供其处理与状态记录使用。
         * @param versionId 版本的标识，用于关联相应记录或执行。
         * @param version 记录版本，用于乐观并发控制或区分协议版本。
         * @param runtimeName 当前条目使用的运行时名称，供其处理与状态记录使用。
         * @param packageUrl 当前条目使用的包URL，供其处理与状态记录使用。
         * @param sha256 内容的 SHA-256 摘要，参与制品完整性验证。
         * @param packageSize 当前条目使用的包大小，供其处理与状态记录使用。
         * @param minAgentVersion 最小Agent的版本，供兼容或并发检查使用。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        @ConstructorProperties({
            "skillId",
            "skillKey",
            "versionId",
            "version",
            "runtimeName",
            "packageUrl",
            "sha256",
            "packageSize",
            "minAgentVersion"
        })
        public Item(
                long skillId,
                String skillKey,
                long versionId,
                String version,
                String runtimeName,
                String packageUrl,
                String sha256,
                long packageSize,
                String minAgentVersion) {
            if (skillId <= 0 || versionId <= 0) {
                throw new IllegalArgumentException(
                        "Skill and version identifiers must be positive");
            }
            skillKey = requireName(skillKey, "skillKey");
            runtimeName = requireName(runtimeName, "runtimeName");
            version = Preconditions.requireText(version, "version must not be blank").trim();
            packageUrl =
                    Preconditions.requireText(packageUrl, "packageUrl must not be blank").trim();
            minAgentVersion = minAgentVersion == null ? "" : minAgentVersion.trim();
            if (sha256 == null || !Identifiers.SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            if (packageSize <= 0) {
                throw new IllegalArgumentException("packageSize must be positive");
            }

            this.skillId = skillId;
            this.skillKey = skillKey;
            this.versionId = versionId;
            this.version = version;
            this.runtimeName = runtimeName;
            this.packageUrl = packageUrl;
            this.sha256 = sha256;
            this.packageSize = packageSize;
            this.minAgentVersion = minAgentVersion;
        }

        /**
         * 取得并校验名称。
         *
         * @param value 待校验、转换或保存的原始值。
         * @param field 当前条目使用的字段，供其处理与状态记录使用。
         * @return 本次处理生成或读取的文本。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        private static String requireName(String value, String field) {
            String text = Preconditions.requireText(value, field + " must not be blank").trim();
            if (!text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException(field + " contains unsafe characters");
            }
            return text;
        }
    }
}
