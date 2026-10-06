package dev.horizen.agent.web.config;

import dev.horizen.agent.domain.workspace.release.AgentCatalogKey;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * 默认关闭，直到平台存在已发布的 Agent 工作区。
 */
@ConfigurationProperties("horizen.agent.workspace-release")
@Data
public class AgentWorkspaceProperties {
    /**
     * 是否启用Agent工作区对应的功能。
     */
    private boolean enabled;

    /**
     * 在调用方显式切换前，现有远程发布仍是默认来源。
     */
    private Source source = Source.REMOTE;

    /**
     * 工作区发布描述的读取来源，供组装对应仓储。
     */
    public enum Source {
        /**
         * 通过远端服务完成对应功能。
         */
        REMOTE,
        /**
         * 使用单机开发环境中的本地适配器。
         */
        LOCAL
    }

    /**
     * 已解析的服务接口地址，供实际网络请求使用。
     */
    private String endpoint = "";

    /**
     * 工作区或发布所属 Project 的标识，参与资源归属校验。
     */
    private long projectId;

    /**
     * 宿主约定的 Agent 标识，用于限定工作区、发布和记忆的归属。
     */
    private String agentKey = AgentProperties.AGENT_KEY;

    /**
     * 服务访问令牌，由宿主配置提供，用于请求认证。
     */
    @ToString.Exclude
    private String token = "";

    /**
     * 产物Hosts的去重集合，供成员查找或范围检查使用。
     */
    private Set<String> artifactHosts = Set.of();

    /**
     * 是否允许通过明文 HTTP 读取远端资源；由相应下载策略校验。
     */
    private boolean allowHttp;

    /**
     * 单次远端请求允许的最长等待时间。
     */
    private Duration requestTimeout = Duration.ofSeconds(15);

    /**
     * 发布制品缓存目录，用于复用已校验的下载内容。
     */
    private Path cacheDirectory = Path.of(".agentscope", "agent-publications");

    /**
     * 最大缓存的字节数，用于容量或传输限制。
     */
    private long maxCacheBytes = 1024L * 1024 * 1024;

    /**
     * 当前发布准备与工作区执行允许的并发数量上限。
     */
    private int maxConcurrentExecutions = 8;

    /**
     * 校验当前Agent工作区配置的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void validate() {
        if (!enabled) return;
        if (projectId <= 0
                || source == null
                || source == Source.REMOTE && (endpoint.isBlank() || artifactHosts.isEmpty()))
            throw new IllegalArgumentException(
                    "Workspace releases require endpoint, project-id and artifact-hosts");
        new AgentCatalogKey(projectId, agentKey);
        if (maxConcurrentExecutions < 1 || maxConcurrentExecutions > 64)
            throw new IllegalArgumentException("Invalid workspace execution concurrency");
        if (!AgentProperties.AGENT_KEY.equals(agentKey))
            throw new IllegalArgumentException("agent-key must match the hosted agent identity");
    }
}
