package dev.horizen.agent.web.api.artifact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 此 API 功能域的 HTTP/SSE 传输模型。 */
public final class ArtifactApi {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private ArtifactApi() {}

    /** 产物下载的接口请求，承载调用方提交的定位信息与输入。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactDownloadRequest {
        /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
        private String artifactId;

        /** 资源访问的有效时长，单位为秒。 */
        private Integer expiresInSeconds;
    }

    /** 产物的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactResponse {
        /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
        private String artifactId;

        /** 当前资源或请求类别，供生命周期、存储与呈现策略选择处理路径。 */
        private String kind;

        /** 当前记录或执行的状态，具体取值由所属领域或协议约定。 */
        private String status;

        /** 当前产物响应的可读标题，供宿主界面展示。 */
        private String title;

        /** 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。 */
        private String mediaType;

        /** 内容大小，单位为字节。 */
        private Long sizeBytes;

        /** 来源产物的标识，用于串联修改前后的版本关系。 */
        private String parentArtifactId;
    }

    /** 产物下载的接口响应，承载已完成查询或操作的结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArtifactDownloadResponse {
        /** 产物资源标识；访问内容时仍需校验所属隔离范围。 */
        private String artifactId;

        /** 资源或远端接口地址；具体访问范围由所属服务的配置校验。 */
        private String url;

        /** 资源访问的有效时长，单位为秒。 */
        private int expiresInSeconds;
    }
}
