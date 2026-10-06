package dev.horizen.agent.interaction.approval;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.beans.ConstructorProperties;
import java.time.Instant;
import java.util.Objects;

/**
 * 对一条待审批工具调用作出批准或拒绝决定。
 */
@Getter
@EqualsAndHashCode
@ToString
public class ApprovalDecisionCommand {
    /**
     * 宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     */
    private final String ownerKey;

    /**
     * 待确认操作的记录标识，提交决定时用它定位原审批。
     */
    private final String approvalId;

    /**
     * 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     */
    private final boolean approved;

    /**
     * 提交当前交互决定的操作方标识。
     */
    private final String decidedBy;

    /**
     * 已决定的时间，用于记录对应生命周期节点。
     */
    private final Instant decidedAt;

    /**
     * 创建审批决定命令，初始化该组件所需的状态、配置或依赖。
     *
     * @param ownerKey   宿主提供的不透明数据隔离键；与会话标识一起定位数据，不解释为业务账号。
     * @param approvalId 待确认操作的记录标识，提交决定时用它定位原审批。
     * @param approved   本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     * @param decidedBy  当前审批决定命令使用的已决定按条件，供其处理与状态记录使用。
     * @param decidedAt  已决定的时间，用于记录对应生命周期节点。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @ConstructorProperties({"ownerKey", "approvalId", "approved", "decidedBy", "decidedAt"})
    public ApprovalDecisionCommand(
            String ownerKey,
            String approvalId,
            boolean approved,
            String decidedBy,
            Instant decidedAt) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey 不能为空");
        }
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("approvalId 不能为空");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy 不能为空");
        }
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");

        this.ownerKey = ownerKey;
        this.approvalId = approvalId;
        this.approved = approved;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }
}
