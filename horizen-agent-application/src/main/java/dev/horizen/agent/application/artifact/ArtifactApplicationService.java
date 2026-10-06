package dev.horizen.agent.application.artifact;

import dev.horizen.agent.application.ApplicationError;
import dev.horizen.agent.domain.artifact.Artifact;
import dev.horizen.agent.domain.artifact.ArtifactContentStore;
import dev.horizen.agent.domain.artifact.ArtifactLifecycleService;
import dev.horizen.agent.domain.artifact.ArtifactState;
import dev.horizen.agent.domain.artifact.ArtifactStore;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Artifact 上传和下载用例，不依赖 HTTP 或对象存储 SDK。
 */
public final class ArtifactApplicationService {
    /**
     * 本组件调用的 {@code ArtifactLifecycleService} 依赖，负责 lifecycle 对应的处理步骤。
     */
    private final ArtifactLifecycleService lifecycle;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private final ArtifactStore artifacts;

    /**
     * 资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    private final ArtifactContentStore contents;

    /**
     * 时间来源，用于计算更新时间、过期时间或执行时限。
     */
    private final Clock clock;

    /**
     * 创建产物应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param lifecycle 提供生命周期能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     */
    public ArtifactApplicationService(
            ArtifactLifecycleService lifecycle,
            ArtifactStore artifacts,
            ArtifactContentStore contents) {
        this(lifecycle, artifacts, contents, Clock.systemUTC());
    }

    /**
     * 创建产物应用服务，初始化该组件所需的状态、配置或依赖。
     *
     * @param lifecycle 提供生命周期能力的依赖，具体实现由当前组件的组装方传入。
     * @param artifacts 产物管理依赖或产物集合，用于引用、读取与交付资源。
     * @param contents  资源内容服务或已持有的内容集合，供读取与写入实际内容使用。
     * @param clock     时间来源，用于计算更新时间、过期时间或执行时限。
     */
    public ArtifactApplicationService(
            ArtifactLifecycleService lifecycle,
            ArtifactStore artifacts,
            ArtifactContentStore contents,
            Clock clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contents = Objects.requireNonNull(contents, "contents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 在可信执行身份范围内登记并写入输入产物，返回可供后续执行引用的资源信息。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param title     当前产物应用服务的可读标题，供宿主界面展示。
     * @param mediaType 当前产物应用服务使用的媒体类型，供其处理与状态记录使用。
     * @param bytes     当前操作处理的内容字节。
     * @return 本次操作返回的产物结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Artifact upload(String ownerKey, String title, String mediaType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApplicationError(ApplicationError.Code.INVALID_ARGUMENT, "请选择要上传的文件");
        }
        return lifecycle.uploadUserFile(ownerKey, title, mediaType, bytes, Instant.now(clock));
    }

    /**
     * 核对资源访问范围与生命周期，再生成当前有效时长内可使用的下载信息。
     *
     * @param ownerKey         宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param artifactId       产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param expiresInSeconds 资源访问的有效时长，单位为秒。
     * @return 本次操作返回的下载结果。
     * @throws ApplicationError 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public Download download(String ownerKey, String artifactId, int expiresInSeconds) {
        Artifact artifact =
                artifacts
                        .find(ownerKey, artifactId)
                        .orElseThrow(
                                () ->
                                        new ApplicationError(
                                                ApplicationError.Code.NOT_FOUND, "Artifact 不存在"));
        if (artifact.getState() != ArtifactState.READY || artifact.getContentRef() == null) {
            throw new ApplicationError(ApplicationError.Code.CONFLICT, "Artifact 尚不可下载");
        }
        URI url = contents.createDownloadUrl(artifact.getContentRef(), expiresInSeconds);
        return new Download(artifact, url, expiresInSeconds);
    }

    /**
     * 产物应用服务内部的下载，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor
    @Getter
    public static final class Download {
        /**
         * 本次操作已经定位或登记的产物领域对象。
         */
        private final Artifact artifact;

        /**
         * 资源或远端接口地址；具体访问范围由所属服务的配置校验。
         */
        private final URI url;

        /**
         * 资源访问的有效时长，单位为秒。
         */
        private final int expiresInSeconds;
    }
}
