package dev.horizen.agent.web.bootstrap.model;

import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.ContextProperties;

import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import okhttp3.OkHttpClient.Builder;

/**
 * 从经过校验的宿主配置创建主模型和压缩模型。
 */
public final class AgentModelFactory {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private AgentModelFactory() {
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentModelFactory处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param context    当前执行上下文，提供关联标识和宿主绑定信息。
     * @return 本次操作返回的对话模型基础结果。
     */
    public static ChatModelBase primary(AgentProperties properties, ContextProperties context) {
        return primary(properties, context, StandaloneTransport.INSTANCE);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentModelFactory处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param context    当前执行上下文，提供关联标识和宿主绑定信息。
     * @param transport  当前Agent模型工厂持有的传输对象，供相应处理步骤使用。
     * @return 本次操作返回的对话模型基础结果。
     */
    public static ChatModelBase primary(
            AgentProperties properties, ContextProperties context, HttpTransport transport) {
        return properties.getModelMode() == AgentProperties.ModelMode.SCRIPTED
                ? new ScriptedWebModel()
                : openAi(
                properties.getApiKey(),
                properties.getBaseUrl(),
                properties.getModelName(),
                context.getModelContextWindowTokens(),
                transport);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentModelFactory处理步骤使用。
     *
     * @param properties   宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param context      当前执行上下文，提供关联标识和宿主绑定信息。
     * @param primaryModel 当前Agent模型工厂持有的primary模型对象，供相应处理步骤使用。
     * @return 本次操作返回的模型结果。
     */
    public static Model compaction(
            AgentProperties properties, ContextProperties context, Model primaryModel) {
        return compaction(properties, context, primaryModel, StandaloneTransport.INSTANCE);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentModelFactory处理步骤使用。
     *
     * @param properties   宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param context      当前执行上下文，提供关联标识和宿主绑定信息。
     * @param primaryModel 当前Agent模型工厂持有的primary模型对象，供相应处理步骤使用。
     * @param transport    当前Agent模型工厂持有的传输对象，供相应处理步骤使用。
     * @return 本次操作返回的模型结果。
     */
    public static Model compaction(
            AgentProperties properties,
            ContextProperties context,
            Model primaryModel,
            HttpTransport transport) {
        if (properties.getModelMode() == AgentProperties.ModelMode.SCRIPTED
                || !context.hasCompressionModel()) return primaryModel;
        String apiKey = context.getCompressionModelApiKey();
        if (apiKey == null || apiKey.isBlank()) apiKey = properties.getApiKey();
        String baseUrl = context.getCompressionModelBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = properties.getBaseUrl();
        return openAi(
                apiKey,
                baseUrl,
                context.getCompressionModelName(),
                context.getCompressionModelContextWindowTokens(),
                transport);
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentModelFactory处理步骤使用。
     *
     * @param apiKey              模型或服务访问凭据，只供服务端调用使用。
     * @param baseUrl             远端服务的基础地址，用于拼接接口路径。
     * @param modelName           模型服务识别的模型名称，脚本模式使用对应的演示名称。
     * @param contextWindowTokens 上下文窗口的 token 数量或预算。
     * @param transport           当前Agent模型工厂持有的传输对象，供相应处理步骤使用。
     * @return 本次操作返回的开启AI对话模型结果。
     */
    private static OpenAIChatModel openAi(
            String apiKey,
            String baseUrl,
            String modelName,
            int contextWindowTokens,
            HttpTransport transport) {
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .stream(true)
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport);
        if (contextWindowTokens > 0) builder.contextWindowSize(contextWindowTokens);
        return builder.build();
    }

    /**
     * Agent模型工厂内部的独立传输，封装该步骤需要的状态或输入输出。
     */
    private static final class StandaloneTransport {
        /**
         * 实例的固定取值，用于相应策略和边界判断。
         */
        private static final HttpTransport INSTANCE = create();

        /**
         * 创建独立传输。
         *
         * @return 本次操作返回的HTTP传输结果。
         */
        private static HttpTransport create() {
            var config = HttpTransportConfig.defaults();
            var client =
                    new Builder()
                            .connectTimeout(config.getConnectTimeout())
                            .readTimeout(config.getReadTimeout())
                            .writeTimeout(config.getWriteTimeout())
                            .build();
            var transport = new CancellableModelHttpTransport(client, config);
            HttpTransportFactory.register(transport);
            return transport;
        }
    }
}
