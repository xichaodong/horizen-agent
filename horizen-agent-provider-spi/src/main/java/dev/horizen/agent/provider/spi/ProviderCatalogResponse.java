package dev.horizen.agent.provider.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Provider 返回的工具目录快照，供运行时注册与治理调用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCatalogResponse {
    /**
     * 协议的版本，供兼容或并发检查使用。
     */
    private int protocolVersion = ProviderProtocol.CURRENT_VERSION;

    /**
     * 目录的版本，供兼容或并发检查使用。
     */
    private String catalogVersion = "";

    /**
     * 工具集合的有序集合，保留当前组件处理或协议输出所需的顺序。
     */
    private List<ToolContract> tools = new ArrayList<>();

    /**
     * 校验当前提供方目录响应的输入与状态约束，不满足条件时拒绝继续处理。
     *
     * @return 本次操作返回的提供方目录响应结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public ProviderCatalogResponse validate() {
        ProviderCatalogRequest.requireVersion(protocolVersion);
        catalogVersion = ProviderCatalogRequest.text(catalogVersion);
        if (tools == null) tools = List.of();
        HashSet<String> names = new HashSet<>();
        for (ToolContract tool : tools) {
            if (tool == null || !names.add(tool.validate().getName())) {
                throw new IllegalArgumentException("catalog contains duplicate or null tool");
            }
        }
        tools = List.copyOf(tools);
        return this;
    }
}
