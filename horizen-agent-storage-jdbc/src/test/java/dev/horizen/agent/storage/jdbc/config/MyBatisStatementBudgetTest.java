package dev.horizen.agent.storage.jdbc.config;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class MyBatisStatementBudgetTest {
    @Test
    void ordinaryWriteReceivesTheConfiguredJdbcStatementTimeout() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:budget_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(source).execute("create table synthetic_budget (marker int)");
        var appliedTimeout = new AtomicInteger();
        var recorded =
                new DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection connection = super.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            Object result = invoke(connection, method, args);
                                            if (!(result instanceof PreparedStatement statement))
                                                return result;
                                            return Proxy.newProxyInstance(
                                                    PreparedStatement.class.getClassLoader(),
                                                    new Class<?>[] {PreparedStatement.class},
                                                    (ignored, statementMethod, parameters) -> {
                                                        if (statementMethod
                                                                .getName()
                                                                .equals("setQueryTimeout")) {
                                                            appliedTimeout.set(
                                                                    (Integer) parameters[0]);
                                                        }
                                                        return invoke(
                                                                statement,
                                                                statementMethod,
                                                                parameters);
                                                    });
                                        });
                    }
                };
        var factory = MyBatisSessions.factory(recorded, Duration.ofMillis(1500));
        factory.getConfiguration().addMapper(BudgetedWrite.class);
        var mapper = new SqlSessionTemplate(factory).getMapper(BudgetedWrite.class);
        assertEquals(1, mapper.write());
        assertEquals(2, appliedTimeout.get());
        assertEquals(
                2,
                factory.getConfiguration()
                        .getMappedStatement(
                                "dev.horizen.agent.storage.jdbc.mapper.SessionTurnMapper.renewLeases")
                        .getTimeout());
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    public interface BudgetedWrite {
        @Insert("insert into synthetic_budget (marker) values (1)")
        int write();
    }
}
