package dev.horizen.agent.storage.jdbc.config;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Duration;
import java.util.Properties;

import javax.sql.DataSource;

/** Spring 配置、独立迁移和隔离测试共用的 MyBatis 组装逻辑。 */
public final class MyBatisSessions {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private MyBatisSessions() {}

    /**
     * 创建MyBatis会话集合。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的SQL会话模板结果。
     */
    public static SqlSessionTemplate create(DataSource source) {
        return create(source, Duration.ofSeconds(3));
    }

    /**
     * 创建MyBatis会话集合。
     *
     * @param source 待解析或转换的来源对象。
     * @param queryTimeout 查询允许持续的最长等待时间。
     * @return 本次操作返回的SQL会话模板结果。
     */
    public static SqlSessionTemplate create(DataSource source, Duration queryTimeout) {
        return new SqlSessionTemplate(factory(source, queryTimeout));
    }

    /**
     * 计算或取得本方法声明的结果，供当前MyBatisSessions处理步骤使用。
     *
     * @param source 待解析或转换的来源对象。
     * @param queryTimeout 查询允许持续的最长等待时间。
     * @return 本次操作返回的SQL会话工厂结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static SqlSessionFactory factory(DataSource source, Duration queryTimeout) {
        try {
            var configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setJdbcTypeForNull(JdbcType.NULL);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            // 普通事件写入与租约查询使用同一预算，避免终态通知被无期限 SQL 阻塞。
            int timeoutSeconds =
                    (int)
                            Math.min(
                                    Integer.MAX_VALUE,
                                    Math.max(1, (queryTimeout.toMillis() + 999) / 1000));
            configuration.setDefaultStatementTimeout(timeoutSeconds);
            var variables = new Properties();
            variables.setProperty("leaseQueryTimeoutSeconds", Integer.toString(timeoutSeconds));
            configuration.setVariables(variables);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mapper/*Mapper.xml"));
            return factory.getObject();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot initialize Agent MyBatis mappings", error);
        }
    }
}
