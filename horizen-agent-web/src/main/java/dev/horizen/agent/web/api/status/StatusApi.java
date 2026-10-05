package dev.horizen.agent.web.api.status;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 此 API 功能域的 HTTP/SSE 传输模型。 */
public final class StatusApi {
    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private StatusApi() {}

    /** 宿主配置与可选集成的状态投影；就绪标记不替代远端服务健康探测。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentStatus {
        /** 脚本模型已启用或远端模型凭据已配置；这是配置就绪标记，不是远端服务健康探测结果。 */
        private boolean ready;

        /** 模型服务识别的模型名称，脚本模式使用对应的演示名称。 */
        private String modelName;

        /** 当前模型配置的基础 URL，脚本模式也保留该配置值。 */
        private String baseUrl;

        /** 配置就绪状态的可读说明，供测试工作台提示下一步操作。 */
        private String message;

        /** 外部工具网关的配置状态。 */
        private GatewayStatus gateway;

        /** 观测上报器的启用状态与本实例累计统计。 */
        private TracingStatus tracing;

        /** 最近成功取得的工作区发布所映射的 Skill 发布状态。 */
        private SkillReleaseStatus skillRelease;

        /** 远程沙箱的启用状态、编码协议与隔离范围。 */
        private SandboxStatus sandbox;

        /** 完整工作区发布的启用状态与最近成功取得的版本。 */
        private WorkspaceReleaseStatus workspaceRelease;
    }

    /** 完整工作区发布的状态摘要，展示最近成功取得的版本及准备失败。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkspaceReleaseStatus {
        /** 宿主是否启用完整工作区发布流程。 */
        private boolean enabled;

        /** 最近成功校验并取得的工作区发布序号；尚未取得发布时为空。 */
        private Long releaseNo;

        /** 最近成功取得的工作区发布内容哈希；尚未取得发布时为空。 */
        private String releaseHash;

        /** 发布服务最近一次失败的异常类型摘要；成功取得发布后清空。 */
        private String lastFailure;
    }

    /** 外部工具网关的基础配置状态，区分远端调用与本地样本模式。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GatewayStatus {
        /** 网关是否满足所选模式的基础配置条件，不表示外部工具已获得业务授权。 */
        private boolean configured;

        /** 网关模式的协议文本，例如 remote 或 mock，用于区分真实网关与本地样本。 */
        private String mode;
    }

    /** 观测上报器的状态和本实例累计计数；计数单位以批次包或重试尝试为准。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TracingStatus {
        /** 观测批量上报器是否已装配；不保证远端上报一定成功。 */
        private boolean enabled;

        /** 是否采集消息与工具正文；关闭时仅保留必要的运行元数据。 */
        private boolean captureContent;

        /** 当前上报器累计执行的额外重试次数，上报器未启用时为 0。 */
        private long retried;

        /** 当前上报器累计成功发送的 Trace 批次包数量，未启用时为 0。 */
        private long uploaded;

        /** 当前上报器累计记录的发送失败或处理失败次数，未启用时为 0。 */
        private long failed;

        /** 累计丢弃的批次包数量，包括队列容量、关闭或取消等待导致的丢弃，未启用时为 0。 */
        private long dropped;

        /** 最近上报失败的安全诊断摘要，不包含请求头或响应正文；成功上报后清空。 */
        private String lastFailure;
    }

    /** 最近成功取得的工作区发布所映射的 Skill 发布摘要。未取得发布时标识与哈希为空、数量为 0。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillReleaseStatus {
        /** 是否已装配工作区发布服务；不代表已成功取得可用发布。 */
        private boolean enabled;

        /** 当前成功取得的 Skill 发布记录标识；由工作区发布映射而来，尚未取得发布时为空。 */
        private Long releaseId;

        /** 当前 Skill 发布序号，用于版本展示；它与发布记录标识不是同一个字段，未取得发布时为空。 */
        private Long releaseNo;

        /** 当前 Skill 发布的内容哈希，用于标识已校验的发布内容；未取得发布时为空。 */
        private String releaseHash;

        /** 当前发布包含的 Skill 数量；未取得发布时为 0，不能仅据此判断发布是否可用。 */
        private int skillCount;

        /** 发布获取或绑定最近一次失败的异常类型摘要；成功取得发布后清空，未记录失败时为空。 */
        private String lastFailure;
    }

    /** 沙箱适配器的配置摘要，不代表当前已经启动了一个沙箱。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SandboxStatus {
        /** 宿主是否启用 E2B 远程沙箱适配器。 */
        private boolean enabled;

        /** 当前沙箱提供方标识，此宿主使用 e2b。 */
        private String provider;

        /** 管理与命令接口的编码方式，此宿主使用 json-sync。 */
        private String codec;

        /** 传给沙箱提供方的隔离范围，以小写协议文本返回。 */
        private String isolationScope;
    }
}
