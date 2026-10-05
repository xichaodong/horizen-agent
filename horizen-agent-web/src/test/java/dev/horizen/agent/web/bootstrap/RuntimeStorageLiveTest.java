package dev.horizen.agent.web.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.horizen.agent.domain.artifact.ArtifactContent;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactContentWrite;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactPublicationRequest;
import dev.horizen.agent.domain.artifact.ArtifactReferenceRole;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.execution.turn.StartTurnCommand;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.interaction.approval.ApprovalDecisionCommand;
import dev.horizen.agent.interaction.approval.ApprovalDecisionResult;
import dev.horizen.agent.interaction.approval.ApprovalRequest;
import dev.horizen.agent.interaction.approval.ApprovalStatus;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.storage.memory.InMemoryWorkspaceContentRepository;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.config.TurnEventProperties;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EnabledIfSystemProperty(named = "horizen.redis.live", matches = "true")
class RuntimeStorageLiveTest {

    @Test
    void mysqlFactsAndRedisEventsFormOneDistributedRuntimeBoundary() {
        String database = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        String redisPrefix = "horizen-web-test:" + UUID.randomUUID() + ":";
        String jdbcUrl =
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        RuntimeStorageProperties firstProperties = properties(jdbcUrl, "instance-a", redisPrefix);
        RuntimeStorageProperties secondProperties = properties(jdbcUrl, "instance-b", redisPrefix);

        try (RuntimeStorage first =
                        RuntimeStorage.open(
                                firstProperties,
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RuntimeStorage second =
                        RuntimeStorage.open(
                                secondProperties,
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RedisTurnEventBridge firstEvents = new RedisTurnEventBridge(first.messageBus());
                RedisTurnEventBridge secondEvents = new RedisTurnEventBridge(second.messageBus())) {
            Instant now = Instant.parse("2026-09-24T01:00:00Z");
            first.getSessionTurns()
                    .startTurn(
                            new StartTurnCommand(
                                    new ExecutionIdentity("owner", "actor"),
                                    "session",
                                    "turn",
                                    "request",
                                    first.getInstanceId(),
                                    "message-user",
                                    "你好",
                                    now,
                                    now.plusSeconds(60),
                                    now.plusSeconds(30)));
            assertEquals(
                    "turn",
                    second.getSessionTurns()
                            .findLatestTurn("owner", "session")
                            .orElseThrow()
                            .getTurnId());

            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TURN_STARTED, null), 1L);
            AgentRuntimeEvent child = event(AgentRuntimeEvent.Type.SUBAGENT_STARTED, null);
            child.setSource("session/worker");
            child.setTaskId("task_child");
            child.setParentSessionId("session");
            child.setAgentId("worker");
            child.setDepth(1);
            firstEvents.publish("owner", child, 2L);
            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TEXT_DELTA, "正在分析"), 3L);
            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TURN_COMPLETED, "完整回答"), 4L);

            List<AgentRuntimeEvent> replay =
                    secondEvents.replay("owner", "turn").collectList().block(Duration.ofSeconds(3));
            assertEquals(
                    List.of(
                            AgentRuntimeEvent.Type.TURN_STARTED,
                            AgentRuntimeEvent.Type.SUBAGENT_STARTED,
                            AgentRuntimeEvent.Type.TEXT_DELTA,
                            AgentRuntimeEvent.Type.TURN_COMPLETED),
                    replay.stream().map(AgentRuntimeEvent::getType).toList());
            AgentRuntimeEvent restoredChild = replay.get(1);
            assertEquals("session/worker", restoredChild.getSource());
            assertEquals("task_child", restoredChild.getTaskId());
            assertEquals("session", restoredChild.getParentSessionId());
            assertEquals("worker", restoredChild.getAgentId());
            assertEquals(1, restoredChild.getDepth());

            List<AgentRuntimeEvent> afterSecond =
                    secondEvents
                            .replay("owner", "turn", 2L)
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(
                    List.of(
                            AgentRuntimeEvent.Type.TEXT_DELTA,
                            AgentRuntimeEvent.Type.TURN_COMPLETED),
                    afterSecond.stream().map(AgentRuntimeEvent::getType).toList());

            second.messageBus()
                    .queuePush(
                            "horizen:control:instance-a",
                            Map.of("type", "cancel", "turnId", "turn"))
                    .block();
            assertEquals(
                    "cancel",
                    first.messageBus()
                            .queueDrain("horizen:control:instance-a", 10)
                            .block()
                            .get(0)
                            .payload()
                            .get("type"));
        }
    }

    @Test
    void restoresProducedTextThenReplaysOnlyNewTextAcrossInstances() {
        String database = "stream_resume_" + UUID.randomUUID().toString().replace("-", "");
        String redisPrefix = "horizen-web-test:" + UUID.randomUUID() + ":";
        String jdbcUrl =
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));

        try (RuntimeStorage first =
                        RuntimeStorage.open(
                                properties(jdbcUrl, "stream-writer", redisPrefix),
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RuntimeStorage second =
                        RuntimeStorage.open(
                                properties(jdbcUrl, "stream-reader", redisPrefix),
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RedisTurnEventBridge firstEvents = new RedisTurnEventBridge(first.messageBus());
                RedisTurnEventBridge secondEvents = new RedisTurnEventBridge(second.messageBus())) {
            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TURN_STARTED, null));
            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TEXT_DELTA, "第一段，"));

            RedisTurnEventBridge.EventSnapshot snapshot = secondEvents.snapshot("owner", "turn");
            assertEquals(2L, snapshot.getLastSequence());
            assertEquals(
                    List.of(AgentRuntimeEvent.Type.TURN_STARTED, AgentRuntimeEvent.Type.TEXT_DELTA),
                    snapshot.getEvents().stream().map(AgentRuntimeEvent::getType).toList());
            assertEquals("第一段，", snapshot.getEvents().get(1).getText());

            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TEXT_DELTA, "第二段，"));
            firstEvents.publish("owner", event(AgentRuntimeEvent.Type.TURN_COMPLETED, "第一段，第二段。"));

            List<AgentRuntimeEvent> resumed =
                    secondEvents
                            .replay("owner", "turn", snapshot.getLastSequence())
                            .collectList()
                            .block(Duration.ofSeconds(3));
            assertEquals(
                    List.of(
                            AgentRuntimeEvent.Type.TEXT_DELTA,
                            AgentRuntimeEvent.Type.TURN_COMPLETED),
                    resumed.stream().map(AgentRuntimeEvent::getType).toList());
            assertEquals("第二段，", resumed.get(0).getText());
            assertEquals("第一段，第二段。", resumed.get(1).getText());
            assertEquals(3L, resumed.get(0).getStreamSequence());
            assertEquals(4L, resumed.get(1).getStreamSequence());
        }
    }

    @Test
    void stopsPollingWhenRunningTurnEventLogIsMissing() {
        String database = "missing_log_" + UUID.randomUUID().toString().replace("-", "");
        String redisPrefix = "horizen-web-test:" + UUID.randomUUID() + ":";
        String jdbcUrl =
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        TurnEventProperties eventProperties = new TurnEventProperties();
        eventProperties.setPollInterval(Duration.ofMillis(10));
        eventProperties.setMaxPollInterval(Duration.ofMillis(10));
        eventProperties.setMissingLogEmptyReads(1);

        try (RuntimeStorage storage =
                        RuntimeStorage.open(
                                properties(jdbcUrl, "reader", redisPrefix),
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RedisTurnEventBridge events =
                        new RedisTurnEventBridge(storage.messageBus(), eventProperties)) {
            assertThrows(
                    RedisTurnEventBridge.EventLogUnavailableException.class,
                    () ->
                            events.replay("owner", "missing-turn")
                                    .collectList()
                                    .block(Duration.ofSeconds(1)));
        }
    }

    @Test
    void persistsArtifactAskUserAndApprovalAcrossRuntimeInstances() {
        String database = "capability_" + UUID.randomUUID().toString().replace("-", "");
        String redisPrefix = "horizen-web-test:" + UUID.randomUUID() + ":";
        String jdbcUrl =
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        new ResourceDatabasePopulator(new ClassPathResource("schema/mysql.sql"))
                .execute(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        Instant now = Instant.parse("2026-09-25T01:00:00Z");
        MemoryContentStore contents = new MemoryContentStore();

        try (RuntimeStorage writer =
                        RuntimeStorage.open(
                                properties(jdbcUrl, "writer", redisPrefix),
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"));
                RuntimeStorage reader =
                        RuntimeStorage.open(
                                properties(jdbcUrl, "reader", redisPrefix),
                                InMemoryWorkspaceContentRepository.shared(
                                        "runtime-storage-tests"))) {
            ArtifactLifecycleService artifacts =
                    new ArtifactLifecycleService(writer.getArtifacts(), contents);
            var uploaded =
                    artifacts.uploadUserFile(
                            "owner",
                            "source.xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "source-workbook".getBytes(StandardCharsets.UTF_8),
                            now);
            var output =
                    artifacts.publishFile(
                            new ArtifactPublicationRequest(
                                    "owner",
                                    "session",
                                    "turn",
                                    "tool-call:edit-workbook",
                                    "edited.xlsx",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "edited-workbook".getBytes(StandardCharsets.UTF_8),
                                    uploaded.getArtifactId(),
                                    null,
                                    now));

            assertEquals(
                    ArtifactState.READY,
                    reader.getArtifacts()
                            .find("owner", output.getArtifactId())
                            .orElseThrow()
                            .getState());
            assertEquals(
                    uploaded.getArtifactId(),
                    reader.getArtifacts()
                            .find("owner", output.getArtifactId())
                            .orElseThrow()
                            .getParentArtifactId());
            assertEquals(
                    ArtifactReferenceRole.OUTPUT,
                    reader.getArtifacts()
                            .listReferences("owner", output.getArtifactId())
                            .get(0)
                            .getRole());
            assertArrayEquals(
                    "edited-workbook".getBytes(StandardCharsets.UTF_8),
                    contents.get(output.getContentRef()));

            AskUserRequest question =
                    writer.getAskUsers()
                            .createOrFind(
                                    new AskUserRequest(
                                            "owner",
                                            "session",
                                            "turn",
                                            "ask-period",
                                            null,
                                            "tool-ask-period",
                                            "[{\"questionId\":\"period\",\"type\":\"single\",\"required\":true}]",
                                            "[]",
                                            AskUserStatus.PENDING,
                                            now,
                                            now.plusSeconds(1800),
                                            null,
                                            0));
            assertEquals(
                    "ask-period",
                    reader.getAskUsers()
                            .findPending("owner", "session", "turn")
                            .get(0)
                            .getAskUserId());
            AskUserRequest answered =
                    reader.getAskUsers()
                            .resolve(
                                    "owner",
                                    question.getAskUserId(),
                                    AskUserStatus.ANSWERED,
                                    "[{\"questionId\":\"period\",\"selectedOptionIds\":[\"7d\"]}]",
                                    now.plusSeconds(1),
                                    question.getVersion());
            assertEquals(AskUserStatus.ANSWERED, answered.getStatus());

            writer.getApprovals()
                    .createPending(
                            List.of(
                                    new ApprovalRequest(
                                            "owner",
                                            "session",
                                            "turn",
                                            "approval-publish",
                                            "reply-1",
                                            "tool-publish",
                                            "publish_file",
                                            "{}",
                                            "{}",
                                            ApprovalStatus.PENDING,
                                            "actor",
                                            now.plusSeconds(1800),
                                            null,
                                            null,
                                            now,
                                            now,
                                            0)));
            var decision =
                    reader.getApprovals()
                            .decide(
                                    new ApprovalDecisionCommand(
                                            "owner",
                                            "approval-publish",
                                            true,
                                            "actor",
                                            now.plusSeconds(2)));
            assertEquals(ApprovalDecisionResult.Outcome.UPDATED, decision.getOutcome());
            assertEquals(ApprovalStatus.APPROVED, decision.getApproval().getStatus());

            writer.getPresentations()
                    .createOrFind(
                            new PresentationRecord(
                                    "owner",
                                    "session",
                                    "turn",
                                    "tool-diagnose",
                                    "diagnose",
                                    new PresentationBlock(
                                            "ui-live", "conclusion", 1, 0, Map.of("title", "诊断完成")),
                                    now));
            assertEquals(
                    "诊断完成",
                    reader.getPresentations()
                            .listForSession("owner", "session")
                            .get(0)
                            .getBlock()
                            .getData()
                            .get("title"));
        }
    }

    private static RuntimeStorageProperties properties(String jdbcUrl, String instanceId) {
        return properties(jdbcUrl, instanceId, "horizen-web-test:" + UUID.randomUUID() + ":");
    }

    private static RuntimeStorageProperties properties(
            String jdbcUrl, String instanceId, String redisPrefix) {
        return new RuntimeStorageProperties(
                RuntimeStorageProperties.Mode.DISTRIBUTED,
                jdbcUrl,
                "sa",
                "",
                4,
                4,
                System.getProperty(
                        "horizen.redis.url",
                        System.getProperty("horizen.redis.url", "redis://127.0.0.1:6379")),
                redisPrefix,
                instanceId,
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofMillis(100));
    }

    private static AgentRuntimeEvent event(AgentRuntimeEvent.Type type, String text) {
        return new AgentRuntimeEvent(
                type,
                "turn",
                "session",
                "id-" + type,
                type.name(),
                text,
                type == AgentRuntimeEvent.Type.TURN_COMPLETED ? "success" : "running",
                null,
                null,
                null,
                null);
    }

    private static final class MemoryContentStore implements ArtifactContentStore {
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();

        @Override
        public ArtifactContent put(ArtifactContentWrite request) {
            String ref = "memory/" + request.getArtifactId();
            byte[] bytes = request.content();
            values.put(ref, bytes);
            return new ArtifactContent(ref, bytes.length, sha256(bytes));
        }

        @Override
        public byte[] get(String contentRef) {
            return values.get(contentRef).clone();
        }

        @Override
        public URI createDownloadUrl(String contentRef, int expiresInSeconds) {
            return URI.create("memory://artifact/" + contentRef);
        }

        @Override
        public void delete(String contentRef) {
            values.remove(contentRef);
        }

        private static String sha256(byte[] value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
                StringBuilder hex = new StringBuilder();
                for (byte item : digest) hex.append(String.format("%02x", item));
                return hex.toString();
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
