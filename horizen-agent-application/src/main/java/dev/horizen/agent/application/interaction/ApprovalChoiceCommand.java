package dev.horizen.agent.application.interaction;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 应用层审批决定命令，携带原执行与审批记录的关联标识。
 */
@RequiredArgsConstructor
@Getter
public final class ApprovalChoiceCommand {
    /**
     * 待确认操作的记录标识，提交决定时用它定位原审批。
     */
    private final String approvalId;

    /**
     * 本次审批是否允许执行；拒绝时不会恢复为已批准的工具调用。
     */
    private final boolean approved;
}
