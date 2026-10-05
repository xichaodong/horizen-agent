package dev.horizen.agent.adapter.agentscope.runtime;

import dev.horizen.agent.execution.turn.TurnStatus;
import dev.horizen.agent.runtime.api.SessionExecutionState;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 使用 AgentScope AgentStateStore 协调 Session 最新 Turn 状态。 */
final class AgentScopeSessionExecutionCoordinator {
    /** 工作状态键使用的固定标识或协议文本。 */
    private static final String STATE_KEY = "session_execution";

    /** 按隔离会话保存工作状态与执行占用信息的存储。 */
    private final AgentStateStore stateStore;

    /**
     * 创建Agent作用域会话执行协调器，初始化该组件所需的状态、配置或依赖。
     *
     * @param stateStore 提供工作状态存储能力的依赖，具体实现由当前组件的组装方传入。
     */
    AgentScopeSessionExecutionCoordinator(AgentStateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /**
     * 按隔离会话原子登记新的活跃执行，拒绝同一会话重叠运行。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param startedAt 当前执行或执行段的开始时间。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<SessionExecutionState> tryStart(
            String ownerKey, String sessionId, String turnId, Instant startedAt) {
        SessionExecutionState next = SessionExecutionState.running(turnId, startedAt);
        if (!stateStore.supportsVersioning()) {
            synchronized (stateStore) {
                Optional<SessionExecutionState> current = get(ownerKey, sessionId);
                if (current.isPresent() && !current.get().getStatus().isTerminal()) {
                    return Optional.empty();
                }
                stateStore.save(
                        ownerKey, sessionId, STATE_KEY, AgentScopeExecutionState.from(next));
                return Optional.of(next);
            }
        }
        while (true) {
            VersionedState<AgentScopeExecutionState> current = versioned(ownerKey, sessionId);
            if (current.isPresent() && !current.value().getStatus().isTerminal()) {
                return Optional.empty();
            }
            long saved =
                    stateStore.saveIfVersion(
                            ownerKey,
                            sessionId,
                            STATE_KEY,
                            AgentScopeExecutionState.from(next),
                            current.version());
            if (saved != AgentStateStore.UNVERSIONED) {
                return Optional.of(next);
            }
        }
    }

    /**
     * 把当前执行标记为等待审批并保存后续恢复需要的工具上下文。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean pauseForApproval(String ownerKey, String sessionId, String turnId) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus() != TurnStatus.RUNNING
                            || !current.getTurnId().equals(turnId)) {
                        return null;
                    }
                    return new SessionExecutionState(
                            current.getTurnId(),
                            TurnStatus.WAITING_APPROVAL,
                            current.getStartedAt(),
                            null,
                            null);
                });
    }

    /**
     * 把当前执行标记为等待澄清回答，保留原执行的恢复定位。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean pauseForAskUser(String ownerKey, String sessionId, String turnId) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus() != TurnStatus.RUNNING
                            || !current.getTurnId().equals(turnId)) return null;
                    return new SessionExecutionState(
                            current.getTurnId(),
                            TurnStatus.WAITING_ASK_USER,
                            current.getStartedAt(),
                            null,
                            null);
                });
    }

    /**
     * 根据审批决定恢复原执行，不创建新的执行标识。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean resumeApproval(String ownerKey, String sessionId, String turnId) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus() != TurnStatus.WAITING_APPROVAL
                            || !current.getTurnId().equals(turnId)) {
                        return null;
                    }
                    return new SessionExecutionState(
                            current.getTurnId(),
                            TurnStatus.RUNNING,
                            current.getStartedAt(),
                            null,
                            null);
                });
    }

    /**
     * 根据澄清回答恢复原执行，不把回答混入其他执行。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean resumeAskUser(String ownerKey, String sessionId, String turnId) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus() != TurnStatus.WAITING_ASK_USER
                            || !current.getTurnId().equals(turnId)) return null;
                    return new SessionExecutionState(
                            current.getTurnId(),
                            TurnStatus.RUNNING,
                            current.getStartedAt(),
                            null,
                            null);
                });
    }

    /**
     * 将原执行记录为结束状态，并释放它对会话的活跃占用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param terminalStatus 当前Agent作用域会话执行协调器持有的终态状态对象，供相应处理步骤使用。
     * @param finishedAt 执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean finish(
            String ownerKey,
            String sessionId,
            String turnId,
            TurnStatus terminalStatus,
            Instant finishedAt,
            String failureCode) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus() != TurnStatus.RUNNING
                            || !current.getTurnId().equals(turnId)) {
                        return null;
                    }
                    return current.finish(terminalStatus, finishedAt, failureCode);
                });
    }

    /**
     * 在当前执行仍匹配时收敛为超时，防止旧超时回调覆盖新执行。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param finishedAt 执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean timeoutCurrent(
            String ownerKey, String sessionId, Instant finishedAt, String failureCode) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus().isTerminal()
                            && current.getStatus() != TurnStatus.CANCELLED) {
                        return null;
                    }
                    return current.finish(TurnStatus.TIMED_OUT, finishedAt, failureCode);
                });
    }

    /**
     * 在宿主故障收敛场景下结束匹配的原执行。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId 单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @param terminalStatus 当前Agent作用域会话执行协调器持有的终态状态对象，供相应处理步骤使用。
     * @param finishedAt 执行结束时间；尚未结束的记录可以没有该时间。
     * @param failureCode 机器可识别的失败分类，供状态恢复与错误展示使用。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    boolean forceFinish(
            String ownerKey,
            String sessionId,
            String turnId,
            TurnStatus terminalStatus,
            Instant finishedAt,
            String failureCode) {
        return update(
                ownerKey,
                sessionId,
                current -> {
                    if (current.getStatus().isTerminal() || !current.getTurnId().equals(turnId)) {
                        return null;
                    }
                    return current.finish(terminalStatus, finishedAt, failureCode);
                });
    }

    /**
     * 读取Agent作用域会话执行协调器。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 可用结果；没有可用对象时以空 Optional 表示。
     */
    Optional<SessionExecutionState> get(String ownerKey, String sessionId) {
        return stateStore
                .get(ownerKey, sessionId, STATE_KEY, AgentScopeExecutionState.class)
                .map(value -> value);
    }

    /**
     * 使用存储版本与原执行身份更新共享运行状态。
     * 共享状态的关键更新在互斥区内完成。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param updater 将输入转换为目标结果的函数。
     * @return 本次检查是否通过或本次更新是否成功。
     */
    private boolean update(
            String ownerKey,
            String sessionId,
            Function<SessionExecutionState, SessionExecutionState> updater) {
        if (!stateStore.supportsVersioning()) {
            synchronized (stateStore) {
                SessionExecutionState current = get(ownerKey, sessionId).orElse(null);
                if (current == null) {
                    return false;
                }
                SessionExecutionState next = updater.apply(current);
                if (next == null) {
                    return false;
                }
                stateStore.save(
                        ownerKey, sessionId, STATE_KEY, AgentScopeExecutionState.from(next));
                return true;
            }
        }
        while (true) {
            VersionedState<AgentScopeExecutionState> current = versioned(ownerKey, sessionId);
            if (!current.isPresent()) {
                return false;
            }
            SessionExecutionState next = updater.apply(current.value());
            if (next == null) {
                return false;
            }
            long saved =
                    stateStore.saveIfVersion(
                            ownerKey,
                            sessionId,
                            STATE_KEY,
                            AgentScopeExecutionState.from(next),
                            current.version());
            if (saved != AgentStateStore.UNVERSIONED) {
                return true;
            }
        }
    }

    /**
     * 计算或取得本方法声明的结果，供当前AgentScopeSessionExecutionCoordinator处理步骤使用。
     *
     * @param ownerKey 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @return 本次操作返回的带版本工作状态结果。
     */
    private VersionedState<AgentScopeExecutionState> versioned(String ownerKey, String sessionId) {
        return stateStore.getVersioned(
                ownerKey, sessionId, STATE_KEY, AgentScopeExecutionState.class);
    }
}
