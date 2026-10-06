package dev.horizen.agent.runtime.api;

import lombok.Getter;

import java.net.URI;

/**
 * 当前模型请求使用的临时 BOS URL；持久化历史仅保存 artifactId。
 */
@Getter
public final class AgentInputAttachment {
    /**
     * 产物资源标识；访问内容时仍需校验所属隔离范围。
     */
    private final String artifactId;

    /**
     * 当前Agent输入附件的可读标题，供宿主界面展示。
     */
    private final String title;

    /**
     * 内容的 MIME 媒体类型，供传输、展示与解析策略选择使用。
     */
    private final String mediaType;

    /**
     * 资源或远端接口地址；具体访问范围由所属服务的配置校验。
     */
    private final String url;

    /**
     * 创建Agent输入附件，初始化该组件所需的状态、配置或依赖。
     *
     * @param artifactId 产物资源标识；访问内容时仍需校验所属隔离范围。
     * @param title      当前Agent输入附件的可读标题，供宿主界面展示。
     * @param mediaType  当前Agent输入附件使用的媒体类型，供其处理与状态记录使用。
     * @param url        资源或远端接口地址；具体访问范围由所属服务的配置校验。
     */
    public AgentInputAttachment(String artifactId, String title, String mediaType, String url) {
        this.artifactId = require(artifactId, "artifactId");
        this.title = require(title, "title");
        this.mediaType = require(mediaType, "mediaType");
        this.url = requireHttpsUrl(url);
    }

    /**
     * 取得并校验Agent输入附件。
     *
     * @param value 待校验、转换或保存的原始值。
     * @param name  需要定位或处理的名称。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String require(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    /**
     * 取得并校验HttpsURL。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String requireHttpsUrl(String value) {
        String url = require(value, "url");
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("attachment URL is invalid", error);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
            throw new IllegalArgumentException("attachment URL must be https");
        }
        return url;
    }
}
