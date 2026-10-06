package dev.horizen.agent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 本地 Web 宿主的 Spring Boot 启动入口，装配 Agent 执行、HTTP 与 SSE 接口。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class AgentWebApplication {
    /**
     * 完成当前操作的main步骤，按实现更新相应状态或依赖。
     *
     * @param args 当前AgentWeb应用持有的参数集合对象，供相应处理步骤使用。
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentWebApplication.class, args);
    }
}
