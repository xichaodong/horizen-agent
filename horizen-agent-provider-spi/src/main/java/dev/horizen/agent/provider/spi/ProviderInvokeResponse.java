package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 工具调用结果，封装状态、消息、呈现块与产物引用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInvokeResponse {
    /**
     * 协议的版本，供兼容或并发检查使用。
     */
    private int protocolVersion = ProviderProtocol.CURRENT_VERSION;

    /**
     * 当前记录或执行的状态，具体取值由所属领域或协议约定。
     */
    private ProviderResultStatus status;

    /**
     * 安全结果的索引映射，供按键查找或归并当前组件的数据。
     */
    private Map<String, Object> safeResult = new LinkedHashMap<>();

    /**
     * 机器可识别的错误代码，用于选择对应的错误处理方式。
     */
    private String errorCode;

    /**
     * 面向调用方的错误说明文本。
     */
    private String errorMessage;

    /**
     * 面向宿主界面的结构化呈现信息，与实际执行结果分开处理。
     */
    private PresentationContract presentation;

    /**
     * 产物管理依赖或产物集合，用于引用、读取与交付资源。
     */
    private List<ArtifactReferenceContract> artifacts = new ArrayList<>();

    /**
     * 校验当前提供方调用响应的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的提供方调用响应结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ProviderInvokeResponse validate() {
        ProviderCatalogRequest.requireVersion(protocolVersion);
        if (status == null) throw new IllegalArgumentException("status is required");
        safeResult = Map.copyOf(safeResult == null ? Map.of() : safeResult);
        if (status == ProviderResultStatus.ERROR && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("error response requires errorCode");
        }
        if (presentation != null) presentation.validate();
        if (artifacts == null) artifacts = List.of();
        artifacts.forEach(ArtifactReferenceContract::validate);
        artifacts = List.copyOf(artifacts);
        return this;
    }
}
