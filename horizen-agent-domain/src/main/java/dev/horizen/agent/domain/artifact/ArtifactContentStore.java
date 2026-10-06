package dev.horizen.agent.domain.artifact;

import java.net.URI;

/**
 * Artifact 内容的可替换云端存储接口。
 */
public interface ArtifactContentStore {
    /**
     * 写入产物正文存储。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的产物正文结果。
     */
    ArtifactContent put(ArtifactContentWrite request);

    /**
     * 读取产物正文存储。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     * @return 本次处理取得或生成的内容字节。
     */
    byte[] get(String contentRef);

    /**
     * 创建下载地址。正数表示秒数；-1 表示由 Provider 生成不设过期时间的地址。
     */
    URI createDownloadUrl(String contentRef, int expiresInSeconds);

    /**
     * 删除产物正文存储。
     *
     * @param contentRef 内容存储引用；它定位实际字节内容，不等同于临时下载 URL。
     */
    void delete(String contentRef);
}
