package dev.horizen.agent.storage.bos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.horizen.agent.domain.artifact.ArtifactContentWrite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 真实 BOS 验证；只在显式传入凭证并开启开关时运行。
 */
@EnabledIfSystemProperty(named = "horizen.bos.live", matches = "true")
class BosArtifactContentStoreLiveTest {
    @Test
    void uploadsReadsSignsAndDeletesAnArtifactObject() throws Exception {
        String accessKey = env("HORIZEN_BOS_ACCESS_KEY");
        String secretKey = env("HORIZEN_BOS_SECRET_KEY");
        String endpoint = env("HORIZEN_BOS_ENDPOINT");
        String bucket = env("HORIZEN_BOS_BUCKET");
        String prefix = env("HORIZEN_BOS_PREFIX");
        Assumptions.assumeTrue(
                accessKey != null
                        && secretKey != null
                        && endpoint != null
                        && bucket != null
                        && prefix != null);

        BosArtifactContentStore store =
                new BosArtifactContentStore(
                        new BosArtifactContentStoreConfig(
                                endpoint, bucket, accessKey, secretKey, prefix, 1024 * 1024));
        String artifactId = "live-" + UUID.randomUUID().toString().replace("-", "");
        byte[] content = ("horizen-bos-live-" + artifactId).getBytes(StandardCharsets.UTF_8);
        var stored =
                store.put(
                        new ArtifactContentWrite(
                                "live-owner", artifactId, content, "text/plain; charset=utf-8"));
        try {
            assertArrayEquals(content, store.get(stored.getContentRef()));
            URI url = store.createDownloadUrl(stored.getContentRef(), 300);
            assertNotNull(url.getHost());
            probeNegativeExpiry(store, stored.getContentRef());
            probeCdn(stored.getContentRef());
        } finally {
            store.delete(stored.getContentRef());
        }
    }

    private static void probeCdn(String contentRef) {
        String base = env("HORIZEN_BOS_CDN_BASE_URL");
        if (base == null) {
            return;
        }
        try {
            URI url = URI.create(base.replaceAll("/+$", "") + "/" + contentRef);
            HttpResponse<Void> response =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build()
                            .send(
                                    HttpRequest.newBuilder(url)
                                            .timeout(Duration.ofSeconds(10))
                                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
            System.out.println("BOS CDN probe status=" + response.statusCode());
        } catch (Exception error) {
            System.out.println("BOS CDN probe failed=" + error.getClass().getSimpleName());
        }
    }

    private static void probeNegativeExpiry(BosArtifactContentStore store, String contentRef) {
        try {
            URI url = store.createDownloadUrl(contentRef, -1);
            HttpResponse<Void> response =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build()
                            .send(
                                    HttpRequest.newBuilder(url)
                                            .timeout(Duration.ofSeconds(10))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
            System.out.println("BOS negative-expiry probe status=" + response.statusCode());
        } catch (Exception error) {
            System.out.println(
                    "BOS negative-expiry probe failed=" + error.getClass().getSimpleName());
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
