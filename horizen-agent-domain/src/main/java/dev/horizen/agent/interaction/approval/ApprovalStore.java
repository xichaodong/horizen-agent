package dev.horizen.agent.interaction.approval;

import java.util.List;

/**
 * 产品审批记录的持久化边界。
 */
public interface ApprovalStore {

    /**
     * 创建待处理。
     *
     * @param approvals 审批存储或待处理审批集合，用于原执行的暂停与恢复。
     */
    void createPending(List<ApprovalRequest> approvals);

    /**
     * 查找待处理。
     *
     * @param ownerKey  宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param sessionId 会话标识；同名会话在不同 ownerKey 下属于不同的隔离范围。
     * @param turnId    单次用户输入触发的执行标识，用于关联状态、消息和事件。
     * @return 本次处理得到的结果集合。
     */
    List<ApprovalRequest> findPending(String ownerKey, String sessionId, String turnId);

    /**
     * 提交决定并处理审批存储。
     *
     * @param command 当前审批存储持有的命令对象，供相应处理步骤使用。
     * @return 本次操作返回的审批决定结果结果。
     */
    ApprovalDecisionResult decide(ApprovalDecisionCommand command);
}
