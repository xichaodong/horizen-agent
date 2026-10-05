package dev.horizen.agent.storage.jdbc.transaction;

import dev.horizen.agent.storage.jdbc.config.StandaloneTransactions;
import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** 由 Spring 管理的事务边界；调用者在进入事务前准备远程操作所需内容。 */
public class JdbcUnitOfWork implements UnitOfWork {
    /** 直接使用数据源构造适配器时采用的独立事务模板。 */
    private final UnitOfWork standalone;

    /** 创建JDBC工作单元关联工作，初始化该组件所需的状态、配置或依赖。 */
    public JdbcUnitOfWork() {
        standalone = null;
    }

    /**
     * 创建JDBC工作单元关联工作，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    @Deprecated
    public JdbcUnitOfWork(DataSource dataSource) {
        standalone = StandaloneTransactions.unit(dataSource);
    }

    /**
     * 执行JDBC工作单元关联工作。
     *
     * @param work 需要在当前工作单元内执行的回调。
     * @return 本次操作返回的类型参数结果。
     */
    @Override
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return standalone == null ? work.get() : standalone.execute(work);
    }

    /**
     * 执行缺省结果。
     *
     * @param work 需要在当前工作单元内执行的回调。
     */
    @Override
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    public void executeWithoutResult(Runnable work) {
        Objects.requireNonNull(work, "work");
        if (standalone == null) work.run();
        else standalone.executeWithoutResult(work);
    }
}
