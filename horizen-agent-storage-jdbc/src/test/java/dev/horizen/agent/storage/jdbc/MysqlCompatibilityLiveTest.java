package dev.horizen.agent.storage.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.execution.turn.StartTurnResult;
import dev.horizen.agent.execution.turn.TransitionTurnCommand;
import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.storage.jdbc.repository.session.JdbcSessionTurnStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

/**
 * 真实 MySQL 协议兼容性测试；默认跳过。
 */
@EnabledIfSystemProperty(named = "horizen.mysql.live", matches = "true")
class MysqlCompatibilityLiveTest {

    @Test
    void migratesAndCommitsTurnLifecycleOnMysql() {
        String url = System.getProperty("horizen.mysql.url");
        String username = System.getProperty("horizen.mysql.username", "root");
        String password = System.getProperty("horizen.mysql.password", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        JdbcSessionTurnStore store = new JdbcSessionTurnStore(dataSource);
        Instant now = Instant.parse("2026-09-24T01:00:00Z");

        StartTurnResult started =
                store.startTurn(
                        new StartTurnCommand(
                                new ExecutionIdentity("mysql-owner", "mysql-user"),
                                "mysql-session",
                                "mysql-turn",
                                "mysql-request",
                                "mysql-instance",
                                "mysql-user-message",
                                "真实 MySQL",
                                now,
                                now.plusSeconds(600),
                                now.plusSeconds(30)));
        assertEquals(StartTurnResult.Outcome.STARTED, started.getOutcome());

        var completed =
                store.transitionTurn(
                        new TransitionTurnCommand(
                                "mysql-owner",
                                "mysql-session",
                                "mysql-turn",
                                TurnStatus.COMPLETED,
                                null,
                                null,
                                now.plusSeconds(1),
                                null,
                                "mysql-assistant-message",
                                "执行完成"));

        assertEquals(TurnStatus.COMPLETED, completed.getTurn().getStatus());
        assertNull(
                store.findSession("mysql-owner", "mysql-session").orElseThrow().getActiveTurnId());
        assertEquals(2, store.listFinalMessages("mysql-owner", "mysql-session").size());
    }
}
