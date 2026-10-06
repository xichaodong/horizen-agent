package dev.horizen.agent.execution.turn;

import dev.horizen.agent.execution.session.AgentSession;
import dev.horizen.agent.execution.session.ConversationMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session、Turn 与正式消息的持久化边界。
 */
public interface SessionTurnStore {

    /**
     * 在同一持久化工作单元中占用会话、创建执行记录与保存用户消息，返回启动、幂等复用或会话占用结果。
     *
     * @param command 当前会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 实际启动或幂等复用的结果，并标明会话占用、归档等不能启动的原因。
     */
    StartTurnResult startTurn(StartTurnCommand command);

    /**
     * 按执行状态与并发条件更新原执行，关联最终消息并同步会话的活跃执行引用。
     *
     * @param command 当前会话执行存储持有的命令对象，供相应处理步骤使用。
     * @return 本次状态更新的结果，并标明已在目标状态、执行变化或状态变化等并发情形。
     */
    TransitionTurnResult transitionTurn(TransitionTurnCommand command);

    /**
     * 仅为仍由指定执行实例持有的执行续期租约；不把其他实例的执行权转移给调用方。
     *
     * @param ownerKey       宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId         单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param executorId     持有当前执行段的执行实例标识，用于租约与跨实例控制。
     * @param leaseExpiresAt 执行实例租约到期时间，用于判断执行权是否仍有效。
     * @param updatedAt      当前记录最近一次更新的时间。
     * @return 是否仍持有原执行并成功完成本次续期。
     */
    boolean renewLease(
            String ownerKey,
            String turnId,
            String executorId,
            Instant leaseExpiresAt,
            Instant updatedAt);

    /**
     * 仅续租传入的本地所属 Turn，且其租约与执行截止时间必须仍有效。
     */
    default int renewLeases(
            Map<String, List<String>> turnIdsByOwner,
            String executorId,
            Instant leaseExpiresAt,
            Instant updatedAt) {
        int renewed = 0;
        for (Map.Entry<String, List<String>> owner : turnIdsByOwner.entrySet()) {
            for (String turnId : owner.getValue().stream().distinct().toList()) {
                AgentTurn turn = findTurn(owner.getKey(), turnId).orElse(null);
                if (turn != null
                        && turn.getLeaseExpiresAt() != null
                        && turn.getLeaseExpiresAt().isAfter(updatedAt)
                        && turn.getDeadlineAt().isAfter(updatedAt)
                        && renewLease(
                        owner.getKey(), turnId, executorId, leaseExpiresAt, updatedAt)) {
                    renewed++;
                }
            }
        }
        return renewed;
    }

    /**
     * 按 ownerKey 与 sessionId 查找会话，避免同名会话跨归属读取。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AgentSession> findSession(String ownerKey, String sessionId);

    /**
     * 在指定 ownerKey 范围内查找执行事实记录。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param turnId   单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AgentTurn> findTurn(String ownerKey, String turnId);

    /**
     * 查找指定隔离会话的最近一次执行，用于页面观察与恢复。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<AgentTurn> findLatestTurn(String ownerKey, String sessionId);

    /**
     * 分页读取指定归属的会话目录，使用 limit 和 offset 限定返回窗口。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param limit    本次处理或返回数量上限。
     * @param offset   本次读取的起始偏移。
     * @return 本次处理得到的结果集合。
     */
    List<AgentSession> listSessions(String ownerKey, int limit, int offset);

    /**
     * 更新指定归属会话的可读标题，同时记录更新时间。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param title     当前会话执行存储的可读标题，供宿主界面展示。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean renameSession(String ownerKey, String sessionId, String title, Instant updatedAt);

    /**
     * 更新指定归属会话的置顶标记，不改变会话或执行身份。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param pinned    会话是否置顶，影响会话目录展示顺序。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean setSessionPinned(String ownerKey, String sessionId, boolean pinned, Instant updatedAt);

    /**
     * 将指定会话归档，使它不再作为可继续启动执行的活跃会话使用。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param updatedAt 当前记录最近一次更新的时间。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean archiveSession(String ownerKey, String sessionId, Instant updatedAt);

    /**
     * 读取指定会话已经提交的正式消息，不把临时文本增量当作正式消息。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次处理得到的结果集合。
     */
    List<ConversationMessage> listFinalMessages(String ownerKey, String sessionId);

    /**
     * 查询已经超过租约期限的执行，供执行权失联后的协调收敛使用。
     *
     * @param now   用于本次更新或过期判断的当前时间。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<AgentTurn> findExpiredLeases(Instant now, int limit);

    /**
     * 查询超过执行截止时间的执行，供超时收敛流程使用。
     *
     * @param now   用于本次更新或过期判断的当前时间。
     * @param limit 本次处理或返回数量上限。
     * @return 本次处理得到的结果集合。
     */
    List<AgentTurn> findOverdueTurns(Instant now, int limit);
}
