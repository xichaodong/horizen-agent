package dev.horizen.agent.sandbox.e2b.http;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

import lombok.Getter;
import lombok.Setter;

import okhttp3.OkHttpClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** 普通 JSON 管理接口与同步 envd 命令接口的配置。 */
public class HttpE2bSandboxClientOptions extends SandboxClientOptions {
    /** 共享的 HTTP 客户端，复用连接并应用当前传输超时配置。 */
    @Setter @Getter private OkHttpClient httpClient;

    /** 模型或服务访问凭据，只供服务端调用使用。 */
    @Setter @Getter private String apiKey;

    /** 远端沙箱管理 API 的基础地址。 */
    @Setter @Getter private String apiBaseUrl;

    /** 根据沙箱标识构造运行端访问地址的模板。 */
    @Setter @Getter private String runtimeBaseUrlPattern;

    /** 模板的标识，用于关联相应记录或执行。 */
    @Setter @Getter private String templateId;

    /** 执行工作区的根目录，用于解析任务文件与脚本路径。 */
    @Getter(onMethod_ = @Override)
    @Setter
    private String workspaceRoot = "/tmp/horizen-agent";

    /** 沙箱超时，单位为秒。 */
    @Setter @Getter private int sandboxTimeoutSeconds = 300;

    /** 连接超时，单位为秒。 */
    @Setter @Getter private int connectTimeoutSeconds = 30;

    /** 读取超时，单位为秒。 */
    @Setter @Getter private int readTimeoutSeconds = 180;

    /** 命令输出允许保留的最大字节数，超出时按执行器策略处理。 */
    @Setter @Getter private int maxOutputBytes = 512 * 1024;

    /** 最大快照的字节数，用于容量或传输限制。 */
    @Getter private long maxSnapshotBytes = 64L * 1024 * 1024;

    /** 单个工作区快照允许包含的条目数量上限。 */
    @Getter private int maxSnapshotEntries = 10000;

    /** 快照超时，单位为秒。 */
    @Getter private int snapshotTimeoutSeconds = 120;

    /** 运行环境的标识，供远端按环境组织观测数据。 */
    @Getter private Map<String, String> environment = Map.of();

    /** 与当前对象关联的附加元数据，不替代领域状态或授权校验。 */
    @Getter private Map<String, String> metadata = Map.of();

    /** refresh已发布工作区的状态标记，用于选择当前组件的处理路径。 */
    @Getter @Setter private boolean refreshPublishedWorkspace;

    /**
     * 读取类型。
     *
     * @return 本次处理生成或读取的文本。
     */
    @Override
    public String getType() {
        return "e2b-http";
    }

    /**
     * 创建客户端。
     *
     * @return 本次操作返回的沙箱客户端结果。
     */
    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new HttpE2bSandboxClient(this, null);
    }

    /**
     * 设置最大快照字节。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void setMaxSnapshotBytes(long value) {
        if (value <= 0) throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        maxSnapshotBytes = value;
    }

    /**
     * 设置最大快照条目集合。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void setMaxSnapshotEntries(int value) {
        if (value <= 0) throw new IllegalArgumentException("maxSnapshotEntries must be positive");
        maxSnapshotEntries = value;
    }

    /**
     * 设置快照超时秒。
     *
     * @param value 待校验、转换或保存的原始值。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public void setSnapshotTimeoutSeconds(int value) {
        if (value <= 0)
            throw new IllegalArgumentException("snapshotTimeoutSeconds must be positive");
        snapshotTimeoutSeconds = value;
    }

    /**
     * 设置环境。
     *
     * @param environment 环境的索引映射，供按键查找或归并当前组件的数据。
     */
    public void setEnvironment(Map<String, String> environment) {
        this.environment =
                environment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environment));
    }

    /**
     * 设置元数据。
     *
     * @param metadata 与当前对象关联的附加元数据，不替代领域状态或授权校验。
     */
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
