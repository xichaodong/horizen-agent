package dev.horizen.agent.web.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.horizen.agent.application.artifact.ArtifactApplicationService;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.api.artifact.ArtifactApi.ArtifactResponse;
import dev.horizen.agent.web.bootstrap.runtime.ArtifactSupport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ArtifactUploadLimitTest {
    private final ExecutionIdentity identity = new ExecutionIdentity("owner", "actor");

    @Test
    void oversizedFileIsRejectedBeforeReadingOrPersistingItsBody() throws Exception {
        var application = mock(ArtifactApplicationService.class);
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1025L);
        var service =
                new ArtifactApiService(
                        mock(ArtifactSupport.class), mock(AgentApiMapper.class), application, 1024);
        var error = assertThrows(ApiException.class, () -> service.upload(identity, file));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatus());
        verify(file, never()).getBytes();
        verifyNoInteractions(application);
    }

    @Test
    void twoMegabyteFileAtConfiguredLimitReachesArtifactUseCase() {
        byte[] content = new byte[2 * 1024 * 1024];
        var file = new MockMultipartFile("file", "report.bin", "application/octet-stream", content);
        var application = mock(ArtifactApplicationService.class);
        var artifact = mock(Artifact.class);
        var mapper = mock(AgentApiMapper.class);
        var expected = new ArtifactResponse();
        when(application.upload(
                eq("owner"),
                eq("report.bin"),
                eq("application/octet-stream"),
                any(byte[].class)))
                .thenReturn(artifact);
        when(mapper.artifact(artifact)).thenReturn(expected);
        var service =
                new ArtifactApiService(
                        mock(ArtifactSupport.class), mapper, application, content.length);
        assertSame(expected, service.upload(identity, file));
        verify(application)
                .upload(
                        eq("owner"),
                        eq("report.bin"),
                        eq("application/octet-stream"),
                        aryEq(content));
    }
}
