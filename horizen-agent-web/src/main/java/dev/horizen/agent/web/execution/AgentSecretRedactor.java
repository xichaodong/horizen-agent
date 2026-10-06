package dev.horizen.agent.web.execution;

import dev.horizen.agent.web.config.AgentProperties;
import dev.horizen.agent.web.config.VisionProperties;
import dev.horizen.agent.web.config.AgentWorkspaceProperties;
import dev.horizen.agent.web.config.E2bSandboxProperties;
import dev.horizen.agent.web.config.GatewayProperties;
import dev.horizen.agent.web.config.HorizenProperties;
import dev.horizen.agent.web.config.WorkspaceStorageProperties;

import java.util.function.Function;

/**
 * 集中应用所有已配置适配器的敏感信息脱敏规则。
 */
public final class AgentSecretRedactor implements Function<String, String> {
    /**
     * 当前配置的 Agent 实例，承担模型与工具循环执行。
     */
    private final AgentProperties agent;

    /**
     * 独立视觉模型的敏感凭据，仅用于诊断脱敏。
     */
    private final VisionProperties vision;

    /**
     * 外部工具目录与调用的网关适配器。
     */
    private final GatewayProperties gateway;

    /**
     * 当前观测服务配置，用于识别需要清理的服务凭据。
     */
    private final HorizenProperties horizen;

    /**
     * 沙箱启用与隔离参数配置。
     */
    private final E2bSandboxProperties sandbox;

    /**
     * 当前工作区发布服务访问令牌，错误文本输出前需要脱敏。
     */
    private final String publicationToken;

    /**
     * 当前 Agent 使用的工作区配置或管理入口。
     */
    private final WorkspaceStorageProperties workspace;

    /**
     * 创建Agent密钥脱敏器，初始化该组件所需的状态、配置或依赖。
     *
     * @param agent        当前配置的 Agent 实例，承担模型与工具循环执行。
     * @param gateway      外部工具目录与调用的网关适配器。
     * @param horizen      当前Agent密钥脱敏器持有的Horizen对象，供相应处理步骤使用。
     * @param sandbox      当前Agent密钥脱敏器持有的沙箱对象，供相应处理步骤使用。
     * @param publications 当前Agent密钥脱敏器持有的发布集合对象，供相应处理步骤使用。
     * @param workspace    当前Agent密钥脱敏器持有的工作区对象，供相应处理步骤使用。
     * @param vision       独立视觉模型的凭据配置。
     */
    public AgentSecretRedactor(
            AgentProperties agent,
            GatewayProperties gateway,
            HorizenProperties horizen,
            E2bSandboxProperties sandbox,
            AgentWorkspaceProperties publications,
            WorkspaceStorageProperties workspace,
            VisionProperties vision) {
        this.agent = agent;
        this.vision = vision;
        this.gateway = gateway;
        this.horizen = horizen;
        this.sandbox = sandbox;
        this.publicationToken = publications.getToken();
        this.workspace = workspace;
    }

    /**
     * 应用Agent密钥脱敏器。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String apply(String value) {
        String redacted =
                vision.redact(workspace.redact(
                        sandbox.redact(horizen.redact(gateway.redact(agent.redact(value))))));
        return redacted == null || publicationToken == null || publicationToken.isBlank()
                ? redacted
                : redacted.replace(publicationToken, "***");
    }
}
