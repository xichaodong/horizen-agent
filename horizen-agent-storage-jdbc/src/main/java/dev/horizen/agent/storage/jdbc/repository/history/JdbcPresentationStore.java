package dev.horizen.agent.storage.jdbc.repository.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizen.agent.common.json.JsonUtils;
import dev.horizen.agent.domain.presentation.PresentationBlock;
import dev.horizen.agent.domain.presentation.PresentationRecord;
import dev.horizen.agent.domain.presentation.PresentationStore;
import dev.horizen.agent.storage.jdbc.codec.JdbcHistoryJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.PresentationMapper;
import dev.horizen.agent.storage.jdbc.model.ConversationHistoryRow;

import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/** 声明式展示块的 JDBC 持久化实现。 */
public class JdbcPresentationStore implements PresentationStore {
    /** 本组件独立的 JSON 编解码器，用于维护对应的持久化或协议格式。 */
    private static final ObjectMapper JSON = JsonUtils.newMapper();

    /** 映射的固定取值，用于相应策略和边界判断。 */
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final PresentationMapper mapper;

    /**
     * 创建JDBC呈现存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcPresentationStore(DataSource dataSource) {
        this.mapper = MyBatisSessions.create(dataSource).getMapper(PresentationMapper.class);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcPresentationStore(PresentationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 创建候选查找。
     *
     * @param record 当前JDBC呈现存储持有的记录对象，供相应处理步骤使用。
     * @return 本次操作返回的呈现记录结果。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public PresentationRecord createOrFind(PresentationRecord record) {
        Objects.requireNonNull(record, "record");
        String payload =
                JdbcHistoryJson.encode(
                        Map.of(
                                "toolCallId",
                                record.getToolCallId(),
                                "toolName",
                                record.getToolName(),
                                "block",
                                record.getBlock()));
        try {
            mapper.updateCreateOrFind(
                    record.getOwnerKey(),
                    record.getSessionId(),
                    record.getTurnId(),
                    record.getBlock().getBlockId(),
                    payload,
                    Timestamp.from(record.getCreatedAt()),
                    Timestamp.from(record.getCreatedAt()));
        } catch (DuplicateKeyException ignored) {
            // Turn 和事件回放是幂等的，但同一 ID 的含义绝不能改变。
        }
        PresentationRecord stored =
                find(record.getOwnerKey(), record.getBlock().getBlockId()).orElseThrow();
        if (!same(record, stored)) {
            throw new IllegalStateException(
                    "presentation block id was reused with different content");
        }
        return stored;
    }

    /**
     * 查找JDBC呈现存储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param blockId 结构化呈现块的标识，客户端用它去重与更新同一张卡片。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<PresentationRecord> find(String ownerKey, String blockId) {
        List<PresentationRecord> values =
                mapper.selectFind(ownerKey, blockId).stream().map(rs -> map(rs)).toList();
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /**
     * 查询列表中的目标范围会话。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<PresentationRecord> listForSession(String ownerKey, String sessionId) {
        return mapper.selectListForSession(ownerKey, sessionId).stream()
                .map(rs -> map(rs))
                .toList()
                .stream()
                .sorted(
                        Comparator.comparing(PresentationRecord::getCreatedAt)
                                .thenComparing(PresentationRecord::getTurnId)
                                .thenComparing(PresentationRecord::getToolCallId)
                                .thenComparingInt(value -> value.getBlock().getPosition()))
                .toList();
    }

    /**
     * 映射JDBC呈现存储。
     *
     * @param rs 当前JDBC呈现存储持有的rs对象，供相应处理步骤使用。
     * @return 本次操作返回的呈现记录结果。
     */
    private static PresentationRecord map(ConversationHistoryRow rs) {
        var payload = JdbcHistoryJson.object(rs.getPayloadJson());
        var value = payload.path("block");
        PresentationBlock block =
                new PresentationBlock(
                        rs.getRecordId(),
                        value.path("type").asText(),
                        value.path("schemaVersion").asInt(),
                        value.path("position").asInt(),
                        parse(value.path("data").toString()));
        return new PresentationRecord(
                rs.getOwnerKey(),
                rs.getSessionId(),
                rs.getTurnId(),
                payload.path("toolCallId").asText(),
                payload.path("toolName").asText(),
                block,
                rs.getCreatedAt().toInstant());
    }

    /**
     * 检查same对应的条件，供调用方选择后续处理分支。
     *
     * @param left 当前JDBC呈现存储持有的left对象，供相应处理步骤使用。
     * @param right 当前JDBC呈现存储持有的right对象，供相应处理步骤使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private static boolean same(PresentationRecord left, PresentationRecord right) {
        return left.getSessionId().equals(right.getSessionId())
                && left.getTurnId().equals(right.getTurnId())
                && left.getToolCallId().equals(right.getToolCallId())
                && left.getToolName().equals(right.getToolName())
                && left.getBlock().getType().equals(right.getBlock().getType())
                && left.getBlock().getSchemaVersion() == right.getBlock().getSchemaVersion()
                && left.getBlock().getPosition() == right.getBlock().getPosition()
                // JDBC JSON 往返可能将范围内的 Long 缩窄为 Integer，对象字段顺序也不具有语义。
                // 应比较解析后的 JSON 树，不比较 Map 包装类型或序列化字段顺序。
                && jsonEquivalent(left.getBlock().getData(), right.getBlock().getData());
    }

    /**
     * 检查jsonEquivalent对应的条件，供调用方选择后续处理分支。
     *
     * @param left left的索引映射，供按键查找或归并当前组件的数据。
     * @param right right的索引映射，供按键查找或归并当前组件的数据。
     * @return 本次检查是否通过或本次更新是否成功。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static boolean jsonEquivalent(Map<String, Object> left, Map<String, Object> right) {
        try {
            return JSON.readTree(json(left)).equals(JSON.readTree(json(right)));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid presentation data", error);
        }
    }

    /**
     * 把当前输入编码为 JSON 文本，供协议输出或持久化保存使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次处理生成或读取的文本。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid presentation data", error);
        }
    }

    /**
     * 解析JDBC呈现存储。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 按返回类型约定组织的结果映射。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    private static Map<String, Object> parse(String value) {
        try {
            return JSON.readValue(value, MAP);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("invalid stored presentation data", error);
        }
    }
}
