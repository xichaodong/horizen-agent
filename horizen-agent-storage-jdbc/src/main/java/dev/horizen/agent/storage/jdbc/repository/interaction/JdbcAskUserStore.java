package dev.horizen.agent.storage.jdbc.repository.interaction;

import dev.horizen.agent.domain.askuser.AskUserRequest;
import dev.horizen.agent.domain.askuser.AskUserStatus;
import dev.horizen.agent.domain.askuser.AskUserStore;
import dev.horizen.agent.storage.jdbc.codec.JdbcInteractionJson;
import dev.horizen.agent.storage.jdbc.config.MyBatisSessions;
import dev.horizen.agent.storage.jdbc.mapper.AskUserMapper;
import dev.horizen.agent.storage.jdbc.model.InteractionRow;
import dev.horizen.agent.storage.jdbc.support.RowValues;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/** 澄清状态共用 ha_interaction 表，但使用独立的类型和状态迁移规则。 */
public class JdbcAskUserStore implements AskUserStore {
    /** 本组件使用的映射器或编解码器，负责协议与存储表示之间的转换。 */
    private final AskUserMapper mapper;

    /**
     * 创建JDBC提问用户存储，初始化该组件所需的状态、配置或依赖。
     *
     * @param dataSource 当前存储适配器使用的数据源；资源所有权由组装方约定。
     */
    public JdbcAskUserStore(DataSource dataSource) {
        this.mapper = MyBatisSessions.create(dataSource).getMapper(AskUserMapper.class);
    }

    /** 供服务 IoC 容器注入依赖的构造方法。 */
    public JdbcAskUserStore(AskUserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * 创建候选查找。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的提问用户请求结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public AskUserRequest createOrFind(AskUserRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getStatus() != AskUserStatus.PENDING) {
            throw new IllegalArgumentException("new ask_user request must be pending");
        }
        try {
            mapper.updateCreateOrFind(
                    request.getOwnerKey(),
                    request.getAskUserId(),
                    request.getSessionId(),
                    request.getTurnId(),
                    request.getReplyId(),
                    request.getToolCallId(),
                    JdbcInteractionJson.encode(Map.of("questionsJson", request.getQuestionsJson())),
                    JdbcInteractionJson.encode(Map.of("answersJson", request.getAnswersJson())),
                    timestamp(request.getExpiresAt()),
                    timestamp(request.getCreatedAt()),
                    timestamp(request.getCreatedAt()));
        } catch (DuplicateKeyException ignored) {
            // 仅允许重放原始请求，问题内容始终不变。
        }
        AskUserRequest found =
                findByTool(request.getOwnerKey(), request.getTurnId(), request.getToolCallId())
                        .orElseThrow();
        if (!found.getSessionId().equals(request.getSessionId())
                || !Objects.equals(found.getReplyId(), request.getReplyId())
                || !found.getQuestionsJson().equals(request.getQuestionsJson())) {
            throw new IllegalStateException(
                    "ask_user identity was reused with a different request");
        }
        return found;
    }

    /**
     * 查找JDBC提问用户存储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param id 目标对象的标识。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    @Override
    public Optional<AskUserRequest> find(String ownerKey, String id) {
        return first(mapper.selectFind(ownerKey, id).stream().map(rs -> map(rs)).toList());
    }

    /**
     * 查找按条件工具。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param toolCallId 一次工具调用的标识，用于配对参数、结果和审批事件。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private Optional<AskUserRequest> findByTool(String ownerKey, String turnId, String toolCallId) {
        return first(
                mapper.selectFindByTool(ownerKey, turnId, toolCallId).stream()
                        .map(rs -> map(rs))
                        .toList());
    }

    /**
     * 查找待处理。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    @Override
    public List<AskUserRequest> findPending(String ownerKey, String sessionId, String turnId) {
        return mapper.selectFindPending(ownerKey, sessionId, turnId).stream()
                .map(rs -> map(rs))
                .toList();
    }

    /**
     * 解析JDBC提问用户存储。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param id 目标对象的标识。
     * @param status 当前记录或执行的状态，具体取值由所属领域或协议约定。
     * @param answers 用户对澄清问题的回答集合，按问题标识关联选项和补充文本。
     * @param at 当前JDBC提问用户存储持有的时间对象，供相应处理步骤使用。
     * @param version 记录版本，用于乐观并发控制或区分协议版本。
     * @return 本次操作返回的提问用户请求结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     * @throws IllegalStateException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Transactional(
            transactionManager = "agentTransactionManager",
            rollbackFor = Exception.class,
            timeout = 15)
    @Override
    public AskUserRequest resolve(
            String ownerKey,
            String id,
            AskUserStatus status,
            String answers,
            Instant at,
            long version) {
        Objects.requireNonNull(status, "status");
        if (status == AskUserStatus.PENDING) {
            throw new IllegalArgumentException("ask_user resolution must be a final state");
        }
        String response =
                JdbcInteractionJson.encode(Map.of("answersJson", answers == null ? "[]" : answers));
        int changed =
                mapper.updateResolve(
                        status.name(),
                        response,
                        timestamp(at),
                        timestamp(at),
                        ownerKey,
                        id,
                        version);
        if (changed != 1) throw new IllegalStateException("ask_user state changed");
        return find(ownerKey, id).orElseThrow();
    }

    /**
     * 映射JDBC提问用户存储。
     *
     * @param rs 当前JDBC提问用户存储持有的rs对象，供相应处理步骤使用。
     * @return 本次操作返回的提问用户请求结果。
     */
    private static AskUserRequest map(InteractionRow rs) {
        var request = JdbcInteractionJson.read(rs.getRequestJson());
        var response = JdbcInteractionJson.read(rs.getResponseJson());
        return new AskUserRequest(
                rs.getOwnerKey(),
                rs.getSessionId(),
                rs.getTurnId(),
                rs.getInteractionId(),
                rs.getReplyId(),
                rs.getToolCallId(),
                JdbcInteractionJson.text(request, "questionsJson"),
                JdbcInteractionJson.text(response, "answersJson"),
                AskUserStatus.valueOf(rs.getStatus()),
                rs.getCreatedAt().toInstant(),
                rs.getExpiresAt().toInstant(),
                RowValues.nullableInstant(rs.getResolvedAt()),
                rs.getVersion());
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcAskUserStore处理步骤使用。
     *
     * @param value 待校验、转换或保存的原始值。
     * @return 本次操作返回的时间戳结果。
     */
    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    /**
     * 计算或取得本方法声明的结果，供当前JdbcAskUserStore处理步骤使用。
     *
     * @param values 本次批量处理的值集合。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    private static <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
}
