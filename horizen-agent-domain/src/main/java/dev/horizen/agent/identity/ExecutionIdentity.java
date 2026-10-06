package dev.horizen.agent.identity;

import dev.horizen.agent.common.validation.Identifiers;
import dev.horizen.agent.common.validation.Preconditions;

import lombok.Value;

import java.beans.ConstructorProperties;

/**
 * 宿主认证后提供的执行身份；ownerKey 只表示隔离边界，不承载具体业务语义。
 */
@Value
public class ExecutionIdentity {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private String ownerKey;

    /**
     * 实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    private String actorId;

    /**
     * 创建执行身份，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param actorId  实际操作方的审计标识，与数据隔离使用的 ownerKey 分开保存。
     */
    @ConstructorProperties({"ownerKey", "actorId"})
    public ExecutionIdentity(String ownerKey, String actorId) {
        ownerKey =
                Preconditions.requireText(
                        ownerKey, "ownerKey 不能为空", Identifiers.OWNER_KEY_MAX_LENGTH);
        actorId =
                Preconditions.requireText(actorId, "actorId 不能为空", Identifiers.ACTOR_ID_MAX_LENGTH);

        this.ownerKey = ownerKey;
        this.actorId = actorId;
    }
}
