package dev.horizen.agent.provider.codec;

import com.fasterxml.jackson.databind.JsonNode;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.provider.spi.ApprovalPolicy;
import dev.horizen.agent.provider.spi.ProviderCatalogResponse;
import dev.horizen.agent.provider.spi.ProviderProtocol;
import dev.horizen.agent.provider.spi.RiskLevel;
import dev.horizen.agent.provider.spi.ToolContract;
import dev.horizen.agent.provider.spi.ToolGroupContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 解码外部 Provider 工具目录 JSON，并校验版本、工具定义与扩展呈现结构。
 */
public final class ProviderCatalogCodec {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private ProviderCatalogCodec() {
    }

    /**
     * 解码提供方目录编解码器。
     *
     * @param tools 当前提供方目录编解码器持有的工具集合对象，供相应处理步骤使用。
     * @return 本次操作返回的提供方目录响应结果。
     */
    public static ProviderCatalogResponse decode(JsonNode tools) {
        List<ToolContract> result = new ArrayList<>();
        for (JsonNode entry : tools) {
            JsonNode group = entry.path("group");
            JsonNode schema =
                    entry.has("inputSchema") ? entry.get("inputSchema") : entry.get("tool_input");
            @SuppressWarnings("unchecked")
            Map<String, Object> input = JsonUtils.newMapper().convertValue(schema, Map.class);
            result.add(
                    new ToolContract(
                            entry.path("name").asText(entry.path("tool_name").asText("")),
                            entry.path("description").asText(""),
                            input,
                            entry.path("readOnly").asBoolean(false),
                            entry.path("riskLevel").asText("").isBlank()
                                    ? RiskLevel.MEDIUM
                                    : RiskLevel.fromWireValue(entry.path("riskLevel").asText()),
                            Math.max(1, entry.path("timeoutSeconds").asInt(30)),
                            entry.path("idempotent").asBoolean(false),
                            entry.path("concurrencySafe").asBoolean(true),
                            entry.path("supportsCancellation").asBoolean(false),
                            ApprovalPolicy.fromWireValue(
                                    entry.path("approvalPolicy")
                                            .asText(
                                                    entry.path("requiresApproval").asBoolean(false)
                                                            ? "required"
                                                            : "none")),
                            new ToolGroupContract(
                                    group.path("id")
                                            .asText(entry.path("groupId").asText("external")),
                                    group.path("description")
                                            .asText(entry.path("groupDescription").asText("")),
                                    group.has("activeByDefault")
                                            ? group.path("activeByDefault").asBoolean()
                                            : entry.path("groupActiveByDefault").asBoolean(true),
                                    group.path("activateOnSkill")
                                            .asText(entry.path("activateOnSkill").asText("")))));
        }
        return new ProviderCatalogResponse(ProviderProtocol.CURRENT_VERSION, "", result).validate();
    }
}
