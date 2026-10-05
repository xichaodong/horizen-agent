package dev.horizen.agent.storage.jdbc.config;

import dev.horizen.agent.transaction.UnitOfWork;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import javax.sql.DataSource;

/** 独立执行模式下，提供与 agentTransactionManager 相同的 REQUIRED 传播和 15 秒事务边界。 */
public final class StandaloneTransactions {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private StandaloneTransactions() {}

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param source 待解析或转换的来源对象。
     * @return 本次操作返回的事务工作单元结果。
     */
    public static UnitOfWork unit(DataSource source) {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
        return new UnitOfWork() {
            /**
             * 执行匿名实现。
             *
             * @param work 需要在当前工作单元内执行的回调。
             * @return 本次操作返回的类型参数结果。
             */
            public <T> T execute(Supplier<T> work) {
                return transaction.execute(status -> work.get());
            }

            /**
             * 执行缺省结果。
             *
             * @param work 需要在当前工作单元内执行的回调。
             */
            public void executeWithoutResult(Runnable work) {
                transaction.executeWithoutResult(status -> work.run());
            }
        };
    }
}
