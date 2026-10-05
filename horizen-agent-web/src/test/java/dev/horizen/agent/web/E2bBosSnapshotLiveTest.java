package dev.horizen.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.adapter.agentscope.workspace.snapshot.RepositoryRemoteSnapshotClient;
import dev.horizen.agent.adapter.agentscope.workspace.snapshot.SandboxSnapshotCheckpoint;
import dev.horizen.agent.common.config.YamlConfigFiles;
import dev.horizen.agent.domain.workspace.snapshot.WorkspaceSnapshotRepository;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bFilesystemSpec;
import dev.horizen.agent.sandbox.e2b.http.HttpE2bSandboxState;
import dev.horizen.agent.storage.bos.BosArtifactContentStoreConfig;
import dev.horizen.agent.storage.bos.BosWorkspaceSnapshotRepository;
import dev.horizen.agent.storage.redis.RedisAgentRuntimeStore;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 显式启用的真实环境测试，使用已保存的配置、合成文件和唯一远程资源名称。 */
@EnabledIfSystemProperty(named = "horizen.snapshot.live", matches = "true")
class E2bBosSnapshotLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path localWorkspace;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void restoresOnANewSandboxUsingIndependentClientsAndSharedRedisAndBos() throws Exception {
        Properties e2b = load(".env.yml");
        Properties bos = load(".env.yml");
        Properties storage = load(".env.yml");
        String run = UUID.randomUUID().toString().replace("-", "");
        String agent = "snapshot-live-" + run;
        String prefix = "horizen-snapshot-live:" + run + ":";
        String objectPrefix =
                required(bos, "horizen.agent.artifact.bos.key-prefix")
                        + "/snapshot-acceptance/"
                        + run;
        BosArtifactContentStoreConfig config =
                new BosArtifactContentStoreConfig(
                        required(bos, "horizen.agent.artifact.bos.endpoint"),
                        required(bos, "horizen.agent.artifact.bos.bucket"),
                        required(bos, "horizen.agent.artifact.bos.access-key"),
                        required(bos, "horizen.agent.artifact.bos.secret-key"),
                        objectPrefix,
                        4L * 1024 * 1024);
        Set<String> objects = new LinkedHashSet<>();
        LifecycleRecorder recorder = new LifecycleRecorder();
        URI redis = URI.create(required(storage, "horizen.agent.storage.redis-url"));
        try (JedisPooled commandsA = new JedisPooled(redis);
                JedisPooled commandsB = new JedisPooled(redis);
                JedisPool poolA = new JedisPool(redis);
                JedisPool poolB = new JedisPool(redis);
                var bosA = new BosWorkspaceSnapshotRepository(config, 2, 60);
                var bosB = new BosWorkspaceSnapshotRepository(config, 2, 60)) {
            SandboxAcquireResult acquiredA = null;
            SandboxAcquireResult acquiredB = null;
            try {
                commandsA.ping();
                var a =
                        new RedisAgentRuntimeStore(
                                commandsA,
                                poolA,
                                prefix,
                                Duration.ofMinutes(10),
                                Duration.ofHours(1));
                var b =
                        new RedisAgentRuntimeStore(
                                commandsB,
                                poolB,
                                prefix,
                                Duration.ofMinutes(10),
                                Duration.ofHours(1));
                SessionSandboxStateStore stateA =
                        new SessionSandboxStateStore(a.agentStateStore(), agent);
                SessionSandboxStateStore stateB =
                        new SessionSandboxStateStore(b.agentStateStore(), agent);
                SandboxContext contextA =
                        spec(e2b, recorder, track(bosA, objects)).toSandboxContext(localWorkspace);
                SandboxContext configuredB =
                        spec(e2b, recorder, track(bosB, objects)).toSandboxContext(localWorkspace);
                // 已部署的 E2B 服务可恢复已删除 ID。此处强制模拟执行器丢失，
                // 验证标准的新建回退路径会下载 BOS 数据。
                SandboxContext contextB =
                        SandboxContext.builder()
                                .client(new ColdRecoveryClient(configuredB.getClient()))
                                .clientOptions(configuredB.getClientOptions())
                                .workspaceSpec(configuredB.getWorkspaceSpec())
                                .snapshotSpec(configuredB.getSnapshotSpec())
                                .isolationScope(configuredB.getIsolationScope())
                                .build();
                SandboxManager managerA =
                        new SandboxManager(
                                contextA.getClient(), stateA, agent, a.sandboxExecutionGuard());
                SandboxManager managerB =
                        new SandboxManager(
                                contextB.getClient(), stateB, agent, b.sandboxExecutionGuard());
                RuntimeContext callA =
                        RuntimeContext.builder().userId("owner-" + run).sessionId("turn-a").build();
                RuntimeContext callB =
                        RuntimeContext.builder().userId("owner-" + run).sessionId("turn-b").build();
                acquiredA = managerA.acquire(contextA, callA);
                acquiredA.getSandbox().start();
                String firstId =
                        ((HttpE2bSandboxState) acquiredA.getSandbox().getState()).getSandboxId();
                System.out.println("Snapshot live: A sandbox started");
                String setup =
                        "import pathlib,hashlib; p=pathlib.Path('acceptance'); p.mkdir();"
                                + " p.joinpath('report.md').write_text('turn-a-report');"
                                + " data=bytes(range(256))*1024; p.joinpath('binary.bin').write_bytes(data);"
                                + " p.joinpath('run.sh').write_text('#!/bin/sh\\n"
                                + "echo restored-script\\n"
                                + "'); p.joinpath('run.sh').chmod(0o755);"
                                + " p.joinpath('link.md').symlink_to('report.md');"
                                + " print(hashlib.sha256(data).hexdigest())";
                String digest =
                        acquiredA
                                .getSandbox()
                                .exec(callA, "python3 -c " + quote(setup), 30)
                                .stdout()
                                .strip();
                callA.put(SandboxAcquireResult.class, acquiredA);
                callA.put(SandboxContext.class, contextA);
                SandboxSnapshotCheckpoint.save(callA, a.agentStateStore(), agent);
                String snapshotId = acquiredA.getSandbox().getState().getSnapshot().getId();
                assertTrue(bosA.exists(snapshotId));
                var identity =
                        SandboxIsolationKey.resolve(IsolationScope.USER, callB, agent)
                                .orElseThrow();
                assertTrue(
                        stateB.load(identity).isPresent(),
                        "B must find A's snapshot pointer in Redis");
                acquiredA.getSandbox().shutdown();
                assertTrue(
                        recorder.deleted.contains(firstId),
                        "A sandbox must have been deleted remotely");
                acquiredA.getLease().close();
                acquiredA = null;
                System.out.println("Snapshot live: A snapshot committed; original sandbox deleted");

                acquiredB = managerB.acquire(contextB, callB);
                acquiredB.getSandbox().start();
                String secondId =
                        ((HttpE2bSandboxState) acquiredB.getSandbox().getState()).getSandboxId();
                assertNotEquals(firstId, secondId, "Recovery must use a newly created sandbox");
                assertEquals(snapshotId, acquiredB.getSandbox().getState().getSnapshot().getId());
                String verify =
                        "import pathlib,hashlib; p=pathlib.Path('acceptance'); "
                                + "assert p.joinpath('report.md').read_text()=='turn-a-report'; "
                                + "assert p.joinpath('link.md').read_text()=='turn-a-report'; "
                                + "print(hashlib.sha256(p.joinpath('binary.bin').read_bytes()).hexdigest())";
                assertEquals(
                        digest,
                        acquiredB
                                .getSandbox()
                                .exec(callB, "python3 -c " + quote(verify), 30)
                                .stdout()
                                .strip());
                assertEquals(
                        "restored-script",
                        acquiredB
                                .getSandbox()
                                .exec(callB, "./acceptance/run.sh", 30)
                                .stdout()
                                .strip());
                RuntimeContext otherOwner =
                        RuntimeContext.builder().userId("other-" + run).sessionId("turn-b").build();
                assertTrue(
                        stateB.load(
                                        SandboxIsolationKey.resolve(
                                                        IsolationScope.USER, otherOwner, agent)
                                                .orElseThrow())
                                .isEmpty());
                System.out.println(
                        "Snapshot live: B restored text, 256 KiB binary, executable permission and internal"
                                + " symlink; owner isolation passed");

                acquiredB
                        .getSandbox()
                        .exec(callB, "printf turn-b-report > acceptance/report.md", 30);
                callB.put(SandboxAcquireResult.class, acquiredB);
                callB.put(SandboxContext.class, contextB);
                SandboxSnapshotCheckpoint.save(callB, b.agentStateStore(), agent);
                assertEquals("turn-b-report", report(bosA.download(snapshotId)));
                try (InputStream oversized =
                        new ByteArrayInputStream(new byte[4 * 1024 * 1024 + 1])) {
                    assertThrows(IOException.class, () -> bosA.upload(snapshotId, oversized));
                }
                assertEquals("turn-b-report", report(bosA.download(snapshotId)));
                System.out.println(
                        "Snapshot live: B update committed; rejected oversized upload preserved previous"
                                + " snapshot");
            } finally {
                // 仅删除本次独立运行创建的 ID 和键，不扫描共享存储桶。
                if (acquiredA != null) acquiredA.getLease().close();
                if (acquiredB != null) acquiredB.getLease().close();
                try {
                    cleanupSandboxes(e2b, recorder);
                } finally {
                    try {
                        cleanupObjects(config, objects);
                    } finally {
                        cleanupRedis(commandsA, prefix);
                    }
                }
                System.out.println(
                        "Snapshot live: test sandboxes, BOS objects and Redis keys cleaned up");
            }
        }
    }

    private static String report(InputStream input) throws IOException {
        try (var tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.getName().equals("./acceptance/report.md"))
                    return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("report missing from snapshot");
    }

    private static HttpE2bFilesystemSpec spec(
            Properties properties, Interceptor recorder, WorkspaceSnapshotRepository repository) {
        var spec =
                new HttpE2bFilesystemSpec()
                        .apiKey(required(properties, "AGENT_E2B_API_KEY"))
                        .apiBaseUrl(required(properties, "AGENT_E2B_API_BASE_URL"))
                        .runtimeBaseUrlPattern(
                                required(properties, "AGENT_E2B_RUNTIME_BASE_URL_PATTERN"))
                        .templateId(required(properties, "AGENT_E2B_TEMPLATE_ID"))
                        .workspaceRoot(
                                properties.getProperty(
                                        "AGENT_E2B_WORKSPACE_ROOT", "/tmp/horizen-agent"))
                        .sandboxTimeoutSeconds(300)
                        .httpClient(
                                new OkHttpClient.Builder()
                                        .connectTimeout(15, TimeUnit.SECONDS)
                                        .readTimeout(120, TimeUnit.SECONDS)
                                        .addInterceptor(recorder)
                                        .build())
                        .snapshotSpec(
                                new RemoteSnapshotSpec(
                                        new RepositoryRemoteSnapshotClient(repository)))
                        .snapshotLimits(4L * 1024 * 1024, 1000, 120);
        spec.isolationScope(IsolationScope.USER);
        spec.workspaceProjectionEnabled(false);
        return spec;
    }

    private static WorkspaceSnapshotRepository track(
            WorkspaceSnapshotRepository repository, Set<String> ids) {
        return new WorkspaceSnapshotRepository() {
            @Override
            public void upload(String id, InputStream data) throws Exception {
                ids.add(id);
                repository.upload(id, data);
            }

            @Override
            public InputStream download(String id) throws Exception {
                return repository.download(id);
            }

            @Override
            public boolean exists(String id) throws Exception {
                return repository.exists(id);
            }
        };
    }

    private static Properties load(String file) throws IOException {
        Properties result = new Properties();
        try (var input = Files.newInputStream(Path.of("..", file))) {
            result.putAll(LiveConfiguration.aliases(YamlConfigFiles.load(input)));
        }
        return result;
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing live configuration: " + key);
        return value.strip();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void cleanupSandboxes(Properties config, LifecycleRecorder recorder)
            throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
        String base = required(config, "AGENT_E2B_API_BASE_URL").replaceAll("/+$", "");
        for (String id : recorder.created) {
            var request =
                    new Request.Builder()
                            .url(base + "/sandboxes/" + id)
                            .header("X-API-KEY", required(config, "AGENT_E2B_API_KEY"))
                            .delete()
                            .build();
            try (var response = client.newCall(request).execute()) {
                assertTrue(
                        response.isSuccessful() || response.code() == 404 || response.code() == 410,
                        "Test sandbox cleanup failed: HTTP " + response.code());
            }
        }
    }

    private static void cleanupObjects(BosArtifactContentStoreConfig config, Set<String> ids) {
        BosClientConfiguration sdk = new BosClientConfiguration();
        sdk.setEndpoint(config.getEndpoint());
        sdk.setCredentials(new DefaultBceCredentials(config.getAccessKey(), config.getSecretKey()));
        BosClient client = new BosClient(sdk);
        try {
            for (String id : ids) {
                String key = config.getKeyPrefix() + "/" + id + ".tar";
                client.deleteObject(config.getBucket(), key);
                assertFalse(
                        client.doesObjectExist(config.getBucket(), key),
                        "Test snapshot cleanup failed");
            }
        } finally {
            client.shutdown();
        }
    }

    private static void cleanupRedis(JedisPooled commands, String prefix) {
        String cursor = "0";
        do {
            var page = commands.scan(cursor, new ScanParams().match(prefix + "*").count(100));
            for (String key : page.getResult()) {
                assertTrue(key.startsWith(prefix));
                commands.del(key);
            }
            cursor = page.getCursor();
        } while (!cursor.equals("0"));
        assertTrue(
                commands.scan("0", new ScanParams().match(prefix + "*").count(1000))
                        .getResult()
                        .isEmpty());
    }

    private static final class LifecycleRecorder implements Interceptor {
        final Set<String> created = new LinkedHashSet<>();
        final Set<String> deleted = new LinkedHashSet<>();

        @Override
        public Response intercept(Chain chain) throws IOException {
            var request = chain.request();
            var response = chain.proceed(request);
            if (response.isSuccessful()
                    && request.method().equals("POST")
                    && request.url().encodedPath().equals("/sandboxes")) {
                String id =
                        JSON.readTree(response.peekBody(65536).string()).path("sandboxID").asText();
                if (!id.isBlank()) created.add(id);
            }
            if (response.isSuccessful() && request.method().equals("DELETE")) {
                deleted.add(
                        request.url().pathSegments().get(request.url().pathSegments().size() - 1));
            }
            return response;
        }
    }

    private static final class ColdRecoveryClient implements SandboxClient<SandboxClientOptions> {
        private final SandboxClient<SandboxClientOptions> delegate;

        @SuppressWarnings("unchecked")
        ColdRecoveryClient(SandboxClient<?> delegate) {
            this.delegate = (SandboxClient<SandboxClientOptions>) delegate;
        }

        @Override
        public Sandbox create(WorkspaceSpec w, SandboxSnapshotSpec s, SandboxClientOptions o) {
            return delegate.create(w, s, o);
        }

        @Override
        public Sandbox resume(SandboxState state) {
            throw new IllegalStateException("Injected executor loss for cold-recovery acceptance");
        }

        @Override
        public void delete(Sandbox sandbox) {
            delegate.delete(sandbox);
        }

        @Override
        public String serializeState(SandboxState state) {
            return delegate.serializeState(state);
        }

        @Override
        public SandboxState deserializeState(String json) {
            return delegate.deserializeState(json);
        }

        @Override
        public SandboxState deserializeState(String json, SandboxSnapshotSpec spec) {
            return delegate.deserializeState(json, spec);
        }
    }
}
