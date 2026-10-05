package dev.horizen.agent.web.bootstrap.storage;

import com.zaxxer.hikari.*;

import dev.horizen.agent.storage.jdbc.config.AgentJdbcConfiguration;
import dev.horizen.agent.storage.jdbc.config.JdbcStorageOptions;
import dev.horizen.agent.web.config.LeaseRenewalProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.util.UUID;

/** 管理服务持久化资源，将 Mapper 和 Repository 组装交给 Spring。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "horizen.agent.storage.mode", havingValue = "DISTRIBUTED")
@Import(AgentJdbcConfiguration.class)
public class StorageConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param leases 当前存储组装持有的租约集合对象，供相应处理步骤使用。
     * @return 本次操作返回的Hikari数据来源结果。
     */
    @Bean(destroyMethod = "close", name = "agentDataSource")
    HikariDataSource dataSource(
            RuntimeStorageProperties properties, LeaseRenewalProperties leases) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getJdbcUrl());
        config.setUsername(properties.getJdbcUsername());
        config.setPassword(properties.getJdbcPassword());
        config.setMaximumPoolSize(properties.getJdbcMaximumPoolSize());
        config.setConnectionTimeout(leases.getConnectionAcquireTimeout().toMillis());
        config.setPoolName("horizen-agent-jdbc");
        return new HikariDataSource(config);
    }

    /**
     * 生成当前操作所需的instanceId文本，供调用方继续处理。
     *
     * @param storage 当前存储组装持有的存储对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     */
    @Bean("agentInstanceId")
    String instanceId(RuntimeStorageProperties storage) {
        return storage.getInstanceId().isBlank()
                ? "agent-" + UUID.randomUUID()
                : storage.getInstanceId();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @param leases 当前存储组装持有的租约集合对象，供相应处理步骤使用。
     * @return 本次操作返回的JDBC存储选项集合结果。
     */
    @Bean
    JdbcStorageOptions jdbcStorageOptions(
            @Qualifier("agentInstanceId") String instanceId, LeaseRenewalProperties leases) {
        return new JdbcStorageOptions(instanceId, leases.getQueryTimeout());
    }
}
