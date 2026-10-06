package dev.horizen.agent.web.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.beans.ConstructorProperties;

/**
 * 本地测试宿主的固定身份；生产宿主应替换 ExecutionIdentityResolver Bean。
 */
@Data
@ConfigurationProperties(prefix = "horizen.agent.identity")
public class IdentityProperties {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    private String actorId;

    /**
     * 创建身份配置，初始化该组件所需的状态、配置或依赖。
     */
    public IdentityProperties() {
        this("web-tester", "web-tester");
    }

    /**
     * 创建身份配置，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param actorId  实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    @ConstructorProperties({"ownerKey", "actorId"})
    public IdentityProperties(String ownerKey, String actorId) {
        this.ownerKey = text(ownerKey, "web-tester");
        this.actorId = text(actorId, this.ownerKey);
    }

    /**
     * 生成当前操作所需的text文本，供调用方继续处理。
     *
     * @param value    待校验、转换或保存的原始值。
     * @param fallback 当前身份配置使用的回退，供其处理与状态记录使用。
     * @return 本次处理生成或读取的文本。
     */
    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
