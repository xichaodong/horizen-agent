package dev.horizen.agent.web.identity;

import dev.horizen.agent.identity.ExecutionIdentity;
import dev.horizen.agent.web.config.IdentityProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为本地测试宿主提供默认身份解析器。 */
@Configuration
public class IdentityConfiguration {

    /**
     * 计算或取得本方法声明的结果，供当前IdentityConfiguration处理步骤使用。
     *
     * @param properties 宿主绑定的配置对象，供组件组装与策略校验使用。
     * @return 本次操作返回的执行身份解析器结果。
     */
    @Bean
    @ConditionalOnMissingBean(ExecutionIdentityResolver.class)
    ExecutionIdentityResolver fixedExecutionIdentityResolver(IdentityProperties properties) {
        ExecutionIdentity identity =
                new ExecutionIdentity(properties.getOwnerKey(), properties.getActorId());
        return request -> identity;
    }
}
