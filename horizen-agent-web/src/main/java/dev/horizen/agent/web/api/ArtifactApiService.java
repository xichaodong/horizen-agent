package dev.horizen.agent.web.api;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.application.artifact.ArtifactApplicationService;
import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.api.artifact.ArtifactApi;
import dev.horizen.agent.web.bootstrap.runtime.ArtifactSupport;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Artifact 上传和签名下载用例的 HTTP 适配器。
 */
public final class ArtifactApiService {
    /**
     * 独立构造服务时的默认上传上限，单位为字节，与 Artifact 默认容量一致。
     */
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024 * 1024;

    /**
     * 当前产物接口使用的元数据、内容与交付依赖集合。
     */
    private final ArtifactSupport support;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactApplicationService artifacts;

    /**
     * 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    private final AgentApiMapper mapper;

    /**
     * 读取 MultipartFile 正文前使用的上传字节上限，由内容存储配置提供。
     */
    private final long maxUploadBytes;

    /**
     * 创建产物API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param support 当前产物API服务持有的支持对象，供相应处理步骤使用。
     * @param mapper  本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     */
    public ArtifactApiService(ArtifactSupport support, AgentApiMapper mapper) {
        this(
                support,
                mapper,
                support == null
                        ? null
                        : new ArtifactApplicationService(
                        support.getLifecycle(),
                        support.getArtifacts(),
                        support.getContents()),
                DEFAULT_MAX_UPLOAD_BYTES);
    }

    /**
     * 创建产物API服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param support   当前产物API服务持有的支持对象，供相应处理步骤使用。
     * @param mapper    本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    public ArtifactApiService(
            ArtifactSupport support, AgentApiMapper mapper, ArtifactApplicationService artifacts) {
        this(support, mapper, artifacts, DEFAULT_MAX_UPLOAD_BYTES);
    }

    /**
     * 使用宿主内容存储的字节上限创建上传与下载适配器。
     *
     * @param support        已启用的 Artifact 依赖集合；null 表示未启用。
     * @param mapper         将领域结果转换为 HTTP DTO 的映射器。
     * @param artifacts      上传和下载的应用服务。
     * @param maxUploadBytes 上传文件的最大字节数，必须为正数。
     */
    public ArtifactApiService(
            ArtifactSupport support,
            AgentApiMapper mapper,
            ArtifactApplicationService artifacts,
            long maxUploadBytes) {
        if (maxUploadBytes <= 0)
            throw new IllegalArgumentException("maxUploadBytes must be positive");
        this.support = support;
        this.mapper = mapper;
        this.artifacts = artifacts;
        this.maxUploadBytes = maxUploadBytes;
    }

    /**
     * 上传产物API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param file     当前产物API服务持有的文件对象，供相应处理步骤使用。
     * @return 本次操作返回的产物响应结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    ArtifactApi.ArtifactResponse upload(ExecutionIdentity identity, MultipartFile file) {
        requireEnabled();
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请选择要上传的文件");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过允许大小");
        }
        String title = AgentRequestValidator.artifactTitle(file.getOriginalFilename());
        String mediaType =
                file.getContentType() == null || file.getContentType().isBlank()
                        ? "application/octet-stream"
                        : file.getContentType();
        try {
            return mapper.artifact(
                    artifacts.upload(identity.getOwnerKey(), title, mediaType, file.getBytes()));
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        } catch (IOException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "读取上传文件失败");
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "上传 Artifact 失败");
        }
    }

    /**
     * 下载产物API服务。
     *
     * @param identity 可信宿主解析的执行身份，供访问范围与审计使用。
     * @param request  当前操作的请求参数。
     * @return 本次操作返回的产物下载响应结果。
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    ArtifactApi.ArtifactDownloadResponse download(
            ExecutionIdentity identity, ArtifactApi.ArtifactDownloadRequest request) {
        requireEnabled();
        if (request == null) throw new ApiException(HttpStatus.BAD_REQUEST, "请求不能为空");
        String artifactId = AgentRequestValidator.artifactId(request.getArtifactId());
        int expiresInSeconds =
                request.getExpiresInSeconds() == null ? -1 : request.getExpiresInSeconds();
        try {
            ArtifactApplicationService.Download download =
                    artifacts.download(identity.getOwnerKey(), artifactId, expiresInSeconds);
            return new ArtifactApi.ArtifactDownloadResponse(
                    download.getArtifact().getArtifactId(),
                    download.getUrl().toString(),
                    download.getExpiresInSeconds());
        } catch (ApplicationError error) {
            throw AgentApiMapper.apiError(error);
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "生成下载地址失败");
        }
    }

    /**
     * 取得并校验启用。
     *
     * @throws ApiException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private void requireEnabled() {
        if (support == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Artifact 存储未启用，请配置 BOS 和 distributed 存储");
        }
    }
}
