package dev.horizen.agent.adapter.agentscope.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.horizen.agent.common.digest.DigestUtils;
import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.presentation.PresentationBlock;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅提取显式展示信封，不将普通工具 JSON 猜测为界面内容。 */
public final class PresentationToolResultMapper {
    /** 当前Schema版本的固定取值，用于相应策略和边界判断。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 最大块集合的固定取值，用于相应策略和边界判断。 */
    public static final int MAX_BLOCKS = 32;

    /** 最大呈现字节的固定取值，用于相应策略和边界判断。 */
    public static final int MAX_PRESENTATION_BYTES = 256 * 1024;

    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 日志的固定取值，用于相应策略和边界判断。 */
    private static final System.Logger LOG =
            System.getLogger(PresentationToolResultMapper.class.getName());

    /** 工具类私有构造器，避免创建没有独立运行状态的实例。 */
    private PresentationToolResultMapper() {}

    /**
     * 提取呈现工具结果映射器。
     *
     * @param result 本次处理已有的结果。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次处理得到的结果集合。
     */
    public static List<PresentationBlock> extract(ToolResultBlock result, String toolCallId) {
        return extract(result, toolCallId, () -> {});
    }

    /**
     * 提取呈现工具结果映射器。
     *
     * @param result 本次处理已有的结果。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param onInvalidPresentation 需要在当前执行边界内运行的工作回调。
     * @return 本次处理得到的结果集合。
     */
    public static List<PresentationBlock> extract(
            ToolResultBlock result, String toolCallId, Runnable onInvalidPresentation) {
        if (result == null
                || (result.getState() != ToolResultState.SUCCESS
                        && result.getState() != ToolResultState.RUNNING)) return List.of();
        String text =
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", String::concat)
                        .trim();
        if (text.isEmpty() || text.length() > MAX_PRESENTATION_BYTES) return List.of();
        try {
            JsonNode root = JSON.readTree(text);
            JsonNode envelope = root.path("presentation");
            if (envelope.isMissingNode() && root.path("safeResult").isObject()) {
                envelope = root.path("safeResult").path("presentation");
            }
            if (envelope.isMissingNode() || envelope.isNull()) return List.of();
            return parseEnvelope(envelope, toolCallId);
        } catch (JsonProcessingException error) {
            return List.of();
        } catch (IllegalArgumentException error) {
            // 业务操作可能已经完成。展示契约错误不能变成可重试的工具失败，应省略卡片并保留工具结果。
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Invalid presentation output was ignored: {0}",
                    error.getMessage());
            onInvalidPresentation.run();
            return List.of();
        }
    }

    /**
     * 解析协议包。
     *
     * @param envelope 当前呈现工具结果映射器持有的协议包对象，供相应处理步骤使用。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 本次处理得到的结果集合。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static List<PresentationBlock> parseEnvelope(JsonNode envelope, String toolCallId)
            throws JsonProcessingException {
        if (!envelope.isObject())
            throw new IllegalArgumentException("presentation must be an object");
        int version = envelope.path("schemaVersion").asInt(CURRENT_SCHEMA_VERSION);
        if (version != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported presentation schemaVersion: " + version);
        }
        JsonNode blocks = envelope.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "presentation blocks must contain 1-" + MAX_BLOCKS + " items");
        }
        List<PresentationBlock> result = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            JsonNode value = blocks.get(index);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalArgumentException("presentation block must be an object");
            }
            String type = object.path("type").asText("").trim();
            int blockVersion = object.path("schemaVersion").asInt(version);
            ObjectNode dataNode;
            if (object.path("data").isObject()) {
                dataNode = ((ObjectNode) object.path("data")).deepCopy();
            } else {
                dataNode = object.deepCopy();
                dataNode.remove(List.of("id", "type", "schemaVersion"));
            }
            if (JSON.writeValueAsBytes(dataNode).length > 64 * 1024) {
                throw new IllegalArgumentException("presentation block exceeds 64 KiB");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = JSON.convertValue(dataNode, LinkedHashMap.class);
            result.add(
                    new PresentationBlock(
                            stableId(toolCallId, index, object), type, blockVersion, index, data));
        }
        return List.copyOf(result);
    }

    /**
     * 生成当前操作所需的stableId文本，供调用方继续处理。
     *
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @param index 当前呈现工具结果映射器使用的索引，供其处理与状态记录使用。
     * @param block 当前呈现工具结果映射器持有的块对象，供相应处理步骤使用。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String stableId(String toolCallId, int index, JsonNode block) {
        try {
            MessageDigest digest = DigestUtils.newSha256();
            digest.update(String.valueOf(toolCallId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(index).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(block.toString().getBytes(StandardCharsets.UTF_8));
            return "ui_" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
