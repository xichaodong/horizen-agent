package dev.horizen.agent.storage.jdbc.config;

import lombok.Value;

import java.time.Duration;

/** 由服务配置层提供的 JDBC 配置。 */
@Value
public class JdbcStorageOptions {
    /** 当前服务实例标识，用于区分分布式执行与资源统计。 */
    String instanceId;

    /** 查询允许持续的最长等待时间。 */
    Duration queryTimeout;
}
