package dev.horizen.agent.web.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.beans.ConstructorProperties;
import java.time.Duration;

/** Web 宿主的分布式存储配置；Core 不理解具体数据库地址。 */
@ConfigurationProperties(prefix = "horizen.agent.storage")
@Getter
@EqualsAndHashCode
@ToString
public class RuntimeStorageProperties {
    /** 当前功能模式，控制所选适配器或处理策略。 */
    private final Mode mode;

    /** 共享事实数据库的 JDBC 连接地址。 */
    private final String jdbcUrl;

    /** 连接共享事实数据库使用的服务端账号。 */
    private final String jdbcUsername;

    /** 连接共享事实数据库使用的服务端凭据。 */
    @ToString.Exclude private final String jdbcPassword;

    /** JDBC 连接池允许创建的最大连接数量。 */
    private final int jdbcMaximumPoolSize;

    /** 每个 Redis 连接池允许保留的最大连接数量。 */
    private final int redisMaximumPoolSize;

    /** Redis 状态与事件存储的连接地址。 */
    private final String redisUrl;

    /** Redis键使用的公共前缀，用于分组或存储寻址。 */
    private final String redisKeyPrefix;

    /** 当前服务实例标识，用于区分分布式执行与资源统计。 */
    private final String instanceId;

    /** 当前执行增量事件的保留时间，供断线回放使用。 */
    private final Duration eventTtl;

    /** 会话工作状态的不活跃保留时间，与持久会话历史分开管理。 */
    private final Duration sessionStateTtl;

    /** 执行实例租约的有效时长。 */
    private final Duration leaseTtl;

    /** 执行租约续期的调度间隔。 */
    private final Duration leaseHeartbeat;

    /** 跨实例执行控制消息的轮询间隔。 */
    private final Duration controlPollInterval;

    /**
     * 创建运行时存储配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param mode 当前功能模式，控制所选适配器或处理策略。
     * @param jdbcUrl 当前运行时存储配置使用的JDBCURL，供其处理与状态记录使用。
     * @param jdbcUsername 当前运行时存储配置使用的JDBC账号，供其处理与状态记录使用。
     * @param jdbcPassword 当前运行时存储配置使用的JDBC密码，供其处理与状态记录使用。
     * @param jdbcMaximumPoolSize 当前运行时存储配置使用的JDBC最大连接池大小，供其处理与状态记录使用。
     * @param redisMaximumPoolSize 当前运行时存储配置使用的Redis最大连接池大小，供其处理与状态记录使用。
     * @param redisUrl 当前运行时存储配置使用的RedisURL，供其处理与状态记录使用。
     * @param redisKeyPrefix Redis键使用的公共前缀，用于分组或存储寻址。
     * @param instanceId 当前服务实例标识，用于区分分布式执行与资源统计。
     * @param eventTtl 当前执行增量事件的保留时间，供断线回放使用。
     * @param sessionStateTtl 会话工作状态的不活跃保留时间，与持久会话历史分开管理。
     * @param leaseTtl 执行实例租约的有效时长。
     * @param leaseHeartbeat 执行租约续期的调度间隔。
     * @param controlPollInterval 跨实例执行控制消息的轮询间隔。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({
        "mode",
        "jdbcUrl",
        "jdbcUsername",
        "jdbcPassword",
        "jdbcMaximumPoolSize",
        "redisMaximumPoolSize",
        "redisUrl",
        "redisKeyPrefix",
        "instanceId",
        "eventTtl",
        "sessionStateTtl",
        "leaseTtl",
        "leaseHeartbeat",
        "controlPollInterval"
    })
    public RuntimeStorageProperties(
            Mode mode,
            String jdbcUrl,
            String jdbcUsername,
            String jdbcPassword,
            int jdbcMaximumPoolSize,
            int redisMaximumPoolSize,
            String redisUrl,
            String redisKeyPrefix,
            String instanceId,
            Duration eventTtl,
            Duration sessionStateTtl,
            Duration leaseTtl,
            Duration leaseHeartbeat,
            Duration controlPollInterval) {
        mode = mode == null ? Mode.LOCAL : mode;
        jdbcUrl = trim(jdbcUrl);
        jdbcUsername = trim(jdbcUsername);
        jdbcPassword = jdbcPassword == null ? "" : jdbcPassword;
        jdbcMaximumPoolSize = jdbcMaximumPoolSize <= 0 ? 10 : jdbcMaximumPoolSize;
        redisMaximumPoolSize = redisMaximumPoolSize <= 0 ? 8 : redisMaximumPoolSize;
        redisUrl = trim(redisUrl);
        redisKeyPrefix = text(redisKeyPrefix, "horizen-agent:");
        instanceId = trim(instanceId);
        eventTtl = positive(eventTtl, Duration.ofMinutes(15), "eventTtl");
        sessionStateTtl = positive(sessionStateTtl, Duration.ofHours(24), "sessionStateTtl");
        if (sessionStateTtl.compareTo(Duration.ofMinutes(30)) <= 0) {
            throw new IllegalArgumentException("sessionStateTtl 必须大于 30 分钟的人机等待窗口");
        }
        leaseTtl = positive(leaseTtl, Duration.ofSeconds(30), "leaseTtl");
        leaseHeartbeat = positive(leaseHeartbeat, Duration.ofSeconds(10), "leaseHeartbeat");
        controlPollInterval =
                positive(controlPollInterval, Duration.ofMillis(500), "controlPollInterval");
        if (!leaseHeartbeat.minus(leaseTtl).isNegative()) {
            throw new IllegalArgumentException("leaseHeartbeat 必须小于 leaseTtl");
        }
        if (mode == Mode.DISTRIBUTED) {
            if (jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("distributed 模式必须配置 jdbcUrl");
            }
            if (redisUrl.isEmpty()) {
                throw new IllegalArgumentException("distributed 模式必须配置 redisUrl");
            }
        }

        this.mode = mode;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUsername = jdbcUsername;
        this.jdbcPassword = jdbcPassword;
        this.jdbcMaximumPoolSize = jdbcMaximumPoolSize;
        this.redisMaximumPoolSize = redisMaximumPoolSize;
        this.redisUrl = redisUrl;
        this.redisKeyPrefix = redisKeyPrefix;
        this.instanceId = instanceId;
        this.eventTtl = eventTtl;
        this.sessionStateTtl = sessionStateTtl;
        this.leaseTtl = leaseTtl;
        this.leaseHeartbeat = leaseHeartbeat;
        this.controlPollInterval = controlPollInterval;
    }

    /**
     * 检查distributed对应的条件，供调用方选择后续处理分支。
     *
     * @return 本次检查是否通过或本次更新是否成功。
     */
    public boolean distributed() {
        return mode == Mode.DISTRIBUTED;
    }

    /** 宿主存储模式，区分单机开发状态与分布式持久化。 */
    public enum Mode {
        /** 使用单机开发环境中的本地适配器。 */
        LOCAL,
        /** 使用共享状态与持久化适配器，支持多个宿主实例。 */
        DISTRIBUTED
    }

    /**
     * 裁剪运行时存储配置。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param fallback 当前运行时存储配置使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeStorageProperties处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param fallback 回退的时间配置，供等待、调度或失效判断使用。
     * @param name 需要定位或处理的名称。
     * @return 本次操作返回的耗时结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration result = value == null ? fallback : value;
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return result;
    }
}
