package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "horizen.local-config=",
                "horizen.agent.model-mode=scripted",
                "horizen.agent.artifact.bos.max-object-bytes=4194304"
        })
class MultipartUploadHttpTest {
    @Autowired
    private TestRestTemplate http;
    @MockitoBean
    private AgentService service;

    @Test
    void twoMegabyteMultipartUploadPassesConfiguredHttpBoundary() throws Exception {
        var result = new ArtifactResponse();
        result.setArtifactId("art_http_fixture");
        when(service.uploadArtifact(any(), any()))
                .thenAnswer(
                        invocation -> {
                            MultipartFile file = invocation.getArgument(1);
                            assertEquals(2 * 1024 * 1024, file.getSize());
                            return result;
                        });
        var response =
                http.postForEntity(
                        "/api/artifacts/upload", request(2 * 1024 * 1024), ArtifactResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("art_http_fixture", response.getBody().getArtifactId());
        verify(service).uploadArtifact(any(), any(MultipartFile.class));
    }

    @Test
    void artifactConfiguredLimitAlsoControlsMultipartParserAndReturnsClear413() {
        var response =
                http.postForEntity("/api/artifacts/upload", request(5 * 1024 * 1024), Map.class);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("上传文件超过允许大小", response.getBody().get("error"));
        verify(service, never()).uploadArtifact(any(), any(MultipartFile.class));
    }

    private static HttpEntity<?> request(int bytes) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add(
                "file",
                new ByteArrayResource(new byte[bytes]) {
                    @Override
                    public String getFilename() {
                        return "synthetic.bin";
                    }
                });
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(form, headers);
    }
}
