package dev.horizen.agent.web.bootstrap;

import dev.horizen.agent.application.turn.TurnExecutionPolicy;
import dev.horizen.agent.application.turn.TurnRecoveryPolicy;
import dev.horizen.agent.application.turn.TurnServices;
import dev.horizen.agent.observability.AgentRuntimeEventObserver;
import dev.horizen.agent.observability.JsonlTraceSink;
import dev.horizen.agent.runtime.api.AgentRuntime;
import dev.horizen.agent.runtime.api.AgentRuntimeEvent;
import dev.horizen.agent.web.api.AgentApiMapper;
import dev.horizen.agent.web.bootstrap.storage.RuntimeStorage;
import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.LeaseRenewalProperties;
import dev.horizen.agent.web.config.RuntimeStorageProperties;
import dev.horizen.agent.web.execution.AgentTurnRequestFactory;
import dev.horizen.agent.web.stream.MessageBusTurnControlChannel;
import dev.horizen.agent.web.stream.RedisTurnEventBridge;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 在容器刷新前组装服务，后台任务在生命周期启动阶段开始执行。
 */
@Configuration(proxyBeanMethods = false)
public class TurnLifecycleConfiguration {
    /**
     * 构造并返回当前操作所需的结果对象。
     *
     * @param agent      当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @param leases     当前执行生命周期组装持有的租约集合对象，供相应处理步骤使用。
     * @param storage    当前执行生命周期组装持有的存储对象，供相应处理步骤使用。
     * @param events     当前执行或历史事件集合，供持久化、回放与观测使用。
     * @param runtime    执行 Agent 模型与工具循环的运行时接口。
     * @param requests   提供请求集合能力的依赖，具体实现由当前组件的组装方传入。
     * @param mapper     本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。
     * @param sink       当前执行生命周期组装持有的上报端对象，供相应处理步骤使用。
     * @return 本次操作返回的执行服务集合生命周期结果。
     */
    @Bean(destroyMethod = "close")
    @DependsOn({"agentRuntime", "traceSink"})
    TurnServicesLifecycle turnServicesLifecycle(
            AgentProperties agent,
            RuntimeStorageProperties properties,
            LeaseRenewalProperties leases,
            ObjectProvider<RuntimeStorage> storage,
            ObjectProvider<RedisTurnEventBridge> events,
            ObjectProvider<AgentRuntime> runtime,
            AgentTurnRequestFactory requests,
            AgentApiMapper mapper,
            ObjectProvider<JsonlTraceSink> sink) {
        var data = storage.getIfAvailable();
        var agentRuntime = runtime.getIfAvailable();
        var trace = sink.getIfAvailable();
        Optional<TurnServices> services = Optional.empty();
        if (agentRuntime != null) {
            var policy =
                    new TurnExecutionPolicy(
                            agent.getStreamTimeout(),
                            agent.getIdleTimeout(),
                            properties.getLeaseTtl(),
                            data == null ? null : data.getInstanceId());
            Consumer<AgentRuntimeEvent> observer =
                    trace == null ? null : new AgentRuntimeEventObserver(trace)::accept;
            TurnServices turns;
            if (data == null) {
                turns = TurnServices.standalone(policy, agentRuntime, requests, observer);
            } else {
                var ports =
                        new TurnServices.PersistencePorts(
                                data.getSessionTurns(),
                                data.getApprovals(),
                                data.getPresentations(),
                                data.getTimeline(),
                                events.getObject());
                turns =
                        TurnServices.distributed(
                                policy,
                                agentRuntime,
                                requests,
                                observer,
                                ports,
                                new TurnRecoveryPolicy(
                                        data.getInstanceId(),
                                        properties.getControlPollInterval(),
                                        properties.getLeaseHeartbeat()),
                                leases.toPolicy(),
                                new MessageBusTurnControlChannel(
                                        data.messageBus(), Duration.ofSeconds(5)),
                                event -> mapper.json(mapper.streamEvent(event)));
            }
            services = Optional.of(turns);
        }
        return new TurnServicesLifecycle(services);
    }
}
