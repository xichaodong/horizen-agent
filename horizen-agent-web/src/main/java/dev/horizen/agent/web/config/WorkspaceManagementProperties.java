package dev.horizen.agent.web.config;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 工作区管理接口的启用开关、服务凭据与允许访问的 Project 范围。
 */
@Data
@ConfigurationProperties("horizen.agent.workspace-management")
public class WorkspaceManagementProperties {
    /**
     * 是否启用工作区管理对应的功能。
     */
    private boolean enabled;

    /**
     * 服务访问令牌，由宿主配置提供，用于请求认证。
     */
    @ToString.Exclude
    private String token = "";

    /**
     * Project的标识集合，用于批量关联相应记录。
     */
    private Set<Long> projectIds = Set.of();
}
