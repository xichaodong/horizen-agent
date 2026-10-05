package dev.horizen.agent.domain.workspace.release;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.skill.SkillReleaseManifest;

import lombok.Getter;

import java.util.*;

/** 完整发布内容；签名 URL 属于传输数据，不参与内容哈希。 */
@Getter
public final class AgentReleaseManifest {
    /** 工作区或发布所属 Project 的标识，参与资源归属校验。 */
    private final long projectId;

    /** 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。 */
    private final String agentKey;

    /** 发布记录标识，用于取得会话绑定的具体发布快照。 */
    private final long releaseId;

    /** 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。 */
    private final long releaseNo;

    /** 发布内容哈希，用于完整性校验和锁定会话的发布内容。 */
    private final String releaseHash;

    /** assets的有序集合，保留当前组件处理或协议输出所需的顺序。 */
    private final List<Asset> assets;

    /** 完整工作区发布所包含的 Skill 发布视图。 */
    private final SkillReleaseManifest skillRelease;

    /** 工作区文件集合的状态标记，用于选择当前组件的处理路径。 */
    private boolean workspaceFiles;

    /**
     * 创建Agent发布清单，初始化该组件所需的状态、配置或依赖。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @param releaseNo 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @param assets assets的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    public AgentReleaseManifest(
            long projectId,
            String agentKey,
            long releaseId,
            long releaseNo,
            String releaseHash,
            List<Asset> assets) {
        this(
                projectId,
                agentKey,
                releaseId,
                releaseNo,
                releaseHash,
                assets,
                new SkillReleaseManifest(
                        projectId, releaseId, releaseNo, releaseHash, 0, List.of()));
        workspaceFiles = true;
    }

    /**
     * 创建Agent发布清单，初始化该组件所需的状态、配置或依赖。
     *
     * @param projectId 工作区或发布所属 Project 的标识，参与资源归属校验。
     * @param agentKey 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     * @param releaseId 发布记录标识，用于取得会话绑定的具体发布快照。
     * @param releaseNo 发布序号，用于版本展示；与发布记录标识、内容哈希分别保存。
     * @param releaseHash 发布内容哈希，用于完整性校验和锁定会话的发布内容。
     * @param assets assets的有序集合，保留当前组件处理或协议输出所需的顺序。
     * @param skills 当前Agent发布清单持有的Skill集合对象，供相应处理步骤使用。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public AgentReleaseManifest(
            long projectId,
            String agentKey,
            long releaseId,
            long releaseNo,
            String releaseHash,
            List<Asset> assets,
            SkillReleaseManifest skills) {
        new AgentCatalogKey(projectId, agentKey);
        if (releaseId <= 0
                || releaseNo <= 0
                || releaseHash == null
                || !Identifiers.SHA256.matcher(releaseHash).matches())
            throw new IllegalArgumentException("Invalid publication identity");
        this.projectId = projectId;
        this.agentKey = agentKey;
        this.releaseId = releaseId;
        this.releaseNo = releaseNo;
        this.releaseHash = releaseHash;
        this.assets = List.copyOf(assets);
        this.skillRelease = Objects.requireNonNull(skills);
        if (skills.getProjectId() != projectId || this.assets.size() > 500)
            throw new IllegalArgumentException("Invalid publication scope or size");
        Set<String> paths = new HashSet<>();
        long total = 0;
        int agents = 0;
        for (Asset a : this.assets) {
            if (!paths.add(a.getPath()))
                throw new IllegalArgumentException("Duplicate publication path");
            total += a.getSize();
            if (a.getPath().startsWith("subagents/")) agents++;
        }
        if (total > 20L * 1024 * 1024
                || agents > 32
                || this.assets.stream()
                        .noneMatch(a -> a.getPath().equals("AGENTS.md") && a.getSize() > 0))
            throw new IllegalArgumentException("Publication exceeds limits or lacks AGENTS.md");
        for (String p : paths)
            for (String q : paths)
                if (q.startsWith(p + "/"))
                    throw new IllegalArgumentException("Publication path collision");
    }

    /**
     * 生成当前操作所需的safePath文本，供调用方继续处理。
     *
     * @param p 当前Agent发布清单使用的参数，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static String safePath(String p) {
        if (p == null
                || p.length() > 512
                || p.contains("\\")
                || p.indexOf(0) >= 0
                || Arrays.stream(p.split("/", -1))
                        .anyMatch(v -> v.isEmpty() || v.equals(".") || v.equals(".."))
                || !(p.equals("AGENTS.md")
                        || p.equals("MEMORY.md")
                        || p.equals("PLAN.md")
                        || p.equals("tools.json")
                        || p.startsWith("knowledge/")
                        || p.startsWith("memory/")
                        || p.startsWith("plans/")
                        || p.startsWith("tasks/")
                        || p.startsWith("skills/")
                        || p.matches("subagents/[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.md")))
            throw new IllegalArgumentException("Invalid publication path");
        return p;
    }

    /** 发布中一个制品的下载与校验描述；签名 URL 不计入发布内容哈希。 */
    @Getter
    public static final class Asset {
        /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
        /** 内容的 SHA-256 摘要，参与制品完整性验证。 */
        /** 资源或远端接口地址；具体访问范围由所属服务的配置校验。 */
        /** 当前资源路径，路径解释和合法范围由所属文件系统适配器限定。 */
        private final String path, url, sha256, mediaType;

        /** 当前内容或集合的大小，计量方式由所属资源协议定义。 */
        private final long size;

        /**
         * 创建制品，初始化该组件所需的状态、配置或依赖。
         *
         * @param path 需要读取、写入或校验的路径。
         * @param url 资源或远端接口地址；具体访问范围由所属服务的配置校验。
         * @param sha256 内容的 SHA-256 摘要，参与制品完整性验证。
         * @param size 当前内容或集合的大小，计量方式由所属资源协议定义。
         * @param mediaType 当前制品使用的媒体类型，供其处理与状态记录使用。
         * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
         */
        public Asset(String path, String url, String sha256, long size, String mediaType) {
            this.path = safePath(path);
            long limit =
                    path.equals("AGENTS.md") || path.startsWith("subagents/")
                            ? 65536
                            : 10L * 1024 * 1024;
            if (size < 0
                    || size > limit
                    || sha256 == null
                    || !Identifiers.SHA256.matcher(sha256).matches()
                    || url == null
                    || url.isBlank())
                throw new IllegalArgumentException("Invalid publication asset");
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.mediaType = mediaType;
        }
    }
}
