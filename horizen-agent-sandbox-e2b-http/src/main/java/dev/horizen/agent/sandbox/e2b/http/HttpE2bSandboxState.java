package dev.horizen.agent.sandbox.e2b.http;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;

import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

import lombok.Getter;
import lombok.Setter;

/**
 * HTTP E2B 后端跨调用恢复所需的状态。
 */
@JsonTypeName("e2b-http")
public final class HttpE2bSandboxState extends SandboxState {
    /**
     * 远程快照保存与恢复能力的配置。
     */
    @JsonIgnore
    @Getter(onMethod_ = @JsonIgnore)
    @Setter
    private SandboxSnapshotSpec snapshotSpec;

    /**
     * 快照已提交的状态标记，用于选择当前组件的处理路径。
     */
    @Getter
    @Setter
    private boolean snapshotCommitted;

    /**
     * 沙箱的标识，用于关联相应记录或执行。
     */
    @Getter
    @Setter
    private String sandboxId;

    /**
     * 当前访问令牌，供相应服务接口校验使用。
     */
    @Setter
    @Getter
    private String accessToken;

    /**
     * 模板的标识，用于关联相应记录或执行。
     */
    @Getter
    @Setter
    private String templateId;

    /**
     * 执行工作区的根目录，用于解析任务文件与脚本路径。
     */
    @Setter
    @Getter
    private String workspaceRoot;

    /**
     * 沙箱持有的的状态标记，用于选择当前组件的处理路径。
     */
    @Setter
    @Getter
    private boolean sandboxOwned;
}
