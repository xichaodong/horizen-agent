package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;

import io.agentscope.core.model.transport.HttpTransport;

import lombok.Value;

import okhttp3.OkHttpClient;

import java.net.http.HttpClient;

/**
 * 共享无状态的工具和传输依赖；执行状态归各 Harness 实例管理。
 */
@Value
public class RuntimeInfrastructure {
    /**
     * 外部工具目录与调用的网关适配器。
     */
    GatewayBackend gateway;

    /**
     * 抓取与解析网页正文的 HTTP 客户端。
     */
    HttpClient webExtractClient;

    /**
     * 创建与管理远程沙箱环境的客户端。
     */
    OkHttpClient sandboxClient;

    /**
     * 当前归属范围内的记忆读取或写入服务。
     */
    CloudMemoryService memory;

    /**
     * 主模型、视觉模型与压缩模型共用的可取消 HTTP 传输；模型实例和用途分别配置。
     */
    HttpTransport modelTransport;
}
