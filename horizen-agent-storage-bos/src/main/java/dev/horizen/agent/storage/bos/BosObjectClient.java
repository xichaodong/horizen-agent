package dev.horizen.agent.storage.bos;

import java.net.URI;

/** 对 BCE SDK 的窄封装，便于替换和离线验证。 */
interface BosObjectClient {
    /**
     * 写入BOS对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @param content 当前记录或资源的正文内容；与资源标识和存储引用分开保存。
     * @param mediaType 当前BOS对象客户端使用的媒体类型，供其处理与状态记录使用。
     * @param checksumSha256 内容的 SHA-256 校验值，用于完整性校验。
     */
    void put(String bucket, String key, byte[] content, String mediaType, String checksumSha256);

    /**
     * 读取BOS对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @return 本次处理取得或生成的内容字节。
     */
    byte[] get(String bucket, String key);

    /**
     * 创建下载URL。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     * @param expiresInSeconds 资源访问的有效时长，单位为秒。
     * @return 本次操作返回的URI结果。
     */
    URI createDownloadUrl(String bucket, String key, int expiresInSeconds);

    /**
     * 删除BOS对象客户端。
     *
     * @param bucket 对象存储桶名称，限定内容对象的存储位置。
     * @param key 当前对象的查找或写入键。
     */
    void delete(String bucket, String key);
}
