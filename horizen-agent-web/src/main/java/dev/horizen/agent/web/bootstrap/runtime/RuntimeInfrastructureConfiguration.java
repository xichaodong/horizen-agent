package dev.horizen.agent.web.bootstrap.runtime;

import dev.horizen.agent.application.workspace.CloudMemoryService;
import dev.horizen.agent.provider.spi.gateway.GatewayBackend;
import dev.horizen.agent.web.bootstrap.model.CancellableModelHttpTransport;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.AgentWorkspaceProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.HorizenProperties;

import io.agentscope.core.model.transport.HttpTransportConfig;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 组装运行时共享的 HTTP、工具网关与沙箱依赖，并由 Spring 管理关闭。
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeInfrastructureConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的Cancellable模型HTTP传输结果。
     */
    @Bean(destroyMethod = "close")
    CancellableModelHttpTransport modelTransport(AgentProperties properties) {
        var config = HttpTransportConfig.builder().readTimeout(properties.getIdleTimeout()).build();
        var client =
                new Builder()
                        .connectTimeout(config.getConnectTimeout())
                        .readTimeout(config.getReadTimeout())
                        .writeTimeout(config.getWriteTimeout())
                        .build();
        return new CancellableModelHttpTransport(client, config);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的网关后端结果。
     */
    @Bean
    GatewayBackend businessGateway(GatewayProperties properties) {
        return AgentToolRegistry.createGateway(properties);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @return 本次操作返回的HTTP客户端结果。
     */
    @Bean("webExtractHttpClient")
    HttpClient webExtractClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的HTTP客户端结果。
     */
    @Bean("publicationHttpClient")
    HttpClient publicationClient(AgentWorkspaceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的HTTP客户端结果。
     */
    @Bean("traceHttpClient")
    HttpClient traceClient(HorizenProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @return 本次操作返回的线程连接池任务执行方结果。
     */
    @Bean("publicationBodyReaders")
    ThreadPoolTaskExecutor publicationBodyReaders() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("publication-body-reader-");
        executor.setDaemon(true);
        executor.setAwaitTerminationSeconds(3);
        return executor;
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @return 本次操作返回的OkHTTP客户端结果。
     */
    @Bean("sandboxHttpClient")
    OkHttpClient sandboxClient() {
        return new Builder().connectionPool(new ConnectionPool(16, 2, TimeUnit.MINUTES)).build();
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @return 本次操作返回的沙箱HTTP资源集合结果。
     */
    @Bean(destroyMethod = "close")
    SandboxHttpResources sandboxResources(@Qualifier("sandboxHttpClient") OkHttpClient client) {
        return new SandboxHttpResources(client);
    }

    /**
     * 计算或取得本方法声明的结果，供当前RuntimeInfrastructureConfiguration处理步骤使用。
     *
     * @param storage 当前运行时基础设施组装持有的存储对象，供相应处理步骤使用。
     * @return 本次操作返回的云端记忆服务结果。
     */
    @Bean
    CloudMemoryService cloudMemory(ObjectProvider<RuntimeStorage> storage) {
        var data = storage.getIfAvailable();
        return data == null ? null : new CloudMemoryService(data.getWorkspaceDocuments());
    }

    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param gateway        外部工具目录与调用的网关适配器。
     * @param web            提供Web能力的依赖，具体实现由当前组件的组装方传入。
     * @param sandbox        提供沙箱能力的依赖，具体实现由当前组件的组装方传入。
     * @param memory         当前运行时基础设施组装持有的记忆对象，供相应处理步骤使用。
     * @param modelTransport 当前运行时基础设施组装持有的模型传输对象，供相应处理步骤使用。
     * @return 本次操作返回的运行时基础设施结果。
     */
    @Bean
    @DependsOn("sandboxResources")
    RuntimeInfrastructure runtimeInfrastructure(
            ObjectProvider<GatewayBackend> gateway,
            @Qualifier("webExtractHttpClient") HttpClient web,
            @Qualifier("sandboxHttpClient") OkHttpClient sandbox,
            ObjectProvider<CloudMemoryService> memory,
            CancellableModelHttpTransport modelTransport) {
        return new RuntimeInfrastructure(
                gateway.getIfAvailable(), web, sandbox, memory.getIfAvailable(), modelTransport);
    }

    /**
     * 运行时基础设施组装内部的沙箱HTTP资源集合，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    static final class SandboxHttpResources implements AutoCloseable {
        /**
         * 当前适配器使用的远端客户端，供实际网络或服务请求使用。
         */
        private final OkHttpClient client;

        /**
         * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
         */
        public void close() {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
